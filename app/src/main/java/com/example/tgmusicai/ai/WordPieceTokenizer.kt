package com.example.tgmusicai.ai

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Minimal BERT-style WordPiece tokenizer, loaded from a plain `vocab.txt` (one token per line,
 * line number = token id -- the standard BERT/MiniLM vocab format). Implements the same
 * lowercase + basic punctuation/whitespace split + greedy-longest-match subword algorithm the
 * original model was trained with, since [LyricsEmbeddingEngine]'s embeddings are only meaningful
 * if tokenized the same way.
 */
class WordPieceTokenizer private constructor(private val vocab: Map<String, Int>) {

    val padId: Int = vocab["[PAD]"] ?: 0
    val unkId: Int = vocab["[UNK]"] ?: 100
    val clsId: Int = vocab["[CLS]"] ?: 101
    val sepId: Int = vocab["[SEP]"] ?: 102

    /** Tokenizes [text] into up to [maxLength] token ids, wrapped with [CLS]/[SEP] and padded with [PAD]. */
    fun encode(text: String, maxLength: Int): IntArray {
        val basicTokens = basicTokenize(text)
        val wordPieceIds = mutableListOf<Int>()
        for (token in basicTokens) {
            wordPieceIds.addAll(wordPieceTokenize(token))
            if (wordPieceIds.size >= maxLength - 2) break
        }
        val truncated = wordPieceIds.take(maxLength - 2)

        val ids = IntArray(maxLength) { padId }
        ids[0] = clsId
        truncated.forEachIndexed { index, id -> ids[index + 1] = id }
        ids[truncated.size + 1] = sepId
        return ids
    }

    /** Attention mask matching [encode]'s output: 1 for real tokens (including [CLS]/[SEP]), 0 for padding. */
    fun attentionMask(ids: IntArray): IntArray = IntArray(ids.size) { if (ids[it] != padId) 1 else 0 }

    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in lower) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                    tokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        return (code in 33..47) || (code in 58..64) || (code in 91..96) || (code in 123..126)
    }

    /** Greedy longest-match-first subword split, e.g. "playing" -> ["play", "##ing"]. Unmatched chunks become [UNK]. */
    private fun wordPieceTokenize(token: String): List<Int> {
        if (token.length > 100) return listOf(unkId)
        val output = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var matchedId: Int? = null
            while (start < end) {
                val substr = (if (start > 0) "##" else "") + token.substring(start, end)
                val id = vocab[substr]
                if (id != null) { matchedId = id; break }
                end--
            }
            if (matchedId == null) return listOf(unkId)
            output.add(matchedId)
            start = end
        }
        return output
    }

    companion object {
        /** Loads a WordPiece vocab from an app asset file (one token per line). Throws on I/O failure -- callers should catch. */
        fun fromAsset(context: Context, assetPath: String): WordPieceTokenizer {
            val vocab = HashMap<String, Int>()
            BufferedReader(InputStreamReader(context.assets.open(assetPath), Charsets.UTF_8)).use { reader ->
                var line: String?
                var index = 0
                while (reader.readLine().also { line = it } != null) {
                    vocab[line!!] = index
                    index++
                }
            }
            return WordPieceTokenizer(vocab)
        }
    }
}
