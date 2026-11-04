package com.voiceprompter.match

/**
 * Turns raw script text or raw ASR output into a comparable token list.
 *
 * Both sides of a comparison must go through the same normalisation, otherwise
 * punctuation and casing in the script will never match the recogniser's
 * lowercase, punctuation-free output.
 */
object Normalizer {

    /**
     * Disfluencies dropped before comparison. Vosk emits some of these
     * verbatim; others come from the speaker genuinely saying them.
     */
    private val FILLERS: Set<String> = setOf(
        "uh", "um", "uhm", "erm", "er", "ah", "eh", "mm", "mmm", "hmm", "huh",
    )

    /**
     * Single digits only. Recognisers are inconsistent about how they render
     * numbers ("2025" vs "twenty twenty five" vs "two thousand twenty five"),
     * and there is no normalisation that gets every case right. Scripts with
     * heavy numeric content will match less reliably — a known limitation.
     */
    private val DIGIT_WORDS: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four",
        '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine",
    )

    /**
     * @param dropFillers remove disfluencies such as "uh" and "um"
     * @param collapseRepeats collapse immediately repeated tokens, so a speaker
     *   stumbling over "the the point" still matches "the point"
     */
    fun tokenize(
        text: String,
        dropFillers: Boolean = true,
        collapseRepeats: Boolean = true,
    ): List<String> {
        val builder = StringBuilder(text.length)
        for (raw in text) {
            val ch = raw.lowercaseChar()
            when {
                ch.isLetter() -> builder.append(ch)

                // Keep apostrophes inside words ("don't"), drop them elsewhere.
                ch == '\'' || ch == '’' -> builder.append('\'')

                DIGIT_WORDS.containsKey(ch) -> {
                    builder.append(' ').append(DIGIT_WORDS[ch]).append(' ')
                }

                else -> builder.append(' ')
            }
        }

        var tokens = builder.toString()
            .split(' ')
            .asSequence()
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
            .toList()

        if (dropFillers) {
            tokens = tokens.filterNot { it in FILLERS }
        }

        if (collapseRepeats) {
            tokens = tokens.filterIndexed { i, token -> i == 0 || token != tokens[i - 1] }
        }

        return tokens
    }
}
