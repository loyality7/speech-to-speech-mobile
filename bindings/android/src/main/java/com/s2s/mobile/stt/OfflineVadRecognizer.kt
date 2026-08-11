package com.s2s.mobile.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.SttBackend
import com.s2s.mobile.config.SttConfig
import com.s2s.mobile.config.VadConfig
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.Transcript
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline recognition over VAD-segmented speech, for models that cannot stream:
 * Moonshine, Parakeet-TDT and Whisper.
 *
 * These are more accurate than the streaming recognisers, and the trade is
 * latency — nothing can be decoded until the user stops talking, so the whole
 * decode sits in the response path rather than running underneath the
 * conversation. On a mid-range phone that is roughly 200–500 ms for Moonshine
 * and more for Parakeet. No partial transcripts are possible.
 *
 * Owns its own VAD instance: the engine's barge-in detector is driven on a
 * different schedule and sharing one would interleave two sets of segments.
 */
class OfflineVadRecognizer(
    private val sttConfig: SttConfig,
    private val vadConfig: VadConfig,
    private val audioConfig: AudioConfig,
    private val modelDir: String,
    private val vadModelPath: String,
) : SpeechRecognizer {

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    /** Serial so utterances are transcribed in the order they were spoken. */
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "S2S-Decode") }
    private val decoded = ConcurrentLinkedQueue<String>()
    private val decodeGeneration = AtomicInteger(0)

    override fun initialize(): Result<Unit> = runCatching {
        val dir = File(modelDir)
        require(dir.isDirectory) { "STT model directory not found: ${dir.absolutePath}" }

        val vadModel = File(vadModelPath)
        require(vadModel.isFile) { "Silero VAD model not found: ${vadModel.absolutePath}" }

        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.absolutePath,
                    threshold = vadConfig.threshold,
                    minSilenceDuration = vadConfig.minSilenceSeconds,
                    minSpeechDuration = vadConfig.minSpeechSeconds,
                    windowSize = audioConfig.frameSize,
                    maxSpeechDuration = vadConfig.maxSpeechSeconds,
                ),
                sampleRate = audioConfig.sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )

        val tokens = File(dir, "tokens.txt")
        require(tokens.isFile) { "tokens.txt missing in ${dir.absolutePath}" }

        val model = when (sttConfig.backend) {
            SttBackend.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = pick(dir, "preprocess"),
                    encoder = pick(dir, "encode"),
                    uncachedDecoder = pick(dir, "uncached_decode"),
                    cachedDecoder = pick(dir, "cached_decode"),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = "cpu",
            )

            SttBackend.PARAKEET_TDT -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = pick(dir, "encoder"),
                    decoder = pick(dir, "decoder"),
                    joiner = pick(dir, "joiner"),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = "cpu",
                // Parakeet is a NeMo transducer; the generic transducer loader
                // reads different metadata and fails on it.
                modelType = "nemo_transducer",
            )

            SttBackend.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = pick(dir, "encoder"),
                    decoder = pick(dir, "decoder"),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = "cpu",
            )

            else -> error("${sttConfig.backend} is a streaming backend; use SherpaStreamingRecognizer")
        }

        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = audioConfig.sampleRate, featureDim = 80),
                modelConfig = model,
                decodingMethod = "greedy_search",
            ),
        )
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    /**
     * Never blocks. Segmentation is cheap and stays here; decoding is handed to a
     * worker and the result is picked up by a later frame.
     *
     * Decoding inline would stall audio capture for the length of the decode
     * (160–650 ms measured), which drops frames and leaves the capture thread
     * un-joinable during shutdown.
     */
    override fun accept(frame: FloatArray): Transcript {
        val detector = vad ?: return Transcript.Nothing

        detector.acceptWaveform(frame)
        while (!detector.empty()) {
            // A segment only appears once the VAD has seen the trailing silence,
            // so each one is already a complete utterance.
            val segment = detector.front()
            detector.pop()
            val generation = decodeGeneration.get()
            decoder.execute { decode(segment.samples, generation) }
        }

        // At most one frame (32 ms) later than an inline decode would have been.
        return decoded.poll()?.let { Transcript.Final(it) } ?: Transcript.Nothing
    }

    private fun decode(samples: FloatArray, generation: Int) {
        val rec = recognizer ?: return
        // Dropped if the turn was reset while this sat in the queue.
        if (generation != decodeGeneration.get()) return

        val started = System.currentTimeMillis()
        val stream = rec.createStream()
        val text = try {
            stream.acceptWaveform(samples, audioConfig.sampleRate)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "decode failed", e)
            ""
        } finally {
            stream.release()
        }

        val audioMs = samples.size * 1000L / audioConfig.sampleRate
        Log.i(TAG, "decoded ${audioMs}ms of speech in ${System.currentTimeMillis() - started}ms")

        if (text.isNotEmpty() && generation == decodeGeneration.get()) decoded.offer(text)
    }

    override fun reset() {
        // Invalidates queued and in-flight decodes so a cancelled utterance cannot
        // surface as a transcript after the fact.
        decodeGeneration.incrementAndGet()
        decoded.clear()
        vad?.reset()
        vad?.clear()
    }

    /**
     * Shuts the decoder down before freeing native handles — a decode in flight
     * holds the recogniser, and releasing underneath it would be a use-after-free.
     */
    override fun release() {
        decodeGeneration.incrementAndGet()
        decoder.shutdown()
        if (!decoder.awaitTermination(5, TimeUnit.SECONDS)) {
            Log.w(TAG, "decoder did not finish; leaking native handles rather than freeing them in use")
            return
        }
        decoded.clear()
        vad?.release(); vad = null
        recognizer?.release(); recognizer = null
    }

    /** Matches a model part by name; bundles differ in prefixes across releases. */
    private fun pick(dir: File, role: String): String {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".onnx") && f.name.contains(role, ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
        require(candidates.isNotEmpty()) { "no $role .onnx in ${dir.absolutePath}" }

        val quantised = candidates.filter { it.name.contains("int8") }
        return when {
            sttConfig.preferInt8 && quantised.isNotEmpty() -> quantised.first()
            else -> (candidates.firstOrNull { !it.name.contains("int8") } ?: candidates.first())
        }.absolutePath
    }

    private companion object {
        const val TAG = "S2S-Stt"
    }
}
