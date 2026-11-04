package com.voiceprompter.prompter

import com.voiceprompter.match.MatchMode
import com.voiceprompter.match.ScriptSegmenter

/** What the prompter is doing right now. */
enum class PrompterStatus {
    /** Nothing loaded, or stopped. */
    IDLE,

    /** Downloading or unpacking the speech model. */
    PREPARING,

    /** TTS is reading the current phrase. Microphone is off. */
    SPEAKING,

    /** Waiting for the speaker to repeat the current phrase. */
    LISTENING,

    /**
     * The speaker is talking, but not saying the script. The position is held
     * and the app keeps listening for a return to the script.
     */
    AD_LIB,

    /** Paused by the user. */
    PAUSED,

    /** Reached the end of the script. */
    FINISHED,

    ERROR,
}

data class PrompterState(
    val status: PrompterStatus = PrompterStatus.IDLE,
    val phrases: List<ScriptSegmenter.Phrase> = emptyList(),
    val currentIndex: Int = 0,
    /** 0..1 through the current phrase, driven by partial recognition. */
    val phraseProgress: Float = 0f,
    /** Last thing the recogniser heard, shown for debugging and confidence. */
    val lastHeard: String = "",
    val lastScore: Double = 0.0,
    val matchMode: MatchMode = MatchMode.DEFAULT,
    /**
     * When true the hardware volume keys scroll the script instead of
     * changing the volume, and keep doing so with the screen off. Turning it
     * off hands the keys back to the volume stream.
     */
    val volumeKeysScroll: Boolean = true,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val modelProgress: Float = 0f,
    val error: String? = null,
) {
    val currentPhrase: ScriptSegmenter.Phrase?
        get() = phrases.getOrNull(currentIndex)

    val isRunning: Boolean
        get() = status == PrompterStatus.SPEAKING ||
            status == PrompterStatus.LISTENING ||
            status == PrompterStatus.AD_LIB
}
