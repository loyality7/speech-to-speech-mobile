package com.s2s.mobile.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.s2s.mobile.config.TtsConfig
import com.s2s.mobile.pipeline.SpeechSynthesizer
import com.s2s.mobile.pipeline.TtsBackend
import com.s2s.mobile.pipeline.Voice
import java.io.File

/**
 * Neural speech synthesis on sherpa-onnx, covering the Kokoro, VITS/Piper,
 * Matcha and Kitten families behind one interface.
 *
 * Kokoro is the default and mirrors the Python pipeline's `TTS/kokoro_handler.py`
 * — 24 kHz, multi-voice, multilingual.
 *
 * Audio streams out chunk by chunk as the model produces it, so playback starts
 * well before the sentence is finished and an interruption can stop synthesis
 * mid-word rather than after it.
 */
class SherpaSynthesizer(
    private val config: TtsConfig,
    private val modelDir: String,
) : SpeechSynthesizer {

    private var tts: OfflineTts? = null
    private var currentVoice: Int = config.speakerId

    override var sampleRate: Int = 24000
        private set

    override var voices: List<Voice> = emptyList()
        private set

    override fun initialize(): Result<Unit> = runCatching {
        val dir = File(modelDir)
        require(dir.isDirectory) { "TTS model directory not found: ${dir.absolutePath}" }

        val engine = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = if (config.backend == TtsBackend.KOKORO) kokoro(dir) else OfflineTtsKokoroModelConfig(),
                    vits = if (config.backend == TtsBackend.VITS) vits(dir) else OfflineTtsVitsModelConfig(),
                    matcha = if (config.backend == TtsBackend.MATCHA) matcha(dir) else OfflineTtsMatchaModelConfig(),
                    kitten = if (config.backend == TtsBackend.KITTEN) kitten(dir) else OfflineTtsKittenModelConfig(),
                    pocket = if (config.backend == TtsBackend.POCKET) pocket(dir) else OfflineTtsPocketModelConfig(),
                    numThreads = config.numThreads,
                    provider = config.provider,
                ),
            ),
        )

        tts = engine
        sampleRate = engine.sampleRate()
        voices = (0 until engine.numSpeakers()).map { Voice(it, "voice_$it") }
        currentVoice = config.speakerId.coerceIn(0, maxOf(0, engine.numSpeakers() - 1))
        Log.i(TAG, "${config.backend} ready: ${engine.numSpeakers()} voices at ${sampleRate}Hz")

        if (config.warmUp) warmUp(engine)
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    override fun synthesize(text: String, keepGoing: () -> Boolean, onChunk: (FloatArray) -> Unit) {
        val engine = tts ?: return
        if (text.isBlank()) return

        // Must be an explicit Function1 object, NOT a lambda. sherpa's JNI looks the
        // callback up by exact signature `invoke([F)Ljava/lang/Integer;`, and Kotlin
        // 2.x compiles lambdas through invokedynamic, which only produces the erased
        // `invoke(Object)Object`. The lookup then fails inside a JNI frame and aborts
        // the process rather than throwing.
        val callback = object : Function1<FloatArray, Int> {
            override fun invoke(samples: FloatArray): Int {
                if (!keepGoing()) return 0 // stops synthesis inside the native loop
                // The native buffer is reused between callbacks; playback needs its own copy.
                onChunk(samples.copyOf())
                return 1
            }
        }

        val started = System.currentTimeMillis()
        try {
            val audio = engine.generateWithCallback(
                text = text,
                sid = currentVoice,
                speed = config.speed,
                callback = callback,
            )
            // Real-time factor: synthesis time over audio produced. Above 1.0 the
            // synthesiser cannot keep up with playback and speech will stutter.
            val elapsed = System.currentTimeMillis() - started
            val audioMs = audio.samples.size * 1000L / maxOf(1, sampleRate)
            Log.i(
                TAG,
                "synth ${elapsed}ms for ${audioMs}ms audio (RTF ${"%.2f".format(elapsed / maxOf(1.0, audioMs.toDouble()))}) " +
                    "\"${text.take(40)}\"",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "synthesis failed for \"${text.take(40)}\"", e)
        }
    }

    /**
     * Runs one throwaway synthesis at load time.
     *
     * The first call to any ONNX graph pays allocation and kernel selection, and
     * the first Kokoro call additionally loads the espeak-ng dictionary. Paying
     * that here — while the user is still looking at a loading screen — keeps it
     * out of the first spoken reply, where it reads as a second of dead air.
     */
    private fun warmUp(engine: OfflineTts) {
        val started = System.currentTimeMillis()
        runCatching {
            engine.generateWithCallback(
                text = "ok",
                sid = currentVoice,
                speed = config.speed,
                callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int = 0 // discard, stop immediately
                },
            )
        }.onFailure { Log.w(TAG, "warm-up failed", it) }
        Log.i(TAG, "warm-up took ${System.currentTimeMillis() - started}ms")
    }

    override fun selectVoice(voiceId: Int) {
        val max = maxOf(0, (tts?.numSpeakers() ?: 1) - 1)
        currentVoice = voiceId.coerceIn(0, max)
    }

    override fun release() {
        tts?.release()
        tts = null
    }

    // ── Per-family file layouts ─────────────────────────────────────────

    private fun kokoro(dir: File) = OfflineTtsKokoroModelConfig(
        model = pickModel(dir),
        voices = required(dir, "voices.bin"),
        tokens = required(dir, "tokens.txt"),
        dataDir = required(dir, "espeak-ng-data"),
        // Multilingual bundles add lexicon files; pick lexicon-us-en.txt or first matching lexicon
        lexicon = lexiconKokoro(dir),
        dictDir = optionalDir(dir, "dict"),
    )

    private fun vits(dir: File) = OfflineTtsVitsModelConfig(
        model = pickModel(dir),
        lexicon = lexicons(dir),
        tokens = required(dir, "tokens.txt"),
        dataDir = optionalDir(dir, "espeak-ng-data"),
        dictDir = optionalDir(dir, "dict"),
        noiseScale = config.noiseScale,
        noiseScaleW = config.noiseScaleW,
        lengthScale = 1f / config.speed,
    )

    private fun matcha(dir: File) = OfflineTtsMatchaModelConfig(
        acousticModel = pickMatching(dir, "am") ?: pickModel(dir),
        vocoder = pickMatching(dir, "vocos") ?: pickMatching(dir, "hifigan")
            ?: error("Matcha needs a vocoder .onnx in ${dir.absolutePath}"),
        lexicon = lexicons(dir),
        tokens = required(dir, "tokens.txt"),
        dataDir = optionalDir(dir, "espeak-ng-data"),
        dictDir = optionalDir(dir, "dict"),
        noiseScale = config.noiseScale,
        lengthScale = 1f / config.speed,
    )

    private fun kitten(dir: File) = OfflineTtsKittenModelConfig(
        model = pickModel(dir),
        voices = required(dir, "voices.bin"),
        tokens = required(dir, "tokens.txt"),
        dataDir = required(dir, "espeak-ng-data"),
        lengthScale = 1f / config.speed,
    )

    /**
     * Pocket ships as separate ONNX parts plus JSON vocabularies, so there is no
     * single "model" file to discover — each part is matched by name.
     */
    private fun pocket(dir: File) = OfflineTtsPocketModelConfig(
        lmFlow = requireModel(dir, "lm_flow", "lm-flow", "flow"),
        lmMain = requireModel(dir, "lm_main", "lm-main", "main"),
        encoder = requireModel(dir, "encoder"),
        decoder = requireModel(dir, "decoder"),
        textConditioner = requireModel(dir, "text_conditioner", "text-conditioner", "conditioner"),
        vocabJson = required(dir, "vocab.json"),
        tokenScoresJson = required(dir, "token_scores.json"),
    )

    // ── File discovery ──────────────────────────────────────────────────

    private fun required(dir: File, name: String): String {
        val f = File(dir, name)
        if (f.exists()) return f.absolutePath
        val match = dir.walkTopDown().firstOrNull { it.isFile && it.name == name }
        requireNotNull(match) { "$name missing in ${dir.absolutePath}" }
        return match.absolutePath
    }

    private fun requireModel(dir: File, vararg needles: String): String =
        needles.firstNotNullOfOrNull { pickMatching(dir, it) }
            ?: error("no ${needles.first()} .onnx in ${dir.absolutePath}")

    private fun optionalDir(dir: File, name: String): String {
        val f = File(dir, name)
        if (f.isDirectory) return f.absolutePath
        return dir.walkTopDown().firstOrNull { it.isDirectory && it.name == name }?.absolutePath ?: ""
    }

    private fun lexicons(dir: File): String {
        val found = dir.walkTopDown().filter { f -> f.isFile && f.name.startsWith("lexicon") }.toList()
        return found.joinToString(",") { it.absolutePath }
    }

    private fun lexiconKokoro(dir: File): String {
        val us = dir.walkTopDown().firstOrNull { it.isFile && it.name == "lexicon-us-en.txt" }
        if (us != null) return us.absolutePath
        val found = dir.walkTopDown().filter { f -> f.isFile && f.name.startsWith("lexicon") }.toList()
        return found.joinToString(",") { it.absolutePath }
    }

    private fun pickMatching(dir: File, needle: String): String? =
        dir.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".onnx") && f.name.contains(needle, true) }
            .minByOrNull { it.name }?.absolutePath

    private fun pickModel(dir: File): String {
        val onnx = dir.walkTopDown().filter { f ->
            f.isFile && f.name.endsWith(".onnx") &&
                !f.name.contains("vocos", true) && !f.name.contains("hifigan", true)
        }.sortedBy { it.name }.toList()
        require(onnx.isNotEmpty()) { "no .onnx model in ${dir.absolutePath}" }

        val quantised = onnx.filter { it.name.contains("int8") }
        return when {
            config.preferInt8 && quantised.isNotEmpty() -> quantised.first()
            else -> (onnx.firstOrNull { !it.name.contains("int8") } ?: onnx.first())
        }.absolutePath
    }

    private companion object {
        const val TAG = "S2S-Tts"
    }
}
