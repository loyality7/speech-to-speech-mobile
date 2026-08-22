package com.s2s.mobile.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.SttBackend
import com.s2s.mobile.config.SttConfig
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.Transcript
import java.io.File

/**
 * Streaming speech recognition on sherpa-onnx, with the model family selected by
 * [SttConfig.backend].
 *
 * Audio is decoded while the user is still talking, so when the endpointer fires
 * the transcript is already there. That removes the whole transcription wait
 * from the response path — the single biggest latency win over transcribing a
 * buffered utterance after the fact.
 */
class SherpaStreamingRecognizer(
    private val sttConfig: SttConfig,
    private val audioConfig: AudioConfig,
    private val modelDir: String,
) : SpeechRecognizer {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var hotwordsFile: File? = null

    override fun initialize(): Result<Unit> = runCatching {
        val dir = File(modelDir)
        require(dir.isDirectory) { "STT model directory not found: ${dir.absolutePath}" }

        val tokens = File(dir, "tokens.txt")
        require(tokens.isFile) { "tokens.txt missing in ${dir.absolutePath}" }

        val model = when (sttConfig.backend) {
            SttBackend.ZIPFORMER_TRANSDUCER -> OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = pick(dir, "encoder", int8 = sttConfig.preferInt8),
                    // The decoder is tiny; quantising it buys nothing measurable.
                    decoder = pick(dir, "decoder", int8 = false),
                    joiner = pick(dir, "joiner", int8 = sttConfig.preferInt8),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = sttConfig.provider,
                // Left empty on purpose: sherpa reads the family from the encoder
                // metadata. Asserting "zipformer2" makes it use a loader that
                // expects metadata the original zipformer models do not carry,
                // and it aborts the process natively rather than throwing.
                modelType = "",
            )

            SttBackend.ZIPFORMER2_CTC -> OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                    model = pick(dir, "ctc", int8 = sttConfig.preferInt8),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = sttConfig.provider,
            )

            SttBackend.PARAFORMER -> OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = pick(dir, "encoder", int8 = sttConfig.preferInt8),
                    decoder = pick(dir, "decoder", int8 = sttConfig.preferInt8),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = sttConfig.provider,
            )

            SttBackend.NEMO_CTC -> OnlineModelConfig(
                neMoCtc = OnlineNeMoCtcModelConfig(
                    model = pick(dir, "model", int8 = sttConfig.preferInt8),
                ),
                tokens = tokens.absolutePath,
                numThreads = sttConfig.numThreads,
                provider = sttConfig.provider,
            )

            else -> error("${sttConfig.backend} is an offline backend; use OfflineVadRecognizer")
        }

        // Hotwords need modified_beam_search; greedy search ignores them.
        val useHotwords = sttConfig.hotwords.isNotEmpty()
        if (useHotwords) {
            hotwordsFile = File(dir, "s2s-hotwords.txt").apply {
                writeText(sttConfig.hotwords.joinToString("\n"))
            }
        }

        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = audioConfig.sampleRate, featureDim = 80),
                modelConfig = model,
                endpointConfig = EndpointConfig(
                    // Nothing said yet — wait longer before abandoning the turn.
                    rule1 = EndpointRule(false, sttConfig.endpointSilenceOnly, 0f),
                    // Speech, then silence: the normal end of a turn.
                    rule2 = EndpointRule(true, sttConfig.endpointTrailingSilence, 0f),
                    // Someone is monologuing; cut in so the assistant can answer.
                    rule3 = EndpointRule(false, 0f, sttConfig.endpointMaxUtterance),
                ),
                enableEndpoint = true,
                // Beam search keeps several hypotheses alive instead of committing
                // to the best token at each step, which is what stops short words
                // like "hello" collapsing into fragments. Costs a few ms per chunk
                // and decoding already runs ahead of real time.
                decodingMethod = sttConfig.decodingMethod,
                maxActivePaths = sttConfig.maxActivePaths,
                hotwordsFile = hotwordsFile?.absolutePath ?: "",
                hotwordsScore = sttConfig.hotwordsScore,
            ),
        )
        stream = recognizer?.createStream()
        Unit
    }.onFailure { Log.e(TAG, "initialize failed", it) }

    override fun accept(frame: FloatArray): Transcript {
        val rec = recognizer ?: return Transcript.Nothing
        val st = stream ?: return Transcript.Nothing

        st.acceptWaveform(frame, audioConfig.sampleRate)
        while (rec.isReady(st)) rec.decode(st)

        val text = rec.getResult(st).text.trim()
        if (rec.isEndpoint(st)) {
            rec.reset(st)
            // An endpoint with no text is silence timing out, not a turn.
            return if (text.isEmpty()) Transcript.Nothing else Transcript.Final(text)
        }
        if (!sttConfig.emitPartials || text.isEmpty()) return Transcript.Nothing
        return Transcript.Partial(text)
    }

    override fun reset() {
        val rec = recognizer ?: return
        stream?.release()
        stream = rec.createStream()
    }

    override fun release() {
        stream?.release(); stream = null
        recognizer?.release(); recognizer = null
        hotwordsFile?.delete(); hotwordsFile = null
    }

    /**
     * sherpa bundles do not agree on filenames across releases, so match on role
     * rather than hardcoding. int8 encoders and joiners are roughly twice as fast
     * on ARM with no accuracy cost worth measuring.
     */
    private fun pick(dir: File, role: String, int8: Boolean): String {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".onnx") && f.name.contains(role, ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
        require(candidates.isNotEmpty()) { "no $role .onnx in ${dir.absolutePath}" }

        val quantised = candidates.filter { it.name.contains("int8") }
        val plain = candidates.filterNot { it.name.contains("int8") }
        val chosen = when {
            int8 && quantised.isNotEmpty() -> quantised.first()
            plain.isNotEmpty() -> plain.first()
            else -> candidates.first()
        }
        return chosen.absolutePath
    }

    private companion object {
        const val TAG = "S2S-Stt"
    }
}
