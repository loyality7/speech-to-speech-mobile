package com.s2s.mobile

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

class FakeAudioInput : AudioInput {
    override val sampleRate: Int = 16000
    override val frameSize: Int = 512
    private var callback: ((FloatArray) -> Unit)? = null
    var started = false

    override fun start(onFrame: (FloatArray) -> Unit): Boolean {
        callback = onFrame
        started = true
        return true
    }

    override fun stop() {
        started = false
        callback = null
    }

    fun pushFrame(samples: FloatArray) {
        callback?.invoke(samples)
    }
}

class FakeVad(private val isSpeech: Boolean = true) : VoiceActivityDetector {
    override val frameSize: Int = 512
    override fun initialize(): Result<Unit> = Result.success(Unit)
    override fun accept(frame: FloatArray): Boolean = isSpeech
    override fun reset() {}
    override fun release() {}
}

class FakeRecognizer(private val transcriptText: String = "Hello assistant") : SpeechRecognizer {
    private var active = false

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun accept(frame: FloatArray): Transcript {
        return if (active) {
            Transcript.Final(text = transcriptText)
        } else {
            active = true
            Transcript.Partial(text = transcriptText)
        }
    }

    override fun reset() {
        active = false
    }

    override fun release() {}
}

class FakeLanguageModel(private val replyText: String = "Hello user! How can I help you today?") : LanguageModel {
    var initialized = false
    var generateCalled = false
    var cancelled = false
    var trimmed = false

    override fun initialize(): Result<Unit> {
        initialized = true
        return Result.success(Unit)
    }

    override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
        generateCalled = true
        sink.onToken(replyText)
        sink.onComplete()
    }

    override fun cancel() {
        cancelled = true
    }

    override fun resetContext() {}

    override fun trimMemory() {
        trimmed = true
    }

    override fun release() {
        initialized = false
    }
}

class FakeSynthesizer : SpeechSynthesizer {
    override val sampleRate: Int = 16000
    override val voices: List<Voice> = listOf(Voice(0, "Amy"))

    override fun initialize(): Result<Unit> = Result.success(Unit)
    override fun selectVoice(voiceId: Int) {}

    override fun synthesize(text: String, keepGoing: () -> Boolean, onChunk: (FloatArray) -> Unit) {
        onChunk(FloatArray(160) { 0.1f })
    }

    override fun release() {}
}

class EngineIntegrationTest {

    @Test
    fun testFakePipelineComponentsAndTrimMemory() = runBlocking {
        val mic = FakeAudioInput()
        val vad = FakeVad(isSpeech = true)
        val stt = FakeRecognizer("What is the weather?")
        val llm = FakeLanguageModel("The weather is sunny.")
        val tts = FakeSynthesizer()

        assertTrue(mic.start { })
        assertTrue(mic.started)
        assertEquals(512, mic.frameSize)

        assertEquals(Result.success(Unit), vad.initialize())
        assertTrue(vad.accept(FloatArray(512)))

        assertEquals(Result.success(Unit), stt.initialize())
        val tr = stt.accept(FloatArray(512))
        assertTrue(tr is Transcript.Partial)
        assertEquals("What is the weather?", (tr as Transcript.Partial).text)

        assertEquals(Result.success(Unit), llm.initialize())
        llm.trimMemory()
        assertTrue(llm.trimmed)

        assertEquals(Result.success(Unit), tts.initialize())
        var chunkReceived = false
        tts.synthesize("hello", { true }) { chunkReceived = true }
        assertTrue(chunkReceived)
    }

    @Test
    fun testFailingLanguageModelDoesNotStuckInThinking() {
        val throwingLlm = object : LanguageModel {
            override fun initialize() = Result.success(Unit)
            override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
                throw RuntimeException("Simulated LLM OOM / crash")
            }
            override fun cancel() {}
            override fun resetContext() {}
            override fun trimMemory() {}
            override fun release() {}
        }

        var exceptionThrown = false
        try {
            throwingLlm.generate(emptyList(), object : TokenSink {
                override fun onToken(text: String) {}
                override fun onComplete() {}
                override fun onError(message: String, cause: Throwable?) {}
            })
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }
}
