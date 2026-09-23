package com.example.tgmusicai.data.local

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes the metadata embedded in a local audio file -- ID3v2 in MP3s, Vorbis comments
 * in FLAC and Ogg, the iTunes-style atoms in M4A -- without going through MediaStore.
 *
 * MediaStore only reports the tags it chose to index, is read-only for these purposes, and does
 * not expose ReplayGain at all. Editing tags for real means rewriting the file itself, which is
 * what jaudiotagger does here.
 *
 * Every entry point returns null or false instead of throwing. Tag parsing runs against arbitrary
 * user files, a share of which are malformed in ways no library handles cleanly, and a bad file
 * must cost at most its own metadata.
 */
object AudioTagIo {

    private const val TAG = "AudioTagIo"

    init {
        // jaudiotagger logs a line per frame it parses through java.util.logging, which on a large
        // library floods logcat badly enough to hide everything else.
        try {
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
        } catch (e: Throwable) {
            Log.w(TAG, "Could not quiet jaudiotagger logging", e)
        }
    }

    /** The subset of tags the in-app editor exposes. Null means the file has no value for that field. */
    data class EditableTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val trackNumber: String? = null,
        val comment: String? = null
    )

    /** A track's stored loudness, as written by whatever tool tagged the file. */
    data class ReplayGainTags(val trackGainDb: Float?, val trackPeak: Float?)

    /** Reads [file]'s editable tags, or null if it has no readable tag at all. */
    fun readTags(file: File): EditableTags? = try {
        val tag = AudioFileIO.read(file).tag
        if (tag == null) {
            null
        } else {
            EditableTags(
                title = tag.firstOrNull(FieldKey.TITLE),
                artist = tag.firstOrNull(FieldKey.ARTIST),
                album = tag.firstOrNull(FieldKey.ALBUM),
                albumArtist = tag.firstOrNull(FieldKey.ALBUM_ARTIST),
                genre = tag.firstOrNull(FieldKey.GENRE),
                year = tag.firstOrNull(FieldKey.YEAR),
                trackNumber = tag.firstOrNull(FieldKey.TRACK),
                comment = tag.firstOrNull(FieldKey.COMMENT)
            )
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read tags from ${file.name}", e)
        null
    }

    /**
     * Writes [tags] into [file] in place, leaving any field set to null untouched rather than
     * clearing it -- a partial edit from the UI must not wipe tags the editor never showed.
     * Returns false if the file could not be rewritten, in which case it is left as it was.
     */
    fun writeTags(file: File, tags: EditableTags): Boolean = try {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        tag.setIfPresent(FieldKey.TITLE, tags.title)
        tag.setIfPresent(FieldKey.ARTIST, tags.artist)
        tag.setIfPresent(FieldKey.ALBUM, tags.album)
        tag.setIfPresent(FieldKey.ALBUM_ARTIST, tags.albumArtist)
        tag.setIfPresent(FieldKey.GENRE, tags.genre)
        tag.setIfPresent(FieldKey.YEAR, tags.year)
        tag.setIfPresent(FieldKey.TRACK, tags.trackNumber)
        tag.setIfPresent(FieldKey.COMMENT, tags.comment)
        audioFile.commit()
        true
    } catch (e: Throwable) {
        Log.e(TAG, "Could not write tags to ${file.name}", e)
        false
    }

    /**
     * Reads [file]'s ReplayGain tags, if it has any.
     *
     * Files tagged by other tools carry a measurement that took far longer to compute than reading
     * it does, so this is always tried before measuring loudness on-device. Most files have no
     * such tag, which is why the measurement path exists at all.
     */
    fun readReplayGain(file: File): ReplayGainTags? = try {
        val tag = AudioFileIO.read(file).tag
        if (tag == null) {
            null
        } else {
            val gain = tag.replayGainField(FIELD_TRACK_GAIN, KEY_TRACK_GAIN)?.let(::parseGainDb)
            val peak = tag.replayGainField(FIELD_TRACK_PEAK, KEY_TRACK_PEAK)?.toFloatOrNull()
            if (gain == null && peak == null) null else ReplayGainTags(gain, peak)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read ReplayGain from ${file.name}", e)
        null
    }

    private const val FIELD_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    private const val FIELD_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
    private const val KEY_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    private const val KEY_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"

    /**
     * Looks up a ReplayGain value by two different routes, because the tag has no single canonical
     * home. Vorbis comments store it under its plain name, which [Tag.getFirst] finds directly.
     * ID3 stores it in a user-defined TXXX frame, which that lookup misses, so the raw field name
     * is tried as well. Whichever route the file used, one of the two finds it.
     */
    private fun Tag.replayGainField(rawName: String, keyName: String): String? {
        firstOrNullRaw(rawName)?.let { return it }
        val key = try {
            FieldKey.valueOf(keyName)
        } catch (e: IllegalArgumentException) {
            // This build of jaudiotagger predates the ReplayGain field keys; the raw lookup above
            // is the only route available and has already been tried.
            return null
        }
        return firstOrNull(key)
    }

    /**
     * ReplayGain values are conventionally written with their unit attached ("-7.50 dB"), and some
     * taggers add a leading "+", so the number has to be pulled out rather than parsed directly.
     */
    private fun parseGainDb(raw: String): Float? =
        Regex("[-+]?[0-9]*\\.?[0-9]+").find(raw)?.value?.toFloatOrNull()

    private fun Tag.firstOrNull(key: FieldKey): String? = try {
        getFirst(key)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    private fun Tag.firstOrNullRaw(name: String): String? = try {
        getFirst(name)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    private fun Tag.setIfPresent(key: FieldKey, value: String?) {
        if (value == null) return
        try {
            if (value.isBlank()) deleteField(key) else setField(key, value)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not set $key", e)
        }
    }
}
