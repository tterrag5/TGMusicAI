package com.example.tgmusicai.data.lyrics

/**
 * Writes LRC timestamps. Lines are `[mm:ss.xx]`, and the inline enhanced-LRC tags that carry word
 * starts and line ends are `<mm:ss.xx>`; [com.example.tgmusicai.data.repository.LyricsRepository]
 * reads both back.
 */
object LrcFormat {

    fun lineTag(ms: Long): String = "[${clock(ms)}]"

    fun wordTag(ms: Long): String = "<${clock(ms)}>"

    private fun clock(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val centis = (safe % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }
}
