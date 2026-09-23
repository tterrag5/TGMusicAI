# Phase 2 — Cloud streaming and downloads: DONE

**Status: shipped and verified on an emulator.** Cloud tracks stream, cloud downloads resolve, and
both were broken before this.

This file previously specified the plan for fixing it: mint Proof-of-Origin tokens with a hidden
WebView running BotGuard, via NewPipeExtractor's `PoTokenProvider`. **That plan was followed, and
then abandoned on evidence.** The original text is in git history. What follows is what is actually
true now, because the difference is the useful part.

---

## What shipped

Stream resolution gained a **Tier 0** that talks to YouTube directly through NewPipeExtractor,
ahead of the existing Piped and Invidious tiers, in both `extractAudioStream` (playback) and
`extractAudioStreams` (downloads). Candidates are still gated on the existing `verifyStreamUrl`
pre-flight probe.

Measured: a signed `googlevideo` URL resolves in about 6 seconds and answers the pre-flight probe
with HTTP 206. Exhausting the old dead tiers took roughly 110 seconds and never produced a playable
URL.

Alongside it:

- **Playback sends the right User-Agent.** Resolution succeeded but ExoPlayer still failed every
  cloud track with `ERROR_CODE_IO_BAD_HTTP_STATUS`, because YouTube answers 206 to the realistic
  Chrome agent the URL was verified with and **403 to Media3's default**. Playback and prefetch now
  share `youTubeHttpDataSourceFactory()` so they cannot disagree again.
- **`StreamPrefetcher`** pulls the current and next track fully into the existing `AudioCacheManager`
  disk cache, so a signed URL expiring or throttling mid-song does not kill playback partway.
- **`MediaControllerManager.recoverFromPlaybackError`** re-resolves a cloud track once when its URL
  fails. There was previously no `onPlayerError` handling at all, so such a failure ended playback
  silently.
- **The dead Piped instance directory was removed**; see below for what was kept and why.

## Why the PoToken plan was wrong

It was not wrong in principle — it was wrong for this extractor, and it is worth knowing why before
anyone proposes it again.

1. The BotGuard WebView was built first and **worked**: it minted real base64 poTokens on-device,
   with an integrity token valid for 43200s. So "it didn't work" is not the reason.
2. The pinned extractor, **v0.24.8, never called the method the plan named.** It calls
   `getWebEmbedClientPoToken`, not `getWebClientPoToken`, so a provider implementing only the latter
   was silently never consulted. Worse, v0.24.8 uses the web client for metadata only, through a
   request that carries no poToken at all and now fails with `ContentNotAvailableException: The page
   needs to be reloaded`. No provider implementation could have fixed that.
3. In **current** NewPipeExtractor, `YoutubeStreamExtractor.setPoTokenProvider` is documented as a
   **no-op** — "this method currently doesn't do anything, as the extractor doesn't use any client
   supporting poTokens until SABR support is added to the extractor". The extractor resolves through
   InnerTube clients that need no token; look for `c=VISIONOS` in a resolved URL.

So the whole poToken layer was removed rather than kept as code that provably does nothing while
imposing GPLv3 on this project. **Recover it from git history if YouTube ever forces poTokens back
— do not rewrite it from scratch.**

One incidental finding, since the risk was flagged loudly in the original plan: an outdated System
WebView (Chrome 109) ran BotGuard without trouble. The failure that looked like a broken WebView was
a `</script>` accidentally written inside a comment in the HTML asset, which truncated the page.

## The dependency, which is the most fragile part

Pinned to a **commit** on the `com.github.TeamNewPipe:NewPipeExtractor` coordinate — not a tag, and
not the older `...NewPipeExtractor:extractor` submodule coordinate.

JitPack deletes tag-built artifacts over time, so `v0.26.5` resolves to nothing at all, while commit
builds persist. NewPipe pins the same way for the same reason, and says so in its own version
catalog. The project's own `maven.schabi.org` is not a usable alternative: it currently serves a
certificate that does not match its hostname.

**If Tier 0 ever breaks, bump NewPipeExtractor. Do not hunt for new public mirrors** — that is what
caused the outage this work fixed.

## Fallback tiers: measured, not guessed

Before removing anything, every host was checked:

| Endpoint | Result |
|---|---|
| `pipedapi.wireway.ch` `/search` | **200 — works** |
| `pipedapi.wireway.ch` `/streams` | 500 |
| `invidious.nerdvpn.de` | 401 |
| `inv.nadeko.net` | 403 |
| `invidious.tiekoetter.com` | 403 |
| `piped-instances.kavin.rocks` | connection times out |
| `api.invidious.io/instances.json` | **200 — works** |

Only `piped-instances.kavin.rocks` was removed: its host no longer resolves, so unlike the others it
can never self-heal, and consulting it cost a multi-second timeout on every resolution that reached
it. Piped's search fallback is kept because `/search` genuinely still works. Invidious is kept
because its instance directory is alive, so that tier can recover on its own, and it is only ever
reached when Tier 0 fails.

## Verification

```bash
./gradlew test                    # 51 unit tests, 0 failures
./gradlew connectedDebugAndroidTest   # 8 instrumented tests
```

The instrumented tests are the ones that matter here, since this can only be proven against a real
device and network:

- `StreamResolutionTest` — resolution, **cloud search** (what a version bump would break), downloads.
- `PlaybackVerificationTest` — a real ExoPlayer, through the real `SchemeAwareCacheDataSourceFactory`,
  reaching `STATE_READY` for a cloud stream **and** a local file. Both halves are mandatory: a past
  change here broke local playback entirely while streams kept working.
- `StreamPrefetchTest` — prefetching commits bytes to the cache.

Two traps if you extend these. They must not all fetch the same track concurrently: YouTube
throttles repeated fetches from one address with 403, which produced a suite that passed
individually and failed as a whole. And do not assert that a full track finishes prefetching — an
emulator sustains only tens of KB/s, so that measures the emulator, not the code.

## Still open

The deferred follow-up named here is done (prefetching). Remaining backlog items are in
`SESSION_HANDOFF.md`: drag-to-reorder playlists, the recommendations specification, and the
"Import from YouTube" tab.
