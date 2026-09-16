# Other Modules Map

Everything outside `data/` and `ui/`, under `app/src/main/java/com/example/tgmusicai/`.

## `playback/` — actual audio playback (Media3)

| File | Key functions | Role |
|---|---|---|
| `PlaybackService.kt` (814 ln) | `MediaLibraryService` subclass | The background service that actually plays audio and survives the UI closing. Builds the Android Auto/media-notification browse tree, owns the `ExoPlayer`/`MediaSession`. |
| `MediaControllerManager.kt` (592 ln) | `playSong`, `playQueue`, `togglePlayPause`, `pause`, `seekTo`, `skipToNext/Previous`, `skipToIndex`, `moveQueueItem`, `toggleShuffle`, `toggleRepeat`, `setPlaybackSpeed`, `setVolume`, `getAudioSessionId`, `release` | The UI-side handle to `PlaybackService` — a `MediaController` wrapper. `PlayerViewModel` calls this, this calls the service. If playback controls "don't do anything," this is the middle layer to check. |
| `AudioEffectsManager.kt` | `attach`, `setEnabled`, `setBandLevel`, `applyBandLevels`, `usePreset`, `setBassBoostStrength`, `bandLevelRange`, `centerFreqHz`, `release` | Wraps Android's `Equalizer`/`BassBoost` audio effects, attached to the current audio session. Backs `EqualizerViewModel`. |
| `SleepTimerManager.kt` | `startTimer`, `cancelTimer` | Counts down and stops playback after N minutes. |
| `SongMediaExtras.kt` | `fromSong`, `songId`, `youtubeId`, `toSong` | Packs/unpacks a `Song`'s id and YouTube id into a Media3 `Bundle` of extras, since `MediaItem` can't carry a full `Song` object across the UI/service process boundary. |

## `alarm/` — musical alarm-clock feature

| File | Key functions | Role |
|---|---|---|
| `AlarmScheduler.kt` | `scheduleAlarm`, `cancelAlarm`, `scheduleSnooze`, `calculateNextTriggerTime` | Talks to Android's `AlarmManager` to arrange the actual OS-level wakeup. |
| `AlarmReceiver.kt` | `AlarmWakeLock.acquire/release` + `AlarmReceiver` (`BroadcastReceiver`) | Fires when `AlarmManager` triggers; grabs a wake lock and starts `AlarmPlaybackService`. |
| `AlarmPlaybackService.kt` (331 ln) | `Service`, companion `currentAlarm`, `start`, `stopRinging` | Plays the alarm sound/song and launches `AlarmActivity` full-screen. |
| `AlarmActivity.kt` (305 ln) | `AlarmActivity`, `AlarmScreenContent` (Compose) | The full-screen "alarm is ringing" UI with snooze/dismiss. |
| `BootReceiver.kt` | `BroadcastReceiver` | Re-registers all enabled alarms with `AlarmManager` after a device reboot (alarms don't survive reboot otherwise). |

## `ai/` — on-device AI (song tagging + lyrics embeddings)

Fully optional/self-contained: every call `MainActivity` makes into `AiFeatureManager` catches
its own failures, so a broken model file can never crash app startup.

| File | Key functions | Role |
|---|---|---|
| `AiFeatureManager.kt` | class, `backfillAll` (called by `MainActivity` startup job) | Top-level facade the rest of the app calls into; coordinates tagging + embedding engines and writes results via `AiSongTagsDao`. |
| `SongTaggingEngine.kt` | `tagAudioFile`, `release` | Runs an on-device model over decoded audio to produce mood/genre-style tags. |
| `LyricsEmbeddingEngine.kt` | `embed`, `cosineSimilarity` (companion), `release` | Turns lyrics text into a vector embedding, used for similarity/matching (radio mode). |
| `PcmDecoder.kt` | `decodeToMonoPcm16k` | Decodes an audio file to mono 16kHz PCM, the input format the tagging model expects. |
| `WordPieceTokenizer.kt` | `encode`, `attentionMask`, `fromAsset` (companion) | BERT-style tokenizer feeding text into the lyrics embedding model. |
| `AiModelResult.kt` | `getOrNull` | Small `Success`/`Failure` result wrapper used by the engines above. |

## Root-level

- `MainActivity.kt` — see `01_UI_LAYER.md`; the composition root that wires every layer together.
