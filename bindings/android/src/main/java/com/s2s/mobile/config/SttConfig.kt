package com.s2s.mobile.config

/** Streaming model families the sherpa-onnx online recogniser can host. */
enum class SttBackend {
    /** Streaming Zipformer transducer. Best accuracy-per-millisecond on ARM. */
    ZIPFORMER_TRANSDUCER,

    /** Streaming Zipformer2 CTC. Smaller, slightly worse on rare words. */
    ZIPFORMER2_CTC,

    /** Streaming Paraformer. Strong on Chinese. */
    PARAFORMER,

    /** Streaming NeMo CTC. */
    NEMO_CTC,
}

/**
 * Speech recognition and endpointing.
 *
 * The endpoint rules decide when a turn is over, so they set the floor on
 * response latency: trailing silence is time the user waits after finishing.
 */
data class SttConfig(
    val backend: SttBackend = SttBackend.ZIPFORMER_TRANSDUCER,
    val numThreads: Int = 2,
    /** Trailing silence that ends a turn once speech was heard, in seconds. */
    val endpointTrailingSilence: Float = 0.8f,
    /** Trailing silence that ends a turn when nothing was said, in seconds. */
    val endpointSilenceOnly: Float = 2.0f,
    /** Hard cap on one turn regardless of silence, in seconds. */
    val endpointMaxUtterance: Float = 25f,
    /** Emit partial transcripts while the user is still talking. */
    val emitPartials: Boolean = true,
    /** Prefer int8 weights where the bundle ships both. Roughly 2x faster on ARM. */
    val preferInt8: Boolean = true,
    /**
     * `modified_beam_search` or `greedy_search`. Beam search keeps several
     * hypotheses alive rather than committing to the best token at each step,
     * which materially helps on short utterances; decoding runs far ahead of
     * real time either way.
     */
    val decodingMethod: String = "modified_beam_search",
    /** Hypotheses kept by beam search. Higher is more accurate and slower. */
    val maxActivePaths: Int = 4,
    /** Bias the recogniser toward these phrases, one per line. Empty to disable. */
    val hotwords: List<String> = emptyList(),
    val hotwordsScore: Float = 1.5f,
)
