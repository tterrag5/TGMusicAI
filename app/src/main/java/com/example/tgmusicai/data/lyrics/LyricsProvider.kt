package com.example.tgmusicai.data.lyrics

/**
 * Where lyrics can come from, in the default order of YT Music Morphe's "Lyrics service" list.
 * The user can turn each one off and reorder them (Settings → Lyrics); the first enabled source
 * with synced lyrics wins.
 */
enum class LyricsProvider(val id: String, val label: String, val enabledByDefault: Boolean) {
    YOUTUBE_MUSIC("ytmusic", "YouTube Music", true),
    APPLE_MUSIC("apple", "Apple Music", true),
    LRCLIB("lrclib", "LRCLIB", true),
    QQ("qq", "QQ", true),
    NETEASE("netease", "NetEase", true),
    KUGOU("kugou", "KuGou", true),
    FILE_TAGS("tags", "Lyrics saved in the file", true),
    YOUTUBE_CAPTIONS("captions", "YouTube Captions (Auto)", false),
}

data class LyricsProviderSetting(val provider: LyricsProvider, val enabled: Boolean)

object LyricsProviderSettings {

    val DEFAULT: List<LyricsProviderSetting> =
        LyricsProvider.entries.map { LyricsProviderSetting(it, it.enabledByDefault) }

    /** `"ytmusic:1,apple:0,..."`. */
    fun encode(settings: List<LyricsProviderSetting>): String =
        settings.joinToString(",") { "${it.provider.id}:${if (it.enabled) 1 else 0}" }

    /**
     * Reads [encode]'s format. Unknown ids are dropped and providers added in a later version are
     * appended at the end with their default state, so an old saved list never hides a new source.
     */
    fun decode(raw: String?): List<LyricsProviderSetting> {
        if (raw.isNullOrBlank()) return DEFAULT
        val byId = LyricsProvider.entries.associateBy { it.id }
        val saved = raw.split(",").mapNotNull { entry ->
            val (id, flag) = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            byId[id.trim()]?.let { LyricsProviderSetting(it, flag.trim() == "1") }
        }.distinctBy { it.provider }
        val missing = LyricsProvider.entries
            .filter { p -> saved.none { it.provider == p } }
            .map { LyricsProviderSetting(it, it.enabledByDefault) }
        return saved + missing
    }
}
