package com.example.tgmusicai.data.youtube.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume

/**
 * Mints YouTube "Proof of Origin" tokens by running Google's BotGuard VM inside a hidden WebView.
 *
 * ## Why this exists
 *
 * YouTube's `/youtubei/v1/player` endpoint increasingly refuses to return usable stream URLs
 * without a poToken. Without one you get no `adaptiveFormats`, formats with no `url`, or an
 * outright playability error. The token can only be produced by BotGuard, an obfuscated JavaScript
 * VM that checks it is running in something resembling a real browser -- so it cannot be computed
 * in Kotlin, and a WebView is the practical way to host it on Android.
 *
 * ## Attribution and licence
 *
 * The flow below, and `assets/po_token.html` in particular, are ported from NewPipe
 * (https://github.com/TeamNewPipe/NewPipe), which is licensed under the **GNU General Public
 * License v3.0**. The HTML asset is copied verbatim because the BotGuard call sequence cannot be
 * reconstructed from a description; this Kotlin is an independent coroutine implementation written
 * against upstream's RxJava version as a reference. See the repository's LICENSE and README.
 *
 * ## Protocol
 *
 * 1. `POST /api/jnn/v1/Create` with a constant request key returns the BotGuard VM program.
 * 2. `runBotGuard(data)` in the page evaluates that program, producing a `botguardResponse` and a
 *    `webPoSignalOutput` object that stays in JavaScript memory.
 * 3. `POST /api/jnn/v1/GenerateIT` exchanges the response for an integrity token plus a lifetime.
 * 4. `obtainPoToken(webPoSignalOutput, integrityToken, identifier)` then mints tokens locally, with
 *    **no further network calls**, until the integrity token expires.
 *
 * ## Threading
 *
 * A WebView can only be constructed and driven on the main thread, so every member here is
 * main-thread confined and [TGPoTokenProvider] is responsible for hopping threads before calling
 * in. Note that `@JavascriptInterface` methods are invoked on a private binder thread, *not* the
 * main thread, which is why each of them re-dispatches through [scope] instead of touching
 * [webView] directly.
 *
 * ## On the API key
 *
 * [GOOGLE_API_KEY] below is **not** a user-supplied credential and is not a reintroduction of the
 * removed AI API-key setting that `CLAUDE.md` forbids. It is a fixed public constant baked into
 * YouTube's own web client, identical for every user, with no settings entry and nothing for a
 * user to configure. It is required by the BotGuard endpoints and cannot be omitted.
 */
class PoTokenWebViewGenerator private constructor(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Completed once an integrity token is in hand, or completed exceptionally if setup fails. */
    private val initialization = CompletableDeferred<Unit>()

    /** In-flight [generatePoToken] calls, keyed by the identifier each one was asked for. */
    private val pendingTokens = mutableMapOf<String, CompletableDeferred<String>>()

    private var expiresAtMs: Long = 0L
    private var closed = false

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        // Safe Browsing would send this page's (data:) content for reputation checks and can
        // interfere with the BotGuard code; upstream disables it too. Using the platform setter
        // with a version guard avoids pulling in androidx.webkit for one call, since minSdk is 24
        // and this API landed in 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }
        // A desktop Chrome UA is correct *here*, unlike in YouTubeLoginDialog where it broke
        // sign-in: the token being minted is for YouTube's web client, so BotGuard has to believe
        // it is running in a desktop browser. It is also safe, because blockNetworkLoads below
        // means this WebView never makes a request of its own -- the UA is only ever observed by
        // the BotGuard code running inside the page, and the same string is sent on the two
        // requests this class makes from Kotlin.
        settings.userAgentString = USER_AGENT
        settings.blockNetworkLoads = true
        addJavascriptInterface(this@PoTokenWebViewGenerator, JS_INTERFACE)

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.message().contains("Uncaught")) {
                    // Everything the page does is wrapped in try-catch, so an *uncaught* error
                    // almost always means the JS engine could not even parse the code -- i.e. the
                    // System WebView is too old. Distinguishing this from a bug in our own code is
                    // the whole reason BadWebViewException exists.
                    val detail = "\"${message.message()}\", source: ${message.sourceId()} " +
                        "(${message.lineNumber()})"
                    Log.e(TAG, "WebView implementation is broken: $detail")
                    failEverything(BadWebViewException(detail))
                }
                return super.onConsoleMessage(message)
            }
        }
    }

    /** True once the integrity token is old enough that it should no longer be trusted. */
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs

    //region Initialization

    /**
     * Loads the BotGuard page and splices in a call to [downloadAndRunBotguard] so the flow starts
     * as soon as the page is ready. Upstream relies on the same `</script>` splice, which is why
     * `po_token.html` must keep exactly one script block.
     */
    @MainThread
    private fun loadHtmlAndObtainBotguard(context: Context) {
        scope.launch {
            try {
                val html = withContext(Dispatchers.IO) {
                    context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                }
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"),
                    "text/html",
                    "utf-8",
                    null,
                )
            } catch (t: Throwable) {
                failEverything(t)
            }
        }
    }

    /** Called from the page once it has loaded. Runs on a binder thread, not the main thread. */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        scope.launch {
            try {
                val response = botguardRequest(CREATE_URL, "[ \"$REQUEST_KEY\" ]")
                val challengeData = parseChallengeData(response)
                webView.evaluateJavascript(
                    """try {
                        data = $challengeData
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null,
                )
            } catch (t: Throwable) {
                failEverything(t)
            }
        }
    }

    /** Called from the page when BotGuard has produced its response. */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        scope.launch {
            try {
                val response = botguardRequest(
                    GENERATE_IT_URL,
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
                )
                val (integrityToken, lifetimeSeconds) = parseIntegrityTokenData(response)
                // Expire early by a margin, so a token is never used in the moments around its
                // real expiry -- a stale token produces stream URLs that 403 later, which is far
                // harder to diagnose than simply re-initializing.
                expiresAtMs = System.currentTimeMillis() +
                    ((lifetimeSeconds - EXPIRY_MARGIN_SECONDS) * 1000L)

                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    Log.i(TAG, "poToken generator ready (integrity token valid ${lifetimeSeconds}s)")
                    initialization.complete(Unit)
                }
            } catch (t: Throwable) {
                failEverything(t)
            }
        }
    }

    /** Called from the page when BotGuard itself fails. */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "BotGuard initialization failed in JavaScript: $error")
        failEverything(buildExceptionForJsError(error))
    }

    //endregion

    //region Minting tokens

    /**
     * Mints a token for [identifier] -- a videoId for a player-request token, or visitorData for a
     * streaming-data token. Purely local; makes no network call.
     *
     * Getting the two identifiers the wrong way round is the classic mistake here: it produces
     * stream URLs that 403, which looks exactly like the failure this whole class exists to fix.
     * See [TGPoTokenProvider] for which is which.
     */
    suspend fun generatePoToken(identifier: String): String = withContext(Dispatchers.Main.immediate) {
        initialization.await()
        check(!closed) { "poToken generator has been closed" }

        val deferred = CompletableDeferred<String>()
        pendingTokens[identifier] = deferred

        webView.evaluateJavascript(
            """try {
                identifier = "$identifier"
                u8Identifier = ${stringToU8(identifier)}
                poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                poTokenU8String = ""
                for (i = 0; i < poTokenU8.length; i++) {
                    if (i != 0) poTokenU8String += ","
                    poTokenU8String += poTokenU8[i]
                }
                $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
            } catch (error) {
                $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
            }""",
        ) { /* the real result arrives through the JavaScript interface, not here */ }

        deferred.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        scope.launch {
            val deferred = pendingTokens.remove(identifier) ?: return@launch
            try {
                deferred.complete(u8ToBase64(poTokenU8))
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.e(TAG, "obtainPoToken failed in JavaScript: $error")
        scope.launch {
            pendingTokens.remove(identifier)?.completeExceptionally(buildExceptionForJsError(error))
        }
    }

    //endregion

    //region Utilities

    /** POSTs to one of the two BotGuard endpoints with the headers they require. */
    private suspend fun botguardRequest(url: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_PROTOBUF))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PoTokenException("$url returned HTTP ${response.code}")
                }
                response.body?.string() ?: throw PoTokenException("$url returned an empty body")
            }
        }

    /**
     * Fails initialization and every in-flight request with [cause]. Safe to call more than once
     * and from any thread; [CompletableDeferred] ignores completion after the first.
     */
    private fun failEverything(cause: Throwable) {
        initialization.completeExceptionally(cause)
        scope.launch {
            val pending = pendingTokens.values.toList()
            pendingTokens.clear()
            pending.forEach { it.completeExceptionally(cause) }
        }
    }

    private fun buildExceptionForJsError(error: String): PoTokenException =
        if (error.contains("SyntaxError", ignoreCase = true)) {
            BadWebViewException(error)
        } else {
            PoTokenException(error)
        }

    /**
     * Releases the WebView. Safe to call from any thread: it hops to the main thread itself,
     * because every WebView method must run there and callers otherwise have to remember to wrap
     * this, which is easy to get wrong and fails only at runtime.
     */
    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        closed = true

        // Settle everything still waiting before cancelling the scope -- once the scope is gone,
        // anything dispatched through it would never run and its caller would hang forever.
        val cause = PoTokenException("poToken generator closed")
        initialization.completeExceptionally(cause)
        val pending = pendingTokens.values.toList()
        pendingTokens.clear()
        pending.forEach { it.completeExceptionally(cause) }
        scope.cancel()

        webView.clearHistory()
        webView.clearCache(true)
        // Make sure the page is not still executing anything when the WebView is destroyed.
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    //endregion

    companion object {
        private const val TAG = "TGMusicPoToken"
        private const val ASSET_NAME = "po_token.html"
        private const val JS_INTERFACE = "PoTokenWebView"

        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"

        /**
         * A fixed public constant from YouTube's web client, not a user credential -- see the
         * class KDoc. Required by the BotGuard endpoints.
         */
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        /** Discard the integrity token this long before it really expires. */
        private const val EXPIRY_MARGIN_SECONDS = 600L

        private val JSON_PROTOBUF = "application/json+protobuf".toMediaType()

        /**
         * Builds a generator and suspends until it has an integrity token and can mint tokens.
         * Throws [BadWebViewException] if the device's WebView cannot run BotGuard at all.
         */
        suspend fun create(
            context: Context,
            httpClient: OkHttpClient,
        ): PoTokenWebViewGenerator = withContext(Dispatchers.Main.immediate) {
            val generator = PoTokenWebViewGenerator(context.applicationContext, httpClient)
            generator.loadHtmlAndObtainBotguard(context.applicationContext)
            try {
                generator.initialization.await()
            } catch (t: Throwable) {
                generator.close()
                throw t
            }
            generator
        }
    }
}

/**
 * Suspends until the WebView returns a result for [script]. Kept for call sites that need the
 * direct return value rather than a callback through the JavaScript interface.
 */
@MainThread
internal suspend fun WebView.evaluateJavascriptSuspending(script: String): String =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result -> continuation.resume(result ?: "null") }
    }
