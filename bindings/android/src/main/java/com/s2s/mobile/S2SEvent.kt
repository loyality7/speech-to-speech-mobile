package com.s2s.mobile

/** Where the conversation currently is. */
enum class S2SState {
    /** Not running. */
    IDLE,

    /** Microphone open, waiting for the user. */
    LISTENING,

    /**
     * The user is mid-turn: at least one acoustic segment has been recognised
     * and the turn is still accumulating. No request has been dispatched and
     * none may be — see [com.s2s.mobile.internal.TurnAggregator] for why a
     * segment boundary is not a turn boundary.
     */
    USER_TURN_ACTIVE,

    /**
     * The user has paused and the adaptive endpoint window is running. More
     * speech returns to [USER_TURN_ACTIVE] and cancels the pending commit;
     * the window elapsing commits the turn and moves to [THINKING].
     */
    TURN_PENDING_CONFIRMATION,

    /** User finished; the model is producing tokens. */
    THINKING,

    /** Assistant audio is playing. The microphone stays open for barge-in. */
    SPEAKING,
}

/** Latency breakdown for one turn, measured from the end of the user's speech. */
data class TurnMetrics(
    val timeToFirstTokenMs: Long,
    val timeToFirstAudioMs: Long,
)

/** Everything the engine reports. Collect [S2SEngine.events] to drive a UI. */
sealed interface S2SEvent {

    /**
     * User speech. [isFinal] false is a live partial that may still change;
     * true means the turn is settled and generation has started.
     */
    data class UserTranscript(val text: String, val isFinal: Boolean) : S2SEvent

    /** One token of the assistant's reply. */
    data class AssistantDelta(val text: String) : S2SEvent

    /** The assistant finished the whole reply. */
    data class AssistantDone(val text: String) : S2SEvent

    data class StateChanged(val state: S2SState) : S2SEvent

    /** The user talked over the assistant and cut it off. */
    data object BargeIn : S2SEvent

    /**
     * Live voice-activity signal while [S2SState.LISTENING], for a UI that wants
     * to show a waveform or mic pulse rather than wait for a transcript.
     * Debounced the same way barge-in is, so a click or cough does not fire it.
     */
    data object SpeechStarted : S2SEvent

    /** The utterance that [SpeechStarted] announced has settled into a transcript. */
    data object SpeechEnded : S2SEvent

    /** Emitted once per turn, when the first audio chunk reaches the speaker. */
    data class Metrics(val metrics: TurnMetrics) : S2SEvent

    /** A tool the model asked for was executed. */
    data class ToolExecuted(val name: String, val output: String, val isError: Boolean) : S2SEvent

    /**
     * The engine yielded the microphone and speaker to something more important —
     * a call, an alarm, another assistant.
     *
     * [willResume] true means the interruption is transient and listening resumes
     * on its own; false means focus was lost for good and the engine has stopped.
     * Worth reflecting in the UI, or the app looks frozen while a call rings.
     */
    data class AudioFocusLost(val willResume: Boolean) : S2SEvent

    /** Listening resumed after a transient loss. */
    data object AudioFocusRegained : S2SEvent

    data class Error(val message: String, val cause: Throwable? = null) : S2SEvent
}
