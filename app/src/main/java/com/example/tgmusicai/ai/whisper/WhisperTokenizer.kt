package com.example.tgmusicai.ai.whisper

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Decodes Whisper's generated token ids back into text. Only the decode direction is needed here
 * (the model only ever produces ids; nothing in transcription re-encodes text into ids), so this
 * skips bundling `merges.txt` / implementing BPE merge encoding entirely -- `vocab.json` (id <->
 * token-string) is the only tokenizer asset required.
 *
 * Whisper reuses GPT-2's byte-level BPE: every token string is itself a sequence of *printable*
 * unicode characters, each one standing in for one raw byte (control/whitespace bytes are remapped
 * to unused printable code points so they survive being stored as ordinary vocab strings). Decoding
 * a token therefore means mapping each of its characters back to its original byte via
 * [byteDecoder], concatenating, and interpreting the result as UTF-8 -- not treating the token
 * string as literal text.
 */
internal class WhisperTokenizer private constructor(
    private val idToToken: Array<String?>
) {
    /** Decodes a sequence of generated token ids into text, silently skipping any id with no vocab entry (special/control tokens). */
    fun decode(ids: List<Int>): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            val token = idToToken.getOrNull(id) ?: continue
            for (ch in token) {
                val b = byteDecoder[ch] ?: continue
                out.write(b)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        fun fromAsset(context: Context, assetPath: String): WhisperTokenizer {
            val json = context.assets.open(assetPath).use { it.reader(Charsets.UTF_8).readText() }
            val obj = JSONObject(json)
            var maxId = -1
            val keys = obj.keys()
            val entries = mutableListOf<Pair<String, Int>>()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = obj.getInt(token)
                entries.add(token to id)
                if (id > maxId) maxId = id
            }
            val table = arrayOfNulls<String>(maxId + 1)
            for ((token, id) in entries) table[id] = token
            return WhisperTokenizer(table)
        }

        /**
         * Inverse of GPT-2's `bytes_to_unicode()`: maps each of the 256 printable characters used
         * to represent a raw byte in a byte-level-BPE vocab string back to that byte value.
         * Printable ASCII/Latin-1 bytes map to themselves as characters; the remaining ~68 byte
         * values (control chars, space, etc., which can't safely round-trip as raw vocab-string
         * characters) are remapped to unused code points starting at U+0100, in byte-value order.
         */
        private val byteDecoder: Map<Char, Int> = run {
            val printableBytes = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val decoder = HashMap<Char, Int>()
            for (b in printableBytes) decoder[b.toChar()] = b
            var nextCode = 256
            for (b in 0..255) {
                if (b !in printableBytes) {
                    decoder[nextCode.toChar()] = b
                    nextCode++
                }
            }
            decoder
        }
    }
}
