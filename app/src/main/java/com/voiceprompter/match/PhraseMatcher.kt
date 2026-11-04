package com.voiceprompter.match

import kotlin.math.max
import kotlin.math.min

/**
 * Scores how well a stretch of recognised speech matches an expected phrase.
 *
 * The score blends two measures, because each alone fails in a way the other
 * catches:
 *
 *  - **Coverage** (longest common subsequence / expected length) asks "how much
 *    of the script did they actually say, in order?". It is forgiving of extra
 *    words the speaker inserts, which is what you want when someone adds "and
 *    so" mid-sentence. On its own it is too forgiving: a speaker who says the
 *    whole phrase buried inside a long ad-lib still scores 1.0.
 *
 *  - **Edit similarity** (token-level Levenshtein) asks "how different are the
 *    two token sequences overall?". It penalises the extra words coverage
 *    ignores. On its own it is too harsh on a speaker who skips a filler clause.
 *
 * Weighted together they behave sensibly on both.
 */
object PhraseMatcher {

    private const val COVERAGE_WEIGHT = 0.6
    private const val EDIT_WEIGHT = 0.4

    data class Result(
        val score: Double,
        val coverage: Double,
        val editSimilarity: Double,
        val expectedTokens: Int,
        val heardTokens: Int,
    ) {
        fun advances(mode: MatchMode): Boolean = score >= mode.threshold
    }

    /** Convenience overload that normalises both sides first. */
    fun score(expected: String, heard: String): Result =
        score(Normalizer.tokenize(expected), Normalizer.tokenize(heard))

    fun score(expected: List<String>, heard: List<String>): Result {
        if (expected.isEmpty()) {
            return Result(
                score = if (heard.isEmpty()) 1.0 else 0.0,
                coverage = 1.0,
                editSimilarity = if (heard.isEmpty()) 1.0 else 0.0,
                expectedTokens = 0,
                heardTokens = heard.size,
            )
        }
        if (heard.isEmpty()) {
            return Result(0.0, 0.0, 0.0, expected.size, 0)
        }

        val lcs = longestCommonSubsequence(expected, heard)
        val coverage = lcs.toDouble() / expected.size

        val distance = levenshtein(expected, heard)
        val span = max(expected.size, heard.size)
        val editSimilarity = 1.0 - (distance.toDouble() / span)

        val blended = (COVERAGE_WEIGHT * coverage) + (EDIT_WEIGHT * editSimilarity)

        return Result(
            score = blended.coerceIn(0.0, 1.0),
            coverage = coverage,
            editSimilarity = editSimilarity.coerceIn(0.0, 1.0),
            expectedTokens = expected.size,
            heardTokens = heard.size,
        )
    }

    /**
     * How far into [expected] the speaker appears to have got. Used to drive a
     * progress highlight from partial recogniser results — it should not be
     * used to advance the script, because partial results change as the
     * recogniser revises its hypothesis.
     */
    fun progress(expected: List<String>, heardSoFar: List<String>): Double {
        if (expected.isEmpty()) return 1.0
        if (heardSoFar.isEmpty()) return 0.0
        return longestCommonSubsequence(expected, heardSoFar).toDouble() / expected.size
    }

    /**
     * Finds which phrase the speaker is on, searching a window around the
     * current position. This is the re-synchronisation primitive: after an
     * ad-lib, feed in what was just heard and it reports the best candidate.
     *
     * @param window how many phrases either side of [currentIndex] to consider
     * @return the best match, or null if nothing in the window clears [mode]
     */
    fun resync(
        phrases: List<List<String>>,
        heard: List<String>,
        currentIndex: Int,
        mode: MatchMode,
        window: Int = 6,
    ): Resync? {
        if (phrases.isEmpty() || heard.isEmpty()) return null

        val from = max(0, currentIndex - window)
        val to = min(phrases.lastIndex, currentIndex + window)

        var bestIndex = -1
        var best: Result? = null

        for (i in from..to) {
            val result = score(phrases[i], heard)
            if (best == null || result.score > best.score) {
                best = result
                bestIndex = i
            }
        }

        val winner = best ?: return null
        if (!winner.advances(mode)) return null
        return Resync(index = bestIndex, result = winner)
    }

    data class Resync(val index: Int, val result: Result)

    // --- algorithms -------------------------------------------------------

    private fun longestCommonSubsequence(a: List<String>, b: List<String>): Int {
        // Two-row DP; O(a*b) time, O(b) space.
        var previous = IntArray(b.size + 1)
        var current = IntArray(b.size + 1)

        for (i in 1..a.size) {
            for (j in 1..b.size) {
                current[j] = if (a[i - 1] == b[j - 1]) {
                    previous[j - 1] + 1
                } else {
                    max(previous[j], current[j - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }

        return previous[b.size]
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)

        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                val deletion = previous[j] + 1
                val insertion = current[j - 1] + 1
                current[j] = min(substitution, min(deletion, insertion))
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.size]
    }
}
