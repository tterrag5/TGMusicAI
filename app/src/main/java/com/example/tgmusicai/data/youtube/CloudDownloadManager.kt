package com.example.tgmusicai.data.youtube

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.tgmusicai.data.local.AiMetadataCleaner
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.MediaScanner
import com.example.tgmusicai.data.local.dao.PendingDownloadDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.PendingDownload
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.ui.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lifecycle states for one track's cloud download, driving what the Downloads UI shows.
 * Not strictly linear: [PAUSED] can return to [DOWNLOADING] via [CloudDownloadManager.resumeDownload].
 * [FAILED] currently ends the sequence -- no automatic retry.
 */
enum class DownloadStatus {
    IDLE,
    EXTRACTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Snapshot of one track's download progress, published via [CloudDownloadManager.downloadMap]. A
 * single instance is reused across a track's whole lifecycle (e.g. `EXTRACTING` -> `DOWNLOADING`,
 * with [progressFraction] climbing to 1.0 -> `COMPLETED`), with fields not relevant to the
 * current [status] simply left at their defaults.
 */
data class DownloadProgressState(
    val videoId: String,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progressFraction: Float = 0f,
    val localUri: String? = null,
    val errorMessage: String? = null,
    val title: String = "",
    val uploader: String = "",
    val durationSeconds: Long = 0L,
    val thumbnailUri: String? = null,
    // Path to a partially-downloaded file left behind by a pause, so a resume can pick up where
    // it left off (via an HTTP Range request) instead of starting over from scratch.
    val partialFilePath: String? = null
)

/**
 * Background Cloud Downloader managing YouTube audio stream downloads with Deduplication Check,
 * automatic multi-tier fallback retry logic, bounded parallel downloading, and pause/cancel.
 *
 * Depends on [YouTubeExtractor] for resolving candidate playable stream URLs (see that class for
 * why extraction needs multiple fallback tiers), [SongDao] for indexing finished downloads into
 * the local Room database, and optionally [PendingDownloadDao] -- a small Room table used purely
 * as a crash/kill recovery journal, so [resumePendingDownloads] can re-enqueue downloads that were
 * still in flight when the app process last died. Downloads run as coroutines on this manager's
 * own [scope] (not tied to any particular screen's lifecycle), gated by [downloadSemaphore] so
 * only a bounded number transfer bytes concurrently; [downloadMap] publishes live per-track
 * progress for the UI to observe.
 */
class CloudDownloadManager(
    private val context: Context,
    private val songDao: SongDao,
    private val youtubeExtractor: YouTubeExtractor = YouTubeExtractor(),
    private val coverArtScraper: CoverArtScraper? = null,
    private val pendingDownloadDao: PendingDownloadDao? = null,
    private val aiFeatureManager: com.example.tgmusicai.ai.AiFeatureManager? = null
) {
    private val TAG = "CloudDownloadManager"
    private val LOG_TAG = "TGMusicCloud"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // At most this many downloads actually transfer bytes at once; everything else queued past
    // this limit sits at IDLE ("Queued") until a permit frees up. Keeps "download this whole
    // playlist" from saturating the network/disk with dozens of simultaneous connections while
    // still genuinely parallelizing multiple downloads instead of the old one-at-a-time queue.
    private val downloadSemaphore = Semaphore(permits = 3)

    // Tracks the in-flight coroutine Job for each videoId currently downloading (or paused-and-
    // resumable), so pauseDownload/cancelDownload have something to actually cancel.
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadMap = MutableStateFlow<Map<String, DownloadProgressState>>(emptyMap())
    /**
     * Live map of every tracked download, keyed by video ID, for UI observation. Entries persist
     * after reaching [DownloadStatus.COMPLETED] or [DownloadStatus.FAILED] until [cancelDownload]
     * removes them.
     */
    val downloadMap: StateFlow<Map<String, DownloadProgressState>> = _downloadMap.asStateFlow()

    /**
     * Enqueues and starts downloading audio for a single track. Performs deduplication first.
     */
    fun downloadTrack(
        videoId: String,
        title: String,
        uploader: String,
        durationSeconds: Long,
        thumbnailUri: String? = null
    ) {
        if (_downloadMap.value[videoId]?.status == DownloadStatus.DOWNLOADING ||
            _downloadMap.value[videoId]?.status == DownloadStatus.EXTRACTING
        ) {
            Log.d(LOG_TAG, "Download already in progress for video ID '$videoId'")
            return
        }

        Log.d(LOG_TAG, "Enqueuing downloadTrack for video ID '$videoId', title '$title'")
        updateProgress(
            videoId,
            DownloadProgressState(videoId = videoId, status = DownloadStatus.IDLE, title = title, uploader = uploader, durationSeconds = durationSeconds, thumbnailUri = thumbnailUri)
        )
        launchDownload(videoId, title, uploader, durationSeconds, resumeFrom = null)
    }

    /**
     * Re-enqueues every download that was still in flight (queued or actively downloading) the
     * last time the app process ended, e.g. because it was killed mid-download. Safe to call on
     * every app launch -- a no-op when there's nothing pending.
     */
    fun resumePendingDownloads() {
        val dao = pendingDownloadDao ?: return
        scope.launch {
            val pending = dao.getAll()
            for (item in pending) {
                Log.d(LOG_TAG, "Resuming interrupted download for video ID '${item.videoId}' ('${item.title}')")
                updateProgress(
                    item.videoId,
                    DownloadProgressState(videoId = item.videoId, status = DownloadStatus.IDLE, title = item.title, uploader = item.uploader, durationSeconds = item.durationSeconds)
                )
                launchDownload(item.videoId, item.title, item.uploader, item.durationSeconds, resumeFrom = null)
            }
        }
    }

    /**
     * Bulk downloads all undownloaded tracks in a playlist, in parallel (bounded by
     * [downloadSemaphore]) rather than strictly one at a time.
     */
    fun downloadPlaylist(songs: List<Song>) {
        val toDownload = songs.filter { !it.isDownloaded || it.mediaUri.isBlank() }
        Log.d(LOG_TAG, "Starting downloadPlaylist for ${toDownload.size} tracks")

        // Mark every track as queued immediately -- otherwise the Downloads screen only shows
        // whichever tracks happen to already be downloading, and the rest of the playlist looks
        // like it was never enqueued at all instead of visibly sitting in the "Queued" section.
        for (song in toDownload) {
            val videoId = song.youtubeId ?: ""
            updateProgress(
                videoId,
                DownloadProgressState(
                    videoId = videoId,
                    status = DownloadStatus.IDLE,
                    title = song.title,
                    uploader = song.artist,
                    durationSeconds = song.durationMs / 1000L,
                    thumbnailUri = song.artworkUri
                )
            )
        }

        for (song in toDownload) {
            val videoId = song.youtubeId ?: ""
            val durationSeconds = song.durationMs / 1000L
            launchDownload(videoId, song.title, song.artist, durationSeconds, resumeFrom = null)
        }
    }

    /**
     * Launches (or relaunches, for a resume) the download coroutine for [videoId], gated by
     * [downloadSemaphore] so at most a handful run concurrently, and tracks the Job so it can be
     * paused/cancelled later.
     */
    private fun launchDownload(
        videoId: String,
        title: String,
        uploader: String,
        durationSeconds: Long,
        resumeFrom: String?
    ) {
        val job = scope.launch {
            pendingDownloadDao?.insert(
                PendingDownload(videoId = videoId, title = title, uploader = uploader, durationSeconds = durationSeconds)
            )
            downloadSemaphore.withPermit {
                executeDownload(videoId, title, uploader, durationSeconds, resumeFrom)
            }
        }
        activeJobs[videoId] = job
        job.invokeOnCompletion { activeJobs.remove(videoId, job) }
    }

    /**
     * Pauses an in-progress download: cancels its coroutine but deliberately leaves any partial
     * file on disk so [resumeDownload] can continue it later via an HTTP Range request instead of
     * starting over. A no-op if the download isn't currently active.
     */
    fun pauseDownload(videoId: String) {
        val current = _downloadMap.value[videoId] ?: return
        if (current.status != DownloadStatus.DOWNLOADING && current.status != DownloadStatus.EXTRACTING) return
        activeJobs[videoId]?.cancel()
        updateProgress(videoId, current.copy(status = DownloadStatus.PAUSED))
        Log.d(LOG_TAG, "Paused download for video ID '$videoId' at ${(current.progressFraction * 100).toInt()}%")
    }

    /**
     * Resumes a paused download, continuing from its partial file (if the retried stream still
     * supports Range requests and matches the same format) rather than starting from zero.
     */
    fun resumeDownload(videoId: String) {
        val current = _downloadMap.value[videoId] ?: return
        if (current.status != DownloadStatus.PAUSED) return
        updateProgress(videoId, current.copy(status = DownloadStatus.IDLE))
        launchDownload(videoId, current.title, current.uploader, current.durationSeconds, resumeFrom = current.partialFilePath)
    }

    /**
     * Cancels a download permanently: stops the coroutine, deletes any partial file, forgets the
     * resumable queue entry, and removes it from [downloadMap] entirely.
     */
    fun cancelDownload(videoId: String) {
        val current = _downloadMap.value[videoId]
        activeJobs[videoId]?.cancel()
        activeJobs.remove(videoId)
        current?.partialFilePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error deleting partial file for cancelled download $videoId", e)
            }
        }
        scope.launch { pendingDownloadDao?.deleteByVideoId(videoId) }
        _downloadMap.update { it - videoId }
        Log.d(LOG_TAG, "Cancelled download for video ID '$videoId'")
    }

    /**
     * Deduplication Check: Checks if a matching track is already downloaded locally.
     */
    suspend fun checkAndDeduplicate(videoId: String?, title: String, uploader: String): Song? {
        if (!videoId.isNullOrBlank()) {
            val existingYt = songDao.getSongByYoutubeId(videoId)
            if (existingYt != null && existingYt.isDownloaded && existingYt.mediaUri.isNotBlank()) {
                return existingYt
            }
        }

        val existingMeta = songDao.findByTitleAndNormalizedArtist(title, uploader)
        if (existingMeta != null && existingMeta.isDownloaded && existingMeta.mediaUri.isNotBlank()) {
            return existingMeta
        }

        return null
    }

    /**
     * Runs one download attempt end to end, on [Dispatchers.IO]: dedup check, stream extraction,
     * byte-for-byte download (with resume support), Room indexing, cover-art scraping, and
     * optional AI auto-tagging. Steps, in order:
     * 1. Skip if [checkAndDeduplicate] finds a matching track already downloaded (skipped on a
     *    resume, since that was already checked before the original attempt).
     * 2. Resolve candidate audio stream URLs via [YouTubeExtractor.extractAudioStreams] -- fails
     *    the whole download if none resolve.
     * 3. Ensure the on-disk Music directory exists.
     * 4. Try each candidate stream in turn until one downloads successfully: an HTTP `Range`
     *    request continues [resumeFrom]'s partial file if this is the first candidate and its
     *    path matches; otherwise any stale partial file is discarded and the download starts
     *    fresh. Progress is reported via [updateProgress] as bytes arrive, and
     *    [kotlinx.coroutines.ensureActive] is checked every chunk so a pause/cancel interrupts the
     *    transfer promptly instead of only between whole candidates.
     * 5. On success, insert/update the [Song] row in Room -- reusing an existing placeholder row
     *    (matched by URI, then YouTube ID, then title+artist) instead of always inserting fresh,
     *    so a song already present as an undownloaded cloud placeholder doesn't end up duplicated.
     * 6. Best-effort cover art scraping via [coverArtScraper].
     * 7. Best-effort AI auto-tagging via [aiFeatureManager], fired on [scope] without being
     *    awaited so a slow/failing analysis can never delay reporting [DownloadStatus.COMPLETED].
     *
     * A [CancellationException] (from [pauseDownload] or [cancelDownload]) is deliberately
     * rethrown rather than turned into [DownloadStatus.FAILED], since those callers already set
     * the correct terminal state themselves before cancelling the job. Any other exception marks
     * the download [DownloadStatus.FAILED] with a message describing what went wrong.
     */
    private suspend fun executeDownload(
        videoId: String,
        title: String,
        uploader: String,
        durationSeconds: Long,
        resumeFrom: String?
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(LOG_TAG, "Executing cloud download for video ID '$videoId' ('$title' by '$uploader')")
            updateProgress(
                videoId,
                DownloadProgressState(videoId = videoId, status = DownloadStatus.IDLE, title = title, uploader = uploader, durationSeconds = durationSeconds)
            )
            // Step 1: Deduplication Check (skipped on a resume -- we already know it's not a dup)
            if (resumeFrom == null) {
                val deduplicated = checkAndDeduplicate(videoId, title, uploader)
                if (deduplicated != null) {
                    Log.d(LOG_TAG, "Deduplication hit for '$title' ($videoId). Reusing local URI: ${deduplicated.mediaUri}")
                    if (videoId.isNotBlank() && deduplicated.youtubeId.isNullOrBlank()) {
                        songDao.insertSong(deduplicated.copy(youtubeId = videoId))
                    }
                    updateProgress(
                        videoId,
                        DownloadProgressState(
                            videoId = videoId,
                            status = DownloadStatus.COMPLETED,
                            progressFraction = 1.0f,
                            localUri = deduplicated.mediaUri
                        )
                    )
                    pendingDownloadDao?.deleteByVideoId(videoId)
                    return@withContext
                }
            }

            // Step 2: Extract candidate audio stream URLs across fallback tiers
            updateProgress(
                videoId,
                DownloadProgressState(videoId = videoId, status = DownloadStatus.EXTRACTING, title = title, uploader = uploader, durationSeconds = durationSeconds)
            )

            Log.d(LOG_TAG, "Extracting audio stream candidates for video ID '$videoId'...")
            val candidateStreams = youtubeExtractor.extractAudioStreams(videoId)
            if (candidateStreams.isEmpty()) {
                val errorMsg = "Failed to extract audio stream for video ID '$videoId': No stream URLs resolved across fallback endpoints"
                Log.e(LOG_TAG, errorMsg)
                updateProgress(
                    videoId,
                    DownloadProgressState(
                        videoId = videoId,
                        status = DownloadStatus.FAILED,
                        errorMessage = errorMsg
                    )
                )
                pendingDownloadDao?.deleteByVideoId(videoId)
                return@withContext
            }

            Log.d(LOG_TAG, "Resolved ${candidateStreams.size} candidate audio stream(s) for video ID '$videoId'")

            // Step 3: Prepare destination directory
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music")
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }

            val sanitizedTitle = FormatUtils.sanitizeFileName(title)
            val sanitizedUploader = FormatUtils.sanitizeFileName(uploader)

            var downloadSucceeded = false
            var lastFailureReason = "Unknown network error"

            // Step 4: Stream download with automatic retry on secondary endpoints
            for ((index, audioStream) in candidateStreams.withIndex()) {
                val fileName = "${sanitizedUploader}_${sanitizedTitle}_${videoId}.${audioStream.format}"
                val outputFile = File(musicDir, fileName)

                // Only the first candidate attempt on a resume can actually continue the old
                // partial file -- it's the one whose path/format we remembered from the pause.
                // If this candidate's expected path doesn't match, treat it as a fresh download
                // (deleting any stale partial rather than risking corrupting it with mismatched
                // data).
                val isResumeCandidate = index == 0 && resumeFrom != null && resumeFrom == outputFile.path
                val alreadyDownloadedBytes = if (isResumeCandidate && outputFile.exists()) outputFile.length() else 0L
                if (!isResumeCandidate && outputFile.exists()) {
                    outputFile.delete()
                }

                Log.d(LOG_TAG, "Attempting download candidate #${index + 1}/${candidateStreams.size} for video ID '$videoId': format=${audioStream.format}, bitrate=${audioStream.bitrate}, resumeBytes=$alreadyDownloadedBytes")

                updateProgress(
                    videoId,
                    DownloadProgressState(
                        videoId = videoId,
                        status = DownloadStatus.DOWNLOADING,
                        progressFraction = 0.05f,
                        title = title,
                        uploader = uploader,
                        durationSeconds = durationSeconds,
                        partialFilePath = outputFile.path
                    )
                )

                val requestBuilder = Request.Builder()
                    .url(audioStream.url)
                    .addHeader("User-Agent", YouTubeExtractor.REALISTIC_USER_AGENT)
                if (alreadyDownloadedBytes > 0L) {
                    requestBuilder.addHeader("Range", "bytes=$alreadyDownloadedBytes-")
                }
                val request = requestBuilder.build()

                var response: okhttp3.Response? = null
                try {
                    response = httpClient.newCall(request).execute()
                    val responseCode = response.code
                    val isRangeHonored = responseCode == 206
                    if (!response.isSuccessful || response.body == null) {
                        lastFailureReason = "HTTP $responseCode ${response.message}"
                        Log.e(LOG_TAG, "Download attempt failed for video ID '$videoId' at URL ${audioStream.url}: HTTP $responseCode ${response.message}. Trying next candidate...")
                        if (outputFile.exists()) outputFile.delete()
                        continue
                    }

                    // If we asked for a Range but the server ignored it and sent the full file
                    // back (200 instead of 206), start clean rather than appending a full copy
                    // onto existing bytes.
                    val startOffset = if (alreadyDownloadedBytes > 0L && isRangeHonored) alreadyDownloadedBytes else 0L
                    if (alreadyDownloadedBytes > 0L && !isRangeHonored && outputFile.exists()) {
                        outputFile.delete()
                    }

                    Log.d(LOG_TAG, "HTTP $responseCode received from ${audioStream.url} for video ID '$videoId'. Downloading payload from byte $startOffset...")
                    val body = response.body!!
                    val contentLength = body.contentLength()
                    val totalExpected = if (startOffset > 0L && contentLength > 0) startOffset + contentLength else contentLength

                    body.byteStream().use { inputStream ->
                        RandomAccessFile(outputFile, "rw").use { raf ->
                            raf.seek(startOffset)
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = startOffset

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive() // let pause/cancel interrupt promptly
                                raf.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                if (totalExpected > 0) {
                                    val fraction = totalBytesRead.toFloat() / totalExpected.toFloat()
                                    updateProgress(
                                        videoId,
                                        DownloadProgressState(
                                            videoId = videoId,
                                            status = DownloadStatus.DOWNLOADING,
                                            progressFraction = fraction.coerceIn(0f, 1f),
                                            title = title,
                                            uploader = uploader,
                                            durationSeconds = durationSeconds,
                                            partialFilePath = outputFile.path
                                        )
                                    )
                                }
                            }
                        }
                    }

                    val localUri = Uri.fromFile(outputFile).toString()

                    val cleaned = AiMetadataCleaner.clean(
                        rawTitle = title,
                        rawArtist = if (uploader != "Unknown Artist") uploader else null
                    )

                    // Step 5: Index into Room DB. Update an existing row in place (by local URI,
                    // then by youtubeId, then by title+artist) rather than always inserting a new
                    // one -- otherwise a song already present as an undownloaded cloud placeholder
                    // (e.g. added to a playlist from search, or synced from a YouTube playlist)
                    // ends up duplicated: one placeholder row plus a second, newly-downloaded row.
                    val song = Song(
                        title = cleaned.cleanTitle,
                        artist = cleaned.artist ?: uploader,
                        album = "YouTube Cloud",
                        durationMs = durationSeconds * 1000L,
                        mediaUri = localUri,
                        producer = cleaned.producer,
                        youtubeId = videoId,
                        isDownloaded = true,
                        folderPath = outputFile.parent
                    )

                    val existingByUri = songDao.getSongByUri(localUri)
                    val existingPlaceholder = existingByUri ?: run {
                        (if (videoId.isNotBlank()) songDao.getSongByYoutubeId(videoId) else null)
                            ?: songDao.findByTitleAndNormalizedArtist(cleaned.cleanTitle, cleaned.artist ?: uploader)
                    }

                    val songId = if (existingPlaceholder != null) {
                        songDao.insertSong(
                            existingPlaceholder.copy(
                                title = cleaned.cleanTitle,
                                artist = cleaned.artist ?: uploader,
                                producer = cleaned.producer,
                                mediaUri = localUri,
                                youtubeId = videoId,
                                isDownloaded = true,
                                folderPath = outputFile.parent
                            )
                        )
                        existingPlaceholder.id
                    } else {
                        songDao.insertSong(song)
                    }

                    MediaScanner.scanMediaStore(context, songDao)

                    // Step 6: Cover Art Scraping
                    val scraper = coverArtScraper ?: try {
                        val db = AppDatabase.getDatabase(context)
                        val repository = MusicRepository(
                            songDao = songDao,
                            playlistDao = db.playlistDao(),
                            songStatsDao = db.songStatsDao(),
                            alarmDao = db.alarmDao()
                        )
                        CoverArtScraper(context, repository, youtubeExtractor)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Could not initialize CoverArtScraper for track $songId", e)
                        null
                    }

                    val songToScrape = songDao.getSongById(songId) ?: song.copy(id = songId)
                    if (scraper != null) {
                        try {
                            scraper.scrapeAndSaveArtwork(songToScrape)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Cover art scraping failed for track $songId", e)
                        }
                    }

                    // Step 7: Auto-tag with on-device AI (fully optional, best-effort). Launched
                    // on the manager's own scope rather than awaited here, so a slow or failing
                    // analysis can never delay this download from reporting COMPLETED.
                    if (aiFeatureManager != null) {
                        scope.launch {
                            try {
                                aiFeatureManager.analyzeSongIfNeeded(songToScrape)
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "AI auto-tagging failed for track $songId", e)
                            }
                        }
                    }

                    updateProgress(
                        videoId,
                        DownloadProgressState(
                            videoId = videoId,
                            status = DownloadStatus.COMPLETED,
                            progressFraction = 1.0f,
                            localUri = localUri
                        )
                    )

                    Log.d(LOG_TAG, "Successfully downloaded and indexed video ID '$videoId' ('$title') -> $localUri")
                    pendingDownloadDao?.deleteByVideoId(videoId)
                    downloadSucceeded = true
                    break
                } catch (e: CancellationException) {
                    // A pause: leave the partial file and current progress state alone (already
                    // updated to PAUSED by pauseDownload()) and stop retrying other candidates.
                    response?.close()
                    throw e
                } catch (e: Exception) {
                    lastFailureReason = e.message ?: "Network error during stream download"
                    Log.e(LOG_TAG, "Exception downloading video ID '$videoId' from URL ${audioStream.url}: $lastFailureReason", e)
                    if (outputFile.exists()) outputFile.delete()
                } finally {
                    response?.close()
                }
            }

            if (!downloadSucceeded) {
                val failureMsg = "Download failed for video ID '$videoId' after trying ${candidateStreams.size} endpoint(s). Last reason: $lastFailureReason"
                Log.e(LOG_TAG, failureMsg)
                updateProgress(
                    videoId,
                    DownloadProgressState(
                        videoId = videoId,
                        status = DownloadStatus.FAILED,
                        errorMessage = failureMsg
                    )
                )
                pendingDownloadDao?.deleteByVideoId(videoId)
            }
        } catch (e: CancellationException) {
            // Pause or cancel -- state already handled by pauseDownload()/cancelDownload(). Don't
            // overwrite it with a FAILED status here.
            throw e
        } catch (e: Exception) {
            val failureMsg = "Error executing download for video ID '$videoId': ${e.message}"
            Log.e(LOG_TAG, failureMsg, e)
            updateProgress(
                videoId,
                DownloadProgressState(
                    videoId = videoId,
                    status = DownloadStatus.FAILED,
                    errorMessage = failureMsg
                )
            )
            pendingDownloadDao?.deleteByVideoId(videoId)
        }
    }

    /**
     * Merges [state] into [_downloadMap] for [videoId], carrying forward title/uploader/duration/
     * thumbnail from the existing entry when the new state leaves them blank, so callers don't
     * have to repeat metadata on every progress tick.
     */
    private fun updateProgress(videoId: String, state: DownloadProgressState) {
        _downloadMap.update { current ->
            val existing = current[videoId]
            // Every progress update along a download's lifecycle carries the title/uploader/
            // thumbnail forward from the first update that set them, so callers don't have to
            // repeat them on every single state transition.
            var merged = state
            if (existing != null) {
                if (merged.title.isBlank()) {
                    merged = merged.copy(title = existing.title, uploader = existing.uploader, durationSeconds = existing.durationSeconds)
                }
                if (merged.thumbnailUri == null) {
                    merged = merged.copy(thumbnailUri = existing.thumbnailUri)
                }
            }
            current + (videoId to merged)
        }
    }
}
