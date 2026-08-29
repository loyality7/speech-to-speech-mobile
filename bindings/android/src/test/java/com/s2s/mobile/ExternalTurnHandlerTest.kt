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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the seam an external caller (e.g. an agent harness) needs to
 * redirect turn handling: with [externalTurnHandler] set, S2SEngine must
 * NOT call ContextEngine.addUser or LanguageModel.generate itself — the
 * handler receives the raw text and owns everything from there.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalTurnHandlerTest {

    private class RecordingLanguageModel : LanguageModel {
        var generateCallCount = 0
            private set
        override fun initialize(): Result<Unit> = Result.success(Unit)
        override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
            generateCallCount++
            sink.onToken("should never be reached")
            sink.onComplete()
        }
        override fun cancel() {}
        override fun resetContext() {}
        override fun trimMemory() {}
        override fun release() {}
    }

    private class RecordingContextEngine : com.s2s.mobile.pipeline.ContextEngine {
        var addUserCallCount = 0
            private set
        override fun addUser(text: String) { addUserCallCount++ }
        override fun replaceLastUser(text: String) {}
        override fun addAssistant(text: String) {}
        override fun dropLastUserIfUnanswered() {}
        override fun addToolResult(name: String, output: String) {}
        override fun messages(extraSystem: String?): List<ChatMessage> = emptyList()
        override fun setSystemPrompt(prompt: String) {}
        override fun clear() {}
        override fun toJson(): String = ""
        override fun fromJson(json: String) {}
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

    @Test
    fun `sendText with externalTurnHandler set never calls generate or addUser`() = runBlocking {
        val llm = RecordingLanguageModel()
        val history = RecordingContextEngine()
        val received = mutableListOf<String>()
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
            externalTurnHandler = { text -> received += text },
        )
        e.initialize().getOrThrow()

        e.sendText("what's the weather")

        assertEquals(listOf("what's the weather"), received)
        assertEquals(0, llm.generateCallCount)
        assertEquals(0, history.addUserCallCount)
    }

    @Test
    fun `without externalTurnHandler, sendText behaves exactly as before`() = runBlocking {
        val llm = RecordingLanguageModel()
        val history = RecordingContextEngine()
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

        e.sendText("hello")
        Thread.sleep(200)

        assertEquals(1, llm.generateCallCount)
        assertEquals(1, history.addUserCallCount)
    }

    @Test
    fun `speakAssistantText still works normally when externalTurnHandler is set`() = runBlocking {
        val llm = RecordingLanguageModel()
        val history = RecordingContextEngine()
        val synth = FakeSynthesizer()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = S2SEngine(
            context,
            testConfig(),
            languageModel = llm,
            history = history,
            vad = FakeVad(),
            recognizer = FakeRecognizer(),
            synthesizer = synth,
            microphone = FakeMic(),
            externalTurnHandler = { },
        )
        e.initialize().getOrThrow()

        e.speakAssistantText("This is the final answer.")
        Thread.sleep(500)

        assertTrue(synth.synthesizedTexts.any { it.contains("final answer") })
    }

    @Test
    fun `continueTurn routes to the external handler too, not generated internally`() = runBlocking {
        // Real defect this reproduces: onFrame's THINKING branch calls
        // continueTurn() when a second utterance arrives while a turn is
        // still in flight. continueTurn() used to call startGeneration()
        // unconditionally — the internal path — even with an
        // externalTurnHandler set. That fired a second, uncoordinated
        // LanguageModel.generate() call alongside whatever the handler (an
        // agent harness) was still doing with the SAME instance, with no
        // shared cancellation. On a real device this produced two
        // overlapping remote calls and both replies were lost — no TTS, no
        // error, for every utterance after the first.
        //
        // Exercises continueTurn() directly via reflection rather than
        // driving the full mic/speaker pipeline through start() — the other
        // tests in this file already prove beginTurn()'s equivalent contract
        // through the public sendText() API; Robolectric's AudioTrack stub
        // does not support the real start()/onFrame path this bug lives on.
        val llm = RecordingLanguageModel()
        val history = RecordingContextEngine()
        val received = mutableListOf<String>()
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
            externalTurnHandler = { text -> received += text },
        )
        e.initialize().getOrThrow()

        // Puts the engine into the state continueTurn() assumes: a turn
        // already in flight via the external handler, same as beginTurn()
        // would after the first utterance.
        e.sendText("first utterance")

        val continueTurnMethod = S2SEngine::class.java.getDeclaredMethod("continueTurn", String::class.java)
        continueTurnMethod.isAccessible = true
        continueTurnMethod.invoke(e, "second utterance")

        // continueTurn() merges with pendingUserText by design (see its own
        // doc: "their earlier words are kept") — the handler correctly sees
        // the whole utterance-so-far, not just the new fragment.
        assertEquals(listOf("first utterance", "first utterance second utterance"), received)
        assertEquals("internal generate() must never run once an external handler owns the turn", 0, llm.generateCallCount)
        assertEquals("history must stay untouched — the external handler owns recording the turn", 0, history.addUserCallCount)
    }
}
