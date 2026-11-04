package com.voiceprompter.match

/**
 * How closely the speaker has to track the script before the prompter advances.
 *
 * The thresholds below are starting points chosen by hand, not tuned against
 * recordings. Expect to adjust them once you have run real sermons through the
 * app — that is the only way to find the right values for your voice, your
 * microphone and your room.
 */
enum class MatchMode(val threshold: Double) {
    /** Near word-perfect. Good for memorisation drills and scripted video. */
    STRICT(0.90),

    /** Default. Tolerates a wrong word or two and a few skips. */
    BALANCED(0.72),

    /** Advances on the gist. Good for preaching from notes. */
    RELAXED(0.55),
    ;

    companion object {
        val DEFAULT = BALANCED
    }
}
