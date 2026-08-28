package com.s2s.plugin.api;

/**
 * The IPC contract an s2s TEXT NORMALIZER plugin exposes.
 *
 * Separate interface from the tool-plugin one on purpose: a normalizer has
 * a different shape (one string in, one string out, latency-critical) and
 * merging them would force every tool plugin to carry normalizer methods
 * it does not implement.
 *
 * Deliberately synchronous. Normalization sits in the middle of a voice
 * turn — the caller is already blocked waiting for a transcript to hand
 * onward — so a callback interface would add machinery without removing
 * any waiting. The caller applies its own timeout and falls back to the
 * raw transcript.
 *
 * The plugin runs in its own process with its own inference runtime. That
 * is not just isolation for safety here: llama.cpp bindings are typically
 * process-global singletons, so a normalizer model and the host's primary
 * model genuinely cannot coexist in one process.
 */
interface IS2STextNormalizerPlugin {
    /** Host API version this plugin was built against. The host refuses to bind a plugin newer than itself. */
    int apiVersion();

    /** Whether the model is present and loadable right now. Cheap: must not load the model to answer. */
    boolean isModelAvailable();

    /**
     * Loads the model if needed and returns when ready. Blocking and slow
     * (hundreds of ms to seconds) — the host calls this off the voice path
     * so the first real utterance does not pay cold start.
     * Returns false if the model could not be made ready.
     */
    boolean warmUp();

    /**
     * Cleans one transcript.
     *
     * [styling], [structure] and [context] are the lowercase option values
     * the host was configured with; a plugin maps them onto whatever its
     * own model expects. Passing strings rather than an enum keeps this
     * interface stable when a new option value is added.
     *
     * Returns the cleaned text, or the input unchanged if it cannot do
     * better. MUST NOT return null or empty for non-empty input: losing a
     * user's turn is worse than an unpolished one.
     */
    String normalize(String rawTranscript, String styling, String structure, String context);

    /** Frees the model. The host calls this when the plugin is disabled or the runtime stops. */
    void releaseModel();
}
