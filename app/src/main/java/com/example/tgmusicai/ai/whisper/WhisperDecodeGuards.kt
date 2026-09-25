package com.example.tgmusicai.ai.whisper

/**
 * The heuristics that stop a greedy Whisper decode from running away, kept separate from the engine
 * so they can be unit-tested without a model, a Context or a device.
 *
 * All three exist because of the same failure. Whisper is a speech model being asked about singing
 * over instrumentation, and when it has nothing confident to say it does not fall silent -- it
 * repeats. Left alone, greedy decoding spends its whole token budget on one phrase, every chunk
 * produces that phrase, and the result is a lyrics pane holding the same line hundreds of times
 * while the decode takes the maximum possible time to produce it.
 */
object WhisperDecodeGuards {
    /** Longest repeating phrase [endsInRepeatedCycle] looks for, in tokens. */
    const val MAX_CYCLE_LEN = 12

    /** How many times a phrase must repeat back-to-back before the decode is called stuck. */
    const val MAX_CYCLE_REPEATS = 3

    /** RMS below which a window is treated as silence and never sent to the model. */
    const val SILENCE_RMS_THRESHOLD = 0.005

    /**
     * True when the tail of [tokens] is one short cycle repeated [MAX_CYCLE_REPEATS] times over.
     *
     * Cycle lengths from a single token up to [MAX_CYCLE_LEN] are all checked, because a stuck
     * decode repeats a *phrase* rather than a token -- "I don't know, I don't know, I don't know".
     * Three repetitions is the threshold: a song really can sing a short phrase twice, but a third
     * identical repetition immediately following it is the model, not the singer.
     */
    fun endsInRepeatedCycle(tokens: List<Int>): Boolean {
        for (cycle in 1..MAX_CYCLE_LEN) {
            val needed = cycle * MAX_CYCLE_REPEATS
            if (tokens.size < needed) break
            val tail = tokens.subList(tokens.size - needed, tokens.size)
            var repeats = true
            for (i in cycle until needed) {
                if (tail[i] != tail[i % cycle]) {
                    repeats = false
                    break
                }
            }
            if (repeats) return true
        }
        return false
    }

    /**
     * True when [chunk] carries no more energy than a quiet room, so there is nothing to transcribe.
     *
     * RMS rather than peak: one stray sample should not make a silent half-minute look like singing.
     * The threshold is deliberately low -- this is meant to skip true silence and tape hiss, not
     * quiet passages, since a wrongly skipped window loses real lyrics outright.
     */
    fun isEffectivelySilent(chunk: FloatArray): Boolean {
        if (chunk.isEmpty()) return true
        var sumSquares = 0.0
        for (sample in chunk) sumSquares += sample.toDouble() * sample.toDouble()
        return Math.sqrt(sumSquares / chunk.size) < SILENCE_RMS_THRESHOLD
    }

    /**
     * Collapses a phrase repeated back-to-back down to a single occurrence.
     *
     * The decoder guard stops a stuck decode, but a chunk can still come back having said the same
     * sentence two or three times before the guard fired. Splitting on sentence-ish punctuation and
     * dropping runs of identical neighbours turns "Oh no. Oh no. Oh no." into "Oh no." while leaving
     * a genuine repeated hook -- one separated by other words -- untouched.
     */
    fun collapseRepeatedPhrases(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val parts = trimmed.split(Regex("(?<=[.!?,])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return trimmed
        val kept = mutableListOf<String>()
        for (part in parts) {
            if (kept.isEmpty() || !kept.last().equals(part, ignoreCase = true)) kept.add(part)
        }
        return kept.joinToString(" ")
    }
}
