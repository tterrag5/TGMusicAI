# TGMusicAI — Phase 2 Handoff: Durable YouTube Stream Resolution via PoToken

**Audience:** the next AI/engineer picking this up cold. You need no prior conversation history — everything required is in this document plus the repository.

**Status:** Phases 1, 3, 4, 5 and 6 of the agreed plan are **done and verified on an emulator**. Phase 2 (this document) is **not started**. It is the last remaining item and the most important one: it is what makes cloud music playable again.

---

## Part 0 — What you are walking into

### 0.1 The one-sentence version

Playing any YouTube-sourced song in this app fails, because the app resolves stream URLs through public Piped/Invidious instances and **every one of those instances is currently dead or blocked**. Your job is to replace that with resolution that talks to YouTube directly, using NewPipeExtractor plus a PoToken provider you implement with a hidden WebView.

### 0.2 Build environment (read first, it will bite you)

The project needs **JDK 17**. It is a Nix flake project. If `./gradlew` fails with:

```
Cannot find a Java installation on your machine ... matching: {languageVersion=17, ...}
```

…you are outside the dev shell. Fix with `nix develop` in the repo root, or point Gradle at a JDK 17 explicitly:

```bash
./gradlew assembleDebug -Dorg.gradle.java.home=/nix/store/<...>-openjdk-17.0.20+8
```

Commands that matter:

```bash
./gradlew assembleDebug      # build
./gradlew installDebug       # build + install to connected device/emulator
./gradlew test               # 51 JVM unit tests, all currently passing
```

`adb` is not on `PATH`; it lives at `~/Android/Sdk/platform-tools/adb`.

### 0.3 Repository state

**Nothing from the previous session is committed.** There are ~66 modified/untracked files sitting in the working tree, all building and passing tests. Treat that as the baseline. Read `CLAUDE.md` first — it was updated to describe the current architecture accurately, including a section on this exact problem.

If you want a safety net before you start: `git stash -u` is *not* what you want (it would bury a lot of finished work). Prefer committing the existing work to a branch first.

### 0.4 What the previous phases changed, in case it matters to you

- **API keys removed entirely.** There is no Gemini/OpenAI key anywhere any more. Metadata cleaning uses the on-device regex engine (`AiMetadataCleaner.cleanOffline`); lyrics translation uses ML Kit on-device translation. Do not reintroduce a key.
- **Explore tab deleted.** Cloud search now lives inside Library search, rendering local results then a "From YouTube" section (`LibraryScreen.kt`). The old `YouTubeScreen.kt` is gone; its result row moved to `ui/components/YouTubeSearchResultItem.kt`. **Cloud search still works** — only *resolution* is broken. This matters to you: users can already find cloud tracks, they just can't play them. Fixing Phase 2 lights up an existing, visible feature.
- **Navigation, Settings, backup/restore, alarms, playlists, theming** were all reworked. None of it should constrain you.

---

## Part 1 — The problem, precisely

### 1.1 What actually breaks

`MediaControllerManager.playSong()` → `resolveSongForPlayback()` → `YouTubeExtractor.extractAudioStream(videoId)`. If that returns null, the song's `mediaUri` stays a raw `https://www.youtube.com/watch?v=…` URL, which ExoPlayer cannot play.

Previously this failed **silently** — the UI just sat on "No Song Selected". That is now fixed: `MediaControllerManager` detects an unresolved URL and surfaces a toast ("Can't stream … right now. Download it to play offline."). So the current user-visible symptom is an honest error, not a dead tap. Your job is to make resolution succeed so the toast stops appearing.

**Downloads are broken by the same root cause.** `CloudDownloadManager.kt:361` calls `youtubeExtractor.extractAudioStreams(videoId)` — the same resolver. Fixing resolution fixes downloads for free. (Worth knowing: a natural-sounding idea is "just do what downloads do and buffer to RAM instead." That does not help, because downloads use the identical broken resolver.)

### 1.2 Evidence the current approach is unfixable by configuration

Measured live during the previous session, from a real network:

| Host / endpoint | Result |
|---|---|
| `pipedapi.wireway.ch/streams/<id>` | HTTP 200 — metadata resolves fine |
| …but its media proxy `pipedproxy.wireway.ch/videoplayback?…` | **HTTP 403 on every audio itag** (139/140/249/250/251), with and without a browser UA |
| `pipedapi.wireway.ch/proxy?url=…` | HTTP 404 |
| `invidious.nerdvpn.de` | DNS: no address |
| `invidious.tiekoetter.com` | DNS: no address |
| `inv.nadeko.net` | HTTP 403 |
| `piped-instances.kavin.rocks` (Piped's own directory) | connection fails — **the self-heal tier is inert** |
| `api.invidious.io/instances.json` | HTTP 200, but lists exactly **one** API-enabled instance (`invidious.f5.si`), which returns **HTTP 500** |

Also observed: some Piped hosts return **HTTP 418** to a desktop-Chrome User-Agent that lacks matching client hints — i.e. the app's UA spoofing is actively counterproductive against these hosts.

The conclusion that matters: **there is no list of public instances that stays working.** They rot continuously. Swapping hosts buys days, not a fix. This is why the solution has to stop depending on third-party servers.

### 1.3 Why NewPipeExtractor was removed from resolution originally

`CLAUDE.md` used to record that stream extraction "does not use NewPipeExtractor" because YouTube's player endpoint sits behind a PoToken wall that NewPipeExtractor could not satisfy. **That was true then and is no longer true.** NewPipeExtractor has since added first-class PoToken support. That is the opening this phase exploits.

---

## Part 2 — Background research: what a PoToken is and how you get one

### 2.1 Concept

YouTube's player endpoint (`/youtubei/v1/player`) increasingly requires a **"Proof of Origin" token (poToken)** to return usable stream URLs. Without it you get either no `adaptiveFormats`, formats with no `url`, or a playability error. This is Google's anti-bot control.

A poToken is minted by **BotGuard**, an obfuscated JavaScript VM that performs environment integrity checks. Crucially, it must run somewhere that looks like a real browser. You cannot compute it natively in Kotlin; the practical approach on Android is to execute it in a **WebView**.

### 2.2 The minting flow (two network calls, then local minting)

This is the flow NewPipe uses, and it is what you will port:

1. **Create** — `POST https://www.youtube.com/api/jnn/v1/Create`
   - Body: `["O43z0dpjhgX20SCx4KAo"]` — a constant request key
   - Headers include `Content-Type: application/json+protobuf`, `x-goog-api-key`, `x-user-agent: grpc-web-javascript/0.1`
   - Returns the BotGuard VM program.

2. **Run BotGuard in the WebView** — evaluate the returned interpreter JS, call `runBotGuard(data)`, which yields a `botguardResponse` and a `webPoSignalOutput` object held in JS memory.

3. **GenerateIT** — `POST https://www.youtube.com/api/jnn/v1/GenerateIT`
   - Body: `["O43z0dpjhgX20SCx4KAo", botguardResponse]`
   - Returns an **integrity token** plus a TTL.

4. **Mint tokens locally** — from then on, for each identifier you need a token for, evaluate `obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)` in the same WebView and base64 the resulting `Uint8Array`. **No further network calls** — one integrity token mints many poTokens until it expires.

**Expiry:** NewPipe stores `expiry - 600 seconds` (a 10-minute safety margin) and re-initialises the whole WebView when expired. Do the same.

### 2.3 Which identifier to use for which token

This is the part that is easy to get wrong. A `PoTokenResult` carries **two different tokens** derived from **two different identifiers**:

- `playerRequestPoToken` — minted from the **videoId**, sent with the `/player` request.
- `streamingDataPoToken` — minted from the **visitorData** (web/web-embed clients) or from a content-binding identifier appropriate to the client, and appended to the resulting stream URLs.

Getting these swapped produces stream URLs that 403 — which will look exactly like the bug you are trying to fix. Follow the upstream implementation literally rather than improvising.

---

## Part 3 — The API you are implementing against (verified, not guessed)

These signatures were read directly out of the jar this project already builds against —
`~/.gradle/caches/modules-2/files-2.1/com.github.TeamNewPipe.NewPipeExtractor/extractor/v0.24.8/…/extractor-v0.24.8.jar` — using `javap`.

**No dependency bump is required.** `newpipeExtractor = "0.24.8"` in `gradle/libs.versions.toml` already contains everything below.

```java
public interface org.schabi.newpipe.extractor.services.youtube.PoTokenProvider {
    PoTokenResult getWebClientPoToken(String videoId);
    PoTokenResult getWebEmbedClientPoToken(String videoId);
    PoTokenResult getAndroidClientPoToken(String videoId);
    PoTokenResult getIosClientPoToken(String videoId);
}

public final class org.schabi.newpipe.extractor.services.youtube.PoTokenResult {
    public final String visitorData;
    public final String playerRequestPoToken;
    public final String streamingDataPoToken;
    public PoTokenResult(String visitorData, String playerRequestPoToken, String streamingDataPoToken);
}
```

Registration is a **static global setter** on the stream extractor:

```java
org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
    .setPoTokenProvider(PoTokenProvider provider);   // static
    .setFetchIosClient(boolean);                     // static, also available
```

Implications worth internalising:

- The provider is **global and synchronous**. Its four methods are called from NewPipeExtractor's own threads and are *not* suspend functions. A WebView, however, must be created and driven on the **main thread**. You therefore need a bridge: the provider method blocks on a result that is produced on the main thread. Get this wrong and you will deadlock or crash with "WebView cannot be created on a background thread".
- Returning `null` from a provider method is acceptable and means "no token available" — NewPipeExtractor then makes a best-effort attempt. Use this as your graceful-degradation path.

---

## Part 4 — Recommended architecture

### 4.1 Shape

```
YouTubeExtractor (existing)
        │
        │ init { NewPipe.init(...); YoutubeStreamExtractor.setPoTokenProvider(TGPoTokenProvider) }
        ▼
TGPoTokenProvider : PoTokenProvider          ← blocking bridge, thread-safe, cached
        │
        │ runBlocking / CountDownLatch onto main thread
        ▼
PoTokenWebViewGenerator                      ← owns the hidden WebView, main-thread confined
        │
        ├── loads assets/po_token.html
        ├── POST /api/jnn/v1/Create        → BotGuard VM program
        ├── evaluateJavascript runBotGuard → botguardResponse + webPoSignalOutput
        ├── POST /api/jnn/v1/GenerateIT    → integrityToken + expiry
        └── generatePoToken(identifier)    → base64 poToken   (repeatable, no network)
```

### 4.2 Files to create

| File | Purpose |
|---|---|
| `app/src/main/assets/po_token.html` | The BotGuard host page + JS (`runBotGuard`, `obtainPoToken`, helpers). **Copy verbatim from upstream.** |
| `app/src/main/java/com/example/tgmusicai/data/youtube/potoken/PoTokenWebViewGenerator.kt` | Owns the WebView, runs the flow above, tracks expiry. |
| `app/src/main/java/com/example/tgmusicai/data/youtube/potoken/TGPoTokenProvider.kt` | Implements `PoTokenProvider`; bridges sync→main-thread; caches per-videoId. |

### 4.3 Files to modify

| File | Change |
|---|---|
| `data/youtube/YouTubeExtractor.kt` | Register the provider in `init` (near the existing `NewPipe.init(...)` at **line ~390**). Add a NewPipeExtractor resolution tier as **Tier 0** in `extractAudioStream` (**line ~686**) and `extractAudioStreams` (**line ~660**). Delete the dead `piped-instances.kavin.rocks` directory lookup. Prune dead hardcoded hosts. |
| `MainActivity.kt` | The generator needs an `Application` context. A `YouTubeExtractor` instance is already constructed there (`val youTubeExtractor = …`) — the simplest wiring is to pass context in, or initialise the provider once at app start. |

### 4.4 Ordering decision

Make NewPipeExtractor **Tier 0**, ahead of Piped and Invidious — do not delete the fallbacks outright. Rationale: NewPipe+PoToken talks to YouTube directly with no third-party dependency, so it should be preferred; but if BotGuard changes under you, having the (currently dead, potentially revived) mirrors behind it costs nothing and avoids a total outage. Keep the existing **pre-flight liveness probe** (`verifyStreamUrl`, line ~729) applied to Tier 0 results as well — it is working correctly and is exactly what currently rejects the 403 proxy URLs.

---

## Part 5 — Implementation sketches

These are structural guides, not drop-in code. **The BotGuard JavaScript itself must be copied verbatim from upstream — do not attempt to reconstruct it from prose.**

### 5.1 The generator (main-thread confined)

```kotlin
package com.example.tgmusicai.data.youtube.potoken

/**
 * Runs YouTube's BotGuard VM inside a hidden WebView to mint poTokens.
 *
 * Confined to the main thread: WebView cannot be constructed or driven from a background
 * thread. [TGPoTokenProvider] is responsible for hopping threads before calling in here.
 */
class PoTokenWebViewGenerator private constructor(
    context: Context
) {
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Derive from the device's real WebView UA rather than claiming to be desktop Chrome:
        // a UA that contradicts the platform's client hints is exactly what Google's anti-bot
        // checks look for. (The YouTube sign-in WebView in this app learned the same lesson --
        // see YouTubeLoginDialog, which strips only the "; wv" marker.)
        settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
        addJavascriptInterface(this@PoTokenWebViewGenerator, "PoTokenWebView")
    }

    private var integrityToken: String? = null
    private var expiresAtMs: Long = 0L

    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs

    /** Loads po_token.html, runs Create -> runBotGuard -> GenerateIT. */
    suspend fun initialize() { /* see Part 2.2 */ }

    /** Mints a token for [identifier] (a videoId or visitorData). No network. */
    suspend fun generatePoToken(identifier: String): String { /* evaluateJavascript(obtainPoToken...) */ }

    fun close() { webView.destroy() }
}
```

Notes that will save you time:

- `evaluateJavascript` returns via callback on the main thread — wrap each call in `suspendCancellableCoroutine`.
- Results come back **JSON-encoded** (a string result arrives quoted and escaped). Unquote before use.
- NewPipe sets `blockNetworkLoads = true` on the WebView and performs the Create/GenerateIT HTTP calls from Kotlin, handing the payloads into JS. Following that split is easier to debug than letting the page fetch by itself.
- Register your JS interface name consistently — upstream uses `PoTokenWebView`, and `po_token.html` calls back into it by that exact name. If you rename one, rename both.

### 5.2 The provider (the sync→async bridge)

```kotlin
class TGPoTokenProvider(private val appContext: Context) : PoTokenProvider {

    private val mutex = Mutex()
    private var generator: PoTokenWebViewGenerator? = null

    /**
     * NewPipeExtractor calls this synchronously from its own thread, but the WebView underneath
     * is main-thread-only -- so this blocks here while the real work is dispatched to Main.
     * Returning null is a supported outcome and means "no token": the extractor then makes a
     * best-effort attempt rather than failing outright, which is the graceful-degradation path.
     */
    override fun getWebClientPoToken(videoId: String): PoTokenResult? = runCatching {
        runBlocking {
            mutex.withLock {
                val gen = ensureInitialized()
                val visitorData = currentVisitorData()
                PoTokenResult(
                    visitorData,
                    gen.generatePoToken(videoId),        // player request binds to the videoId
                    gen.generatePoToken(visitorData)     // streaming data binds to visitorData
                )
            }
        }
    }.getOrNull()

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private suspend fun ensureInitialized(): PoTokenWebViewGenerator { /* recreate if expired */ }
}
```

Start by implementing **only `getWebClientPoToken`** and returning `null` from the other three. That is the smallest thing that can work, and it keeps the first debugging session tractable. Add the others only if the web client alone proves insufficient.

### 5.3 visitorData

`visitorData` identifies the pseudonymous session and must be consistent between the token and the player request. Two options:

1. Let NewPipeExtractor supply it — 0.24.8's release notes describe extracting visitor data from the service itself, because generated visitor data stopped yielding valid player responses.
2. Reuse this app's existing InnerTube session. **`InnerTubeCookieManager` already exists** (used for YouTube Music playlist sync via a captured `music.youtube.com` web session). If a signed-in user's visitorData is available there, it is a legitimate source.

Prefer (1) first; treat (2) as an enhancement. Do not invent a random visitorData — that is a known failure mode.

### 5.4 Integration into `YouTubeExtractor`

```kotlin
// In init, next to the existing NewPipe.init(...) at ~line 390:
NewPipe.init(NewPipeOkHttpDownloader(okHttpClient))
YoutubeStreamExtractor.setPoTokenProvider(TGPoTokenProvider(appContext))

// New Tier 0, tried before Piped in extractAudioStream(...):
try {
    val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$cleanId")
    info.audioStreams
        .sortedByDescending { it.averageBitrate }
        .forEach { s ->
            if (verifyStreamUrl(s.content)) {          // reuse the existing pre-flight probe
                val candidate = YouTubeAudioStream(
                    url = s.content,
                    format = normalizeAudioFormat(s.format?.mimeType, s.format?.suffix),
                    bitrate = s.averageBitrate
                )
                streamCache[cleanId] = CachedStream(candidate, expiryFromStreamUrl(candidate.url))
                return@withContext candidate
            }
        }
} catch (e: Exception) {
    Log.e("TGMusicCloud", "Tier 0 NewPipe+PoToken failed for $cleanId: ${e.message}", e)
}
```

`StreamInfo.getInfo` performs network I/O and must stay on `Dispatchers.IO` — the surrounding function already is. Do not call it from the main thread.

### 5.5 Upstream sources to port from

Copy these rather than reinventing them. The JS especially is not reconstructable from description:

- `po_token.html` — https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/assets/po_token.html
- `PoTokenWebView.kt` — https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenWebView.kt
- Sibling files in that `util/potoken/` package (`PoTokenGenerator`, `PoTokenProviderImpl`) show the caching and provider-bridging patterns.
- Context PRs: NewPipe [#11955](https://github.com/TeamNewPipe/NewPipe/pull/11955) (original implementation), [#12028](https://github.com/TeamNewPipe/NewPipe/pull/12028) (coroutine rewrite), NewPipeExtractor [#1272](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1272) (the extractor-side PoToken support this depends on).

**Licensing:** NewPipe is GPLv3. Check the license implications for this project before copying code wholesale. The `po_token.html` JS is the piece you most need verbatim; the Kotlin can be written independently using the upstream only as a reference.

---

## Part 6 — Alternative considered and rejected

**`ZemerTeam/zemer-cipher`** (https://github.com/ZemerTeam/zemer-cipher) — a standalone Android Kotlin library doing YouTube signature/n-param deobfuscation *and* BotGuard poToken generation, with "self-healing" player configs fetched from its GitHub repo at runtime (6-hour TTL, ETag caching) plus CI that auto-rotates configs when YouTube changes its player.

Genuinely well targeted at this exact problem, and worth revisiting if the WebView route proves unstable. Rejected as the *primary* approach because its self-healing mechanism fetches configuration from a single third-party GitHub repo at runtime — which trades one external availability dependency for another, and that is precisely the failure mode this whole phase exists to eliminate. Maven coordinates were also not clearly published at time of research.

---

## Part 7 — Honest risk assessment

1. **This is maintenance-bearing work.** BotGuard changes; NewPipeExtractor follows it. Expect periodic extractor version bumps. It is still dramatically sturdier than depending on strangers' servers, but it is not "fix once, forget".
2. **The main-thread/blocking bridge is the most likely source of hangs.** A `runBlocking` on a thread that the main thread is itself waiting on will deadlock. Test early with a real tap, not just a unit test.
3. **WebView availability.** GMS-less devices and outdated System WebView are known to break poToken generation (see PipePipe issue #2667). Your provider must degrade to `null` rather than crash.
4. **Emulator networking is flaky in this environment.** During the previous session the emulator intermittently lost DNS entirely (`Unable to resolve host …`). Do not conclude your implementation is broken until you have confirmed the emulator can resolve `www.youtube.com`.
5. **You cannot fully validate without network access to YouTube.** If your sandbox blocks it, say so plainly rather than declaring success.

---

## Part 8 — Verification plan

Ordered, and the middle one is non-negotiable per `CLAUDE.md`:

1. `./gradlew test` — 51 tests must still pass.
2. **On a real device or emulator with working network:**
   - Play a **cloud** track from Library search → audio actually plays.
   - Play a **local** track (`file://` or `content://`) → still plays. *`CLAUDE.md` explicitly requires testing both, because a past change to this layer broke local playback entirely while streams kept working.*
   - Play a **playlist mixing both** → queue advances correctly.
   - **Download** a cloud track → completes, then plays offline.
3. Watch logcat for the tier markers: you should see `Tier 0 NewPipe+PoToken resolved …` and **not** see the `Giving up on "<title>": no Piped/Invidious endpoint returned a playable stream` warning that `MediaControllerManager` currently logs.
4. Confirm resolution latency is sane. Before this work, exhausting all dead tiers took ~110 seconds; connect timeout was reduced to 6s to bound that. Tier 0 succeeding should make resolution feel near-instant.
5. Re-test after leaving the app idle past the integrity-token TTL (~10 min margin) to confirm re-initialisation works rather than failing on a stale token.

---

## Part 9 — Suggested order of work

1. Get the environment building (`nix develop`), confirm 51 tests pass, commit the existing uncommitted work to a branch.
2. Port `po_token.html` into `assets/`.
3. Build `PoTokenWebViewGenerator`, and prove it in isolation: log a minted token for a hardcoded videoId. **Do not integrate until you see a real base64 token in logcat.**
4. Build `TGPoTokenProvider` with only `getWebClientPoToken` implemented.
5. Register it in `YouTubeExtractor.init`.
6. Add Tier 0 to `extractAudioStream`, keeping `verifyStreamUrl`.
7. Verify playback end to end (Part 8).
8. Add Tier 0 to `extractAudioStreams` so downloads benefit.
9. Only now: prune the dead Piped/Invidious hosts and delete the inert `piped-instances.kavin.rocks` directory lookup.
10. Update `CLAUDE.md` — replace the "Stream resolution is currently broken" section with what actually shipped.

Step 3 is the checkpoint that matters. If you cannot mint a token standalone, nothing downstream will work, and integrating first will make the failure much harder to localise.

---

## Appendix A — Key file map

| Path | Role |
|---|---|
| `data/youtube/YouTubeExtractor.kt` | All search + resolution. `NewPipe.init` ~390, `search` ~491, `extractAudioStreams` ~660, `extractAudioStream` ~686, `verifyStreamUrl` ~729, `tryPipedStreamExtractions` ~762, `tryInvidiousStreamExtractions` ~822. Hardcoded host lists near the top. |
| `playback/MediaControllerManager.kt` | `resolveSongForPlayback`, the unresolved-URL guard, and the user-facing failure toast. |
| `data/youtube/CloudDownloadManager.kt` | Downloads; calls `extractAudioStreams` at ~361. |
| `data/youtube/InnerTubeCookieManager.kt` | Existing captured `music.youtube.com` session — possible visitorData source. |
| `ui/components/YouTubeLoginDialog.kt` | Existing WebView in this app; its UA handling is the pattern to follow. |
| `playback/PlaybackService.kt` | ExoPlayer owner. `SchemeAwareCacheDataSource` routes `http(s)` through a 500MB disk cache and everything else through a plain source — **do not make this cache-only**, it has broken local playback before. |

## Appendix B — Post-Phase-2 follow-up (not in scope)

Once resolution works, prefetching a whole track before playback is a worthwhile reliability improvement: signed googlevideo URLs carry an `expire=` parameter and are throttled, so ExoPlayer re-requesting byte ranges mid-song can fail partway through. Implement it **disk-backed through the existing `AudioCacheManager`**, not in RAM — it survives a process kill and does not scale with queue length. This was discussed and deliberately deferred until resolution is fixed, since it is useless before then.
