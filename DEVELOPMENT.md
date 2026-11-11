# Auto Lyrics — Development Reference

## Terminology

| Term | Meaning |
|---|---|
| **Full lyrics view** (AA) | The browse tree on Android Auto. The root has two browsable tabs: **Lyrics** (header row with art/song/artist, then current line + next 3) and **Sync** (car delay, preview, ±0.1s/±0.5s, Reset). All root items must be browsable or AA adds a "More" tab. |
| **Fast-updating screen** / **Now-playing card** (AA) | The Android Auto now-playing card. Displays title, artist, album art, and a subtitle (current lyric) via `MediaMetadataCompat`. Updated every 300ms. |
| **Small fast-updating screen** | The now-playing card when AA is in the *minimized* container (e.g. maps in the larger panel). |
| **Large fast-updating screen** | The now-playing card when it fills the larger AA container. |
| **Performance Mode** (Phone) | A separate full-screen immersive activity with large karaoke text and word pop-out. |
| **Karaoke** | Word-level timestamps (ELRC format). On the phone, the current word is highlighted; on AA, shown via bracket notation `【word】`. |
| **Synced lyrics** | Line-level timestamps (standard LRC). `LyricsStatus.FOUND`. |
| **Plain lyrics** / **Unsynced** | No timestamps. `LyricsStatus.PLAIN_ONLY`. Scrolled proportionally based on song duration. |
| **SyncLRC** | Primary lyrics API: `synclrc.tharuk.pro`. Provides karaoke, synced, and plain lyrics. |
| **LRCLIB** | Second lyrics API: `lrclib.net`. Provides synced and plain lyrics. |
| **NetEase** | Third lyrics API: `music.163.com`. Provides synced and plain lyrics. Only matched when title + artist agree. |
| **Big lyrics** (AA) | Swaps the now-playing card text: lyric in the large title slot, "Song — Artist" in the small subtitle slot. Pref `aa_big_lyrics`. |
| **AA offset** | Car audio delay for all songs (`aa_offset_ms`), set on the phone. Added on top of the per-song offset. |
| **Song offset** | Per-song lyric timing (`MediaTracker.offsetMs`), saved in the `song_offsets` prefs file and reloaded when the song plays again. |
| **Wrong lyrics? / pinned** | User-picked lyrics from the picker are stored in the cache with `pinned = true`: never auto-refreshed, pruned or cleared. |

## Architecture

```
AutoLyricsApp (Application)
  └─ MediaTracker (singleton) — playback tracking, lyrics fetching, state emission
       ├─ MediaListenerService (NotificationListenerService) — intercepts media notifications
       ├─ SyncLrcClient / LrcLibClient — lyrics API clients
       ├─ LrcParser — LRC/ELRC parsing
       ├─ MetadataCleaner — cleans track metadata for API queries
       ├─ LyricsCache — in-memory + background refresh + queue pre-fetch
       └─ AlbumColorExtractor — Palette API color extraction

  Phone UI
  ├─ MainActivity — main lyrics view, settings, sync tools
  └─ PerformanceActivity — full-screen immersive karaoke view

  Android Auto
  ├─ LyricsBrowserService (MediaBrowserServiceCompat)
  │   ├─ Browse tree (onLoadChildren) — flat single-level: track header + windowed lyrics + sync controls
  │   ├─ MediaSession — metadata (title, art, subtitle), playback state
  │   ├─ Karaoke bracket builder — buildKaraokeText()
  │   └─ Transport controls proxy — forwards play/pause/seek to source player
  └─ BootReceiver — starts service on boot/package replace
```

## File Map

### Kotlin Sources (`app/src/main/java/com/autolyrics/`)

| File | Purpose |
|---|---|
| `AutoLyricsApp.kt` | Application class. Initializes `MediaTracker`, starts `LyricsBrowserService`. |
| `MainActivity.kt` | Phone UI: lyrics display, font/delay settings, tap-to-sync, audio sync, scroll handling, theme colors. |
| `PerformanceActivity.kt` | Full-screen immersive karaoke mode with large text and word pop-out effect. |
| `model/Models.kt` | Data classes: `TrackInfo`, `LyricWord`, `LyricLine`, `AlbumColors`, `LyricsStatus`, `LyricsState`. |
| `media/MediaTracker.kt` | Singleton. Tracks active media session, fetches lyrics, manages offset, emits `StateFlow<LyricsState>`. |
| `media/MediaListenerService.kt` | `NotificationListenerService` — detects media sessions. Prefers the playing player, stays with the current one while it buffers/skips, and re-checks every 3s (watchdog) so lyrics don't freeze on a stale session. |
| `lyrics/SyncLrcClient.kt` | HTTP client for SyncLRC API (primary source). |
| `lyrics/LrcLibClient.kt` | HTTP client for LRCLIB API (second source). |
| `lyrics/NetEaseClient.kt` | HTTP client for NetEase Cloud Music (third source). Search + strict title/artist/duration match, then LRC fetch. |
| `lyrics/LrcParser.kt` | Parses LRC and ELRC format strings into `LyricLine` lists. |
| `lyrics/MetadataCleaner.kt` | Normalizes track/artist strings for API queries. `searchVariants()` builds fallback queries: first artist only, brackets/feat./"- Remastered" removed, and "Artist - Song" titles split (for Metrolist / YouTube-based players). |
| `media/CarDelayLearner.kt` | Learns the car's audio lag: when ≥3 songs' car Sync-tab nudges agree (≥70%, same sign, ≥150 ms, within 400 ms of the median), moves the median into `aa_offset_ms` and subtracts it from those songs' offsets. Prefs `aa_learn_delay`, `aa_learn_nudges`, `aa_learned_shift_ms`. |
| `media/AppFilter.kt` | Remembers every app seen with a media session and which ones the user has unticked (prefs `seen_packages`, `ignored_packages`). |
| `lyrics/LyricsCache.kt` | In-memory lyrics cache with background refresh and queue pre-fetching. |
| `util/AlbumColorExtractor.kt` | Extracts `AlbumColors` from album art bitmaps using AndroidX Palette. |
| `auto/LyricsBrowserService.kt` | Android Auto integration. Browse tree, MediaSession metadata/playback, karaoke brackets, transport proxy. |
| `auto/BootReceiver.kt` | Starts `LyricsBrowserService` on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. |

### Resources (`app/src/main/res/`)

| File | Purpose |
|---|---|
| `layout/activity_main.xml` | Phone main UI layout. |
| `layout/activity_performance.xml` | Full-screen performance mode layout. |
| `xml/automotive_app_desc.xml` | Android Auto app descriptor. |
| `values/themes.xml` | App theme (Material dark, no action bar). |
| `values/colors.xml` | Color resources. |
| `values/strings.xml` | String resources. |
| `drawable/` | Icons, backgrounds, shapes. |

### Key Configuration

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Dependencies, SDK versions, signing config, version management. |
| `AndroidManifest.xml` | Components, permissions, AA metadata. |
| `.github/workflows/build.yml` | CI: build APK, create GitHub release with versioned filename. |
| `app/signing.p12` | Old shared signing key, used for debug builds and as a fallback. |
| Release signing | Release APKs are signed with the owner's private key from GitHub secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (decoded in CI to `SIGNING_STORE_FILE`). Never commit the key. |
| `app/src/test/` | JUnit tests for `MetadataCleaner` and `LrcParser`; CI runs `testDebugUnitTest` before building. |

## Key Constants (`LyricsBrowserService`)

| Constant | Value | Purpose |
|---|---|---|
| `WINDOW_SIZE` | 4 | Synced lyric lines on the AA Lyrics tab: the current line (top, ▶) plus the next 3. |
| `PLAIN_WINDOW_SIZE` | 4 | Unsynced lyric lines on the AA Lyrics tab (estimated current line first). |
| `SYNC_STEPS` | ±0.1s, ±0.5s | Buttons on the AA Sync tab (white vector icons `ic_aa_*`, rendered to bitmaps), plus a Reset-to-0 button. |
| `PAD_WIDTH` | 60 | Character padding for browse tree items (reduces choppiness). |
| `NOTIFY_THROTTLE_MS` | 500ms | Minimum interval between browse tree refreshes. |
| `BROWSE_KARAOKE_WINDOW_MS` | 600ms | Karaoke bracket time window for browse tree items. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 300ms | Karaoke bracket time window for now-playing subtitle. |
| `SESSION_REFRESH_MS` | 1500ms | Periodic MediaSession playback state refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 2000ms | Plain lyrics browse tree periodic advance interval. |

## SharedPreferences Keys (`auto_lyrics_prefs`)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `lyrics_font_size` | Int | 16 | Phone lyrics text size (sp). |
| `lyrics_font_family` | String | `sans-serif` | Phone lyrics font family. |
| `translation_enabled` | Boolean | false | Translate non-English lyrics to English (ML Kit). |
| (`song_offsets` prefs file) | Long per `title|artist` | — | Per-song lyric timing, adjusted from the phone delay buttons or the car Sync tab. |
| `aa_karaoke_enabled` | Boolean | true | Toggle karaoke bracket display on AA. |
| `aa_offset_ms` | Long | 0 | Android Auto-specific lyrics delay (ms). |
| `aa_big_lyrics` | Boolean | true | Show the lyric big and the song name small on the AA now-playing card. |

## Karaoke Bracket Logic (AA)

The bracket display uses **monotonic advancement** to avoid visual jitter:
- Opening bracket `【` is placed at the current word index.
- Closing bracket `】` is placed at the last word within the karaoke window.
- Both `lastKaraokeWordIdx` and `endIdx` only advance forward within the same line.
- `resetKaraokeState()` is called on track change and line change to allow the brackets to start fresh.

## Lyrics Fetch Priority

1. **SyncLRC** (`synclrc.tharuk.pro`) — returns karaoke (ELRC), synced (LRC), or plain text.
2. **LRCLIB** (`lrclib.net`) — returns synced (LRC) or plain text.
3. **NetEase** (`music.163.com`) — returns synced (LRC) or plain text.
4. Each query variant from `MetadataCleaner.searchVariants()` is tried in turn until synced lyrics are found.
5. Synced from any source beats plain from any source: a later source is only asked when earlier ones had no synced lyrics (`MediaTracker.fetchFromAllSources`).
5. Within a SyncLRC response, preference: karaoke > synced > plain.
