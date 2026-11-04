package com.voiceprompter.match

/**
 * Splits a script into the chunks the prompter speaks and listens for.
 *
 * Chunk size is the main lever on how the app feels. Too long and the speaker
 * has to get a whole paragraph right before it advances; too short and the TTS
 * stutters through fragments. Sentence-sized, capped at [maxWords], is a
 * reasonable default.
 */
object ScriptSegmenter {

    data class Phrase(
        val index: Int,
        val text: String,
        val tokens: List<String>,
        /** Character offset in the original script, for highlighting. */
        val start: Int,
        val end: Int,
    )

    private val HARD_BREAKS = charArrayOf('.', '!', '?', '\n')
    private val SOFT_BREAKS = charArrayOf(';', ':', ',')

    fun segment(script: String, maxWords: Int = 14): List<Phrase> {
        val sentences = splitOn(script, HARD_BREAKS)

        val phrases = mutableListOf<Phrase>()
        for (sentence in sentences) {
            val wordCount = sentence.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            if (wordCount <= maxWords) {
                phrases += sentence
            } else {
                // Too long to speak in one breath — break at commas, and if
                // that still is not enough, hard-split on word count.
                val parts = splitOn(sentence.text, SOFT_BREAKS, offset = sentence.start)
                for (part in parts) {
                    phrases += splitByWordCount(part, maxWords)
                }
            }
        }

        return phrases
            .filter { it.text.isNotBlank() }
            .mapIndexed { i, p ->
                val text = p.text.trim()
                p.copy(index = i, text = text, tokens = Normalizer.tokenize(text))
            }
    }

    private fun splitOn(text: String, delimiters: CharArray, offset: Int = 0): List<Phrase> {
        val out = mutableListOf<Phrase>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in delimiters) {
                val slice = text.substring(start, i + 1)
                if (slice.isNotBlank()) {
                    out += Phrase(0, slice, emptyList(), offset + start, offset + i + 1)
                }
                start = i + 1
            }
        }
        if (start < text.length) {
            val slice = text.substring(start)
            if (slice.isNotBlank()) {
                out += Phrase(0, slice, emptyList(), offset + start, offset + text.length)
            }
        }
        return out
    }

    private fun splitByWordCount(phrase: Phrase, maxWords: Int): List<Phrase> {
        val words = phrase.text.split(" ").filter { it.isNotBlank() }
        if (words.size <= maxWords) return listOf(phrase)

        val out = mutableListOf<Phrase>()
        var cursor = phrase.start
        var i = 0
        while (i < words.size) {
            val chunk = words.subList(i, minOf(i + maxWords, words.size)).joinToString(" ")
            out += Phrase(0, chunk, emptyList(), cursor, cursor + chunk.length)
            cursor += chunk.length + 1
            i += maxWords
        }
        return out
    }
}
