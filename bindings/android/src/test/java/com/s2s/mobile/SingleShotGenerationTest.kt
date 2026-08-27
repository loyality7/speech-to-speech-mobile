package com.s2s.mobile

import androidx.test.core.app.ApplicationProvider
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.ModelPaths
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.pipeline.AudioInput
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.SpeechSynthesizer
import com.s2s.mobile.pipeline.TokenSink
import com.s2s.mobile.pipeline.Transcript
import com.s2s.mobile.pipeline.Voice
import com.s2s.mobile.pipeline.VoiceActivityDetector
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the post-tool-extraction contract: [S2SEngine] performs exactly one
 * [LanguageModel.generate] call per request and never inspects, parses, or
 * dispatches the result — no tool interpretation, no recursion, nothing
 * spoken automatically. An external caller decides what the text means and,
 * for a final answer, calls [S2SEngine.speakAssistantText] explicitly.
 *
 * Real [S2SEngine] instances, not isolated fakes — Robolectric provides the
 * Android runtime [S2SEngine.initialize] needs (a real, simulated
 * `AudioTrack`-backed `SpeakerOutput` is built internally and is not
 * injectable, so no fake-only test can exercise this path).
 */
@RunWith(RobolectricTestRunner::class)
class SingleShotGenerationTest {

    private class RecordingLanguageModel(private val reply: String) : LanguageModel {
        var generateCallCount = 0
            private set
        var cancelCallCount = 0
            private set
        var lastMessages: List<ChatMessage> = emptyList()

        override fun initialize(): Result<Unit> = Result.success(Unit)

        override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
            generateCallCount++
            lastMessages = messages
            sink.onToken(reply)
            sink.onComplete()
        }

        override fun cancel() {
            cancelCallCount++
        }

        override fun resetContext() {}
        override fun trimMemory() {}
        override fun release() {}
    }

    private class BlockingLanguageModel : LanguageModel {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        var cancelCallCount = 0
            private set

        override fun initialize(): Result<Unit> = Result.success(Unit)

        override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
            startedLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
            // Cancellation does not force generate() to return early in this
            // fake — same as a real backend, cancel() just tells it to stop
            // emitting/producing; the caller-side turn-staleness check is
            // what actually discards anything it emits after that.
        }

        override fun cancel() {
            cancelCallCount++
            releaseLatch.countDown()
        }

        override fun resetContext() {}
        override fun trimMemory() {}
        override fun release() {}
    }

    private class FakeVad : VoiceActivityDetector {
        override val frameSize = 512
        override fun initialize() = Result.success(Unit)
        override fun accept(frame: FloatArray) = false
        override fun reset() {}
        override fun release() {}
    }

    private class FakeRecognizer : SpeechRecognizer {
        override fun initialize() = Result.success(Unit)
        override fun accept(frame: FloatArray): Transcript = Transcript.Nothing
        override fun reset() {}
        override fun release() {}
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        override val sampleRate = 16000
        override val voices = listOf(Voice(0, "test"))
        val synthesizedTexts = mutableListOf<String>()

        override fun initialize() = Result.success(Unit)
        override fun selectVoice(voiceId: Int) {}
        override fun synthesize(text: String, keepGoing: () -> Boolean, onChunk: (FloatArray) -> Unit) {
            synthesizedTexts += text
            if (keepGoing()) onChunk(FloatArray(160) { 0.1f })
        }
        override fun release() {}
    }

    private class FakeMic : AudioInput {
        override val sampleRate = 16000
        override val frameSize = 512
        override fun start(onFrame: (FloatArray) -> Unit): Boolean = true
        override fun stop() {}
    }

    private fun testConfig() = S2SConfig(
        models = ModelPaths(vadModel = "vad", sttDir = "stt", llmModel = "llm", ttsDir = "tts"),
        audio = AudioConfig(manageForegroundService = false, manageAudioFocus = false),
        warmUpOnInit = false,
    )

    private fun engine(languageModel: LanguageModel, synthesizer: FakeSynthesizer = FakeSynthesizer()): S2SEngine {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return S2SEngine(
            context,
            testConfig(),
            languageModel = languageModel,
            history = FakeContextEngine("system"),
            vad = FakeVad(),
            recognizer = FakeRecognizer(),
            synthesizer = synthesizer,
            microphone = FakeMic(),
        )
    }

    @Test
    fun `sendText causes exactly one generate call`() = runBlocking {
        val llm = RecordingLanguageModel("hello back")
        val e = engine(llm)
        e.initialize().getOrThrow()

        e.sendText("hi")
        Thread.sleep(200) // llmWorker is async

        assertEquals(1, llm.generateCallCount)
    }

    @Test
    fun `tool-call-shaped output is not executed or re-generated`() = runBlocking {
        val toolCallText = """{"tool": "get_weather", "arguments": {"location": "NYC"}}"""
        val llm = RecordingLanguageModel(toolCallText)
        val e = engine(llm)
        e.initialize().getOrThrow()

        e.sendText("what's the weather")
        Thread.sleep(200)

        // Exactly one call — the engine never recognized this as a tool call,
        // never executed anything, never called generate() a second time.
        assertEquals(1, llm.generateCallCount)
    }

    @Test
    fun `no automatic recursion regardless of how many times generate is invoked`() = runBlocking {
        val llm = RecordingLanguageModel("some reply")
        val e = engine(llm)
        e.initialize().getOrThrow()

        e.sendText("first")
        Thread.sleep(150)
        e.sendText("second")
        Thread.sleep(150)

        // Two explicit calls in, exactly two generate() calls out — never more
        // than one generate() per sendText(), confirming nothing internally
        // chains additional generations onto a single request.
        assertEquals(2, llm.generateCallCount)
    }

    @Test
    fun `completed generation text is observable via AssistantDone`() = runBlocking {
        val llm = RecordingLanguageModel("the raw model output")
        val e = engine(llm)
        e.initialize().getOrThrow()

        val received = mutableListOf<String>()
        val subscribed = CountDownLatch(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val job = scope.launch {
            e.events.onSubscription { subscribed.countDown() }.collect { event ->
                if (event is S2SEvent.AssistantDone) received += event.text
            }
        }
        subscribed.await(2, TimeUnit.SECONDS)

        e.sendText("hi")
        Thread.sleep(200)
        job.cancel()

        assertTrue(received.contains("the raw model output"))
    }

    @Test
    fun `nothing is spoken automatically after generation completes`() = runBlocking {
        val llm = RecordingLanguageModel("plain text or a tool call, either way")
        val synth = FakeSynthesizer()
        val e = engine(llm, synth)
        e.initialize().getOrThrow()

        e.sendText("hi")
        Thread.sleep(300)

        assertTrue(synth.synthesizedTexts.isEmpty())
    }

    @Test
    fun `speakAssistantText sends text to the synthesizer`() = runBlocking {
        val llm = RecordingLanguageModel("irrelevant")
        val synth = FakeSynthesizer()
        val e = engine(llm, synth)
        e.initialize().getOrThrow()

        e.speakAssistantText("This is the final answer.")
        Thread.sleep(300)

        assertTrue(synth.synthesizedTexts.any { it.contains("final answer") })
    }

    @Test
    fun `speakAssistantText does not add a user turn to context`() = runBlocking {
        val llm = RecordingLanguageModel("irrelevant")
        val history = FakeContextEngine("system")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = S2SEngine(
            context,
            testConfig(),
            languageModel = llm,
            history = history,
            vad = FakeVad(),
            recognizer = FakeRecognizer(),
            synthesizer = FakeSynthesizer(),
            microphone = FakeMic(),
        )
        e.initialize().getOrThrow()

        val before = history.messages().size
        e.speakAssistantText("final answer text")
        Thread.sleep(200)

        // speakAssistantText is speech-only — it must not call addUser (or
        // addAssistant a second time; generate()'s onComplete already does
        // that when the text came from a real generation).
        assertEquals(before, history.messages().size)
    }

    @Test
    fun `cancelling via interrupt reaches LanguageModel cancel`() = runBlocking {
        val llm = BlockingLanguageModel()
        val e = engine(llm)
        e.initialize().getOrThrow()

        e.sendText("hi")
        assertTrue("generate() should have started", llm.startedLatch.await(2, TimeUnit.SECONDS))

        e.interrupt()

        assertTrue(llm.cancelCallCount > 0)
    }

    @Test
    fun `session identity is stable and readable`() = runBlocking {
        val llm = RecordingLanguageModel("reply")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixedSessionId = "fixed-session-123"
        val e = S2SEngine(
            context,
            testConfig(),
            languageModel = llm,
            history = FakeContextEngine("system"),
            vad = FakeVad(),
            recognizer = FakeRecognizer(),
            synthesizer = FakeSynthesizer(),
            microphone = FakeMic(),
            sessionId = fixedSessionId,
        )

        assertEquals(fixedSessionId, e.sessionId)
    }
}
