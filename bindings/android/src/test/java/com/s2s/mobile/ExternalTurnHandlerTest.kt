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
    fun `a second sendText while THINKING still never generates internally`() = runBlocking {
        // History: onFrame's THINKING branch used to call continueTurn(),
        // which called startGeneration() — the internal path — even with an
        // externalTurnHandler set. That fired a second, uncoordinated
        // LanguageModel.generate() call alongside whatever the handler (an
        // agent harness) was still doing with the SAME instance, with no
        // shared cancellation: two overlapping remote calls, both replies
        // lost, no TTS, for every utterance after the first.
        //
        // continueTurn() is gone — TurnAggregator now owns merging segments
        // into one turn, and the engine dispatches only at a single commit
        // point (see TurnAggregatorTest for that behaviour). What this test
        // still guards is the invariant that made the old bug so damaging:
        // with an external handler set, the engine must NEVER run generation
        // or touch history itself, no matter how many turns arrive or what
        // state it is in.
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

        // First turn: engine moves to THINKING and waits on the handler,
        // which in this test never replies.
        e.sendText("first utterance")
        // Second turn arriving while THINKING — the state the old bug lived in.
        e.sendText("second utterance")

        assertEquals(listOf("first utterance", "second utterance"), received)
        assertEquals("internal generate() must never run once an external handler owns the turn", 0, llm.generateCallCount)
        assertEquals("history must stay untouched — the external handler owns recording the turn", 0, history.addUserCallCount)
    }
}
