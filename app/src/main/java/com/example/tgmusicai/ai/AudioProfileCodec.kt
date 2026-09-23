package com.example.tgmusicai.ai

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Packs a song's acoustic profile -- the mean-pooled YAMNet class-score vector produced by
 * [SongTaggingEngine] -- into a short string for the `ai_song_tags.audioProfile` column, and back.
 *
 * The vector is L2-normalized, quantized to signed 8-bit, and written as hex, which costs about
 * 1KB per song against roughly 5KB for the same numbers written as comma-separated floats. Across
 * a few thousand songs that is the difference between two megabytes and ten.
 *
 * Hex rather than Base64 because `android.util.Base64` returns stubbed values under the project's
 * `unitTests.isReturnDefaultValues = true`, which would make the codec untestable on the JVM, and
 * `java.util.Base64` needs API 26 against this module's `minSdk = 24`. A third of a kilobyte per
 * song is a fair price for a codec that is pure Kotlin and fully unit-testable.
 *
 * Normalizing before quantizing is what makes 8 bits safe here. Raw AudioSet mean scores bunch up
 * near zero, so quantizing them directly would flatten nearly every component to 0 and destroy the
 * ranking the profile exists to support. After normalization the components use the available
 * range, and cosine similarity does not care about the overall scale that normalization removes.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-testable on the JVM.
 */
object AudioProfileCodec {

    /** Full-scale value for the signed 8-bit quantization. -128 is left unused so the range is symmetric. */
    private const val QUANTIZATION_SCALE = 127f

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Encodes [scores] for storage, or returns null if there is nothing worth storing (an empty
     * vector, or one that is all zeros and so has no direction to preserve).
     */
    fun encode(scores: FloatArray): String? {
        if (scores.isEmpty()) return null
        val norm = sqrt(scores.fold(0.0) { acc, v -> acc + v.toDouble() * v }).toFloat()
        if (norm <= 0f || !norm.isFinite()) return null

        val hex = StringBuilder(scores.size * 2)
        for (score in scores) {
            // roundToInt, not toInt: truncation biases every component toward zero, which on a
            // normalized vector of small components is a systematic error large enough to reorder
            // similarity results.
            val quantized = (score / norm * QUANTIZATION_SCALE)
                .coerceIn(-QUANTIZATION_SCALE, QUANTIZATION_SCALE)
                .roundToInt()
            // and 0xFF so a negative value writes as its two-digit two's-complement byte.
            val unsigned = quantized and 0xFF
            hex.append(HEX_DIGITS[unsigned ushr 4])
            hex.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return hex.toString()
    }

    /**
     * Decodes a profile written by [encode], or returns null if [encoded] is unusable. Returns the
     * already-normalized vector, so [cosineSimilarity] over two decoded profiles is a plain dot
     * product in all but name.
     *
     * Never throws: a truncated or corrupted column value must degrade the acoustic signal to
     * nothing, not fail the recommendation that reads it.
     */
    fun decode(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank()) return null
        if (encoded.length % 2 != 0) return null
        return try {
            FloatArray(encoded.length / 2) { i ->
                val unsigned = encoded.substring(i * 2, i * 2 + 2).toInt(16)
                // Values at or above 0x80 are negative in two's complement.
                val signed = if (unsigned >= 0x80) unsigned - 0x100 else unsigned
                signed.toFloat() / QUANTIZATION_SCALE
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Cosine similarity between two profiles, in `[-1, 1]`, or 0 when they cannot be compared --
     * either is null, empty, or they came from different model versions and so have different
     * lengths. Mismatched lengths are a real case during a model swap, and scoring them as "no
     * signal" is right: the two vectors mean different things.
     */
    fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return 0f

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return if (similarity.isFinite()) similarity.toFloat() else 0f
    }
}
