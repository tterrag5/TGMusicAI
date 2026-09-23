package com.example.tgmusicai.data.youtube.potoken

import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Supplies NewPipeExtractor with Proof-of-Origin tokens, so YouTube's player endpoint returns
 * usable stream URLs and cloud playback and downloads work without any third-party instance.
 *
 * ## Contract with NewPipeExtractor
 *
 * NewPipeExtractor calls these methods **synchronously, from its own threads**, and they are not
 * suspend functions -- but the WebView underneath is main-thread-only. This class is therefore the
 * bridge: it blocks the calling thread while the real work runs on the main thread.
 *
 * Returning `null` is an explicitly supported outcome meaning "no token available", after which
 * the extractor makes a best-effort attempt anyway. That is the graceful-degradation path, and it
 * is why nothing here is allowed to throw: a failure to mint a token must never propagate into
 * playback. This mirrors how the `ai/` engines are contained.
 *
 * ## Ordering requirement
 *
 * A [PoTokenResult] carries two tokens derived from two different identifiers, and they are not
 * interchangeable:
 *
 *  - `playerRequestPoToken` is bound to the **videoId** and sent with the `/player` request.
 *  - `streamingDataPoToken` is bound to the **visitorData** and appended to the stream URLs.
 *
 * Swapping them yields stream URLs that 403 -- indistinguishable from the original bug. The
 * streaming token must also be minted exactly once, before any player token, for a given
 * generator.
 */
object TGPoTokenProvider : PoTokenProvider {

    private const val TAG = "TGMusicPoToken"

    private lateinit var appContext: Context
    private lateinit var httpClient: OkHttpClient

    /**
     * Set permanently once the device proves it cannot run BotGuard. Retrying in that case only
     * spends battery and delays playback, so this follows the same "fail once, stay unavailable"
     * rule the on-device AI engines use.
     */
    @Volatile
    private var webViewUnsupported = false

    private val lock = Any()
    private var generator: PoTokenWebViewGenerator? = null
    private var visitorData: String? = null
    private var streamingPoToken: String? = null

    /** True once [initialize] has run; guards against use before MainActivity/Application setup. */
    @Volatile
    private var initialized = false

    fun initialize(context: Context, client: OkHttpClient) {
        appContext = context.applicationContext
        httpClient = client
        initialized = true
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!initialized || webViewUnsupported) return null

        // runBlocking on the main thread would deadlock against the generator's own main-thread
        // work. NewPipeExtractor should always be driven from a background thread here (both
        // extractAudioStream and extractAudioStreams run on Dispatchers.IO), so this is a
        // defensive guard rather than an expected path.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "getWebClientPoToken called on the main thread; skipping to avoid deadlock")
            return null
        }

        return try {
            runBlocking { obtainWebClientPoToken(videoId, forceRecreate = false) }
        } catch (e: BadWebViewException) {
            Log.e(TAG, "Device WebView cannot run BotGuard; disabling poToken generation", e)
            webViewUnsupported = true
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Could not obtain a poToken for $videoId", t)
            null
        }
    }

    /**
     * Only the web client is implemented. It is the smallest thing that can work and keeps the
     * failure surface small; the others fall back to the extractor's best-effort behaviour.
     */
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private suspend fun obtainWebClientPoToken(
        videoId: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val session = ensureSession(forceRecreate)

        val playerPoToken = try {
            session.generator.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (session.freshlyCreated) throw t
            // The WebView can lose its page while the app is backgrounded, which invalidates the
            // in-memory BotGuard state. One rebuild from scratch is worth trying before giving up.
            Log.w(TAG, "poToken minting failed; rebuilding the generator and retrying", t)
            return obtainWebClientPoToken(videoId, forceRecreate = true)
        }

        return PoTokenResult(session.visitorData, playerPoToken, session.streamingPoToken)
    }

    private class Session(
        val generator: PoTokenWebViewGenerator,
        val visitorData: String,
        val streamingPoToken: String,
        val freshlyCreated: Boolean,
    )

    private suspend fun ensureSession(forceRecreate: Boolean): Session {
        val existing = synchronized(lock) {
            val current = generator
            val reusable = current != null && !forceRecreate && !current.isExpired() &&
                visitorData != null && streamingPoToken != null
            if (reusable) {
                Session(current!!, visitorData!!, streamingPoToken!!, freshlyCreated = false)
            } else {
                null
            }
        }
        if (existing != null) return existing

        // visitorData identifies the pseudonymous session and must match the token bound to it.
        // It comes from InnerTube itself: a generated or random value stopped producing valid
        // player responses, and this path requires no account, which keeps anonymous playback
        // working as the primary case.
        val freshVisitorData = withContext(Dispatchers.IO) {
            val clientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
            clientRequestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
            YoutubeParsingHelper.getVisitorDataFromInnertube(
                clientRequestInfo,
                NewPipe.getPreferredLocalization(),
                NewPipe.getPreferredContentCountry(),
                YoutubeParsingHelper.getYouTubeHeaders(),
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
        }

        val previous = synchronized(lock) { generator.also { generator = null } }
        previous?.close()

        val freshGenerator = PoTokenWebViewGenerator.create(appContext, httpClient)
        // Must happen exactly once, before any player token is minted from this generator.
        val freshStreamingPoToken = freshGenerator.generatePoToken(freshVisitorData)

        synchronized(lock) {
            generator = freshGenerator
            visitorData = freshVisitorData
            streamingPoToken = freshStreamingPoToken
        }

        Log.i(TAG, "poToken session established")
        return Session(freshGenerator, freshVisitorData, freshStreamingPoToken, freshlyCreated = true)
    }
}
