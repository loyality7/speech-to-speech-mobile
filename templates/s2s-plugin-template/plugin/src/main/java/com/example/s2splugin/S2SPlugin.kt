package com.example.s2splugin

/**
 * Identity and metadata for one S2S plugin — NOT a capability interface.
 *
 * This exists so a host can log/inspect what it has installed ("s2s-llm-openai
 * v0.3.1 provides LanguageModel") without the plugin needing to also implement
 * generate()/executeTool()/etc through some giant catch-all interface. A plugin
 * implements [S2SPlugin] for identity, plus whichever core contract
 * (`LanguageModel`, `ContextEngine`, `Tools`, ...) it actually provides —
 * those stay separate, unrelated interfaces from `com.s2s.mobile.pipeline`.
 *
 * Installing this class on the classpath (a Gradle dependency) does NOT make
 * it active. The host reads [id]/[version]/[capabilities] to decide whether
 * to construct and inject it — activation is host-driven, not automatic. See
 * the template README's "Installation vs activation" section.
 */
interface S2SPlugin {
    /** Stable, human-readable identity, e.g. "s2s-llm-openai". Does not change across versions. */
    val id: String

    /** Semver string. Used for compatibility logging, not enforced by core. */
    val version: String

    /**
     * Which core contracts this plugin can provide, e.g. `listOf("LanguageModel")`.
     * A plugin implementing more than one capability (rare — most plugins should
     * provide exactly one) lists all of them.
     */
    val capabilities: List<String>
}
