# PocketLink Android — Architecture, Recent Changes & AI Handoff Reference

> **Purpose:** Reference guide for AI coding agents and Android engineers continuing development on **PocketLink**. Covers project structure, UI/navigation architecture, custom Apple Music Now Playing & container-transform animations, Liquid Glass styling, 3-bar drawer layering, SSD bridge / Media3 internals, testing, and known platform diagnostics.

---

## 1. Project Identity & Build Constraints

| Property | Value |
| :--- | :--- |
| **App Name** | `PocketLink` (`app/src/main/res/values/strings.xml`, `settings.gradle.kts`, `metadata.json`) |
| **Application ID** | `com.aistudio.pocketlink.xkqvmz` (keep constant across updates) |
| **Namespace** | `com.example` (with native SSD bridge under `io.github.augztn.pocketlink.ssdbridge`) |
| **UI Toolkit** | Jetpack Compose + Material Design 3 + Edge-to-Edge (`enableEdgeToEdge()`) |
| **Media Stack** | AndroidX Media3 (`ExoPlayer`, `MediaSessionService`, `MediaController`) + `OkHttpDataSource` + `PocketLinkMediaEngine` |
| **Build Command** | `gradle assembleDebug` (or `compile_applet`) — **never** use `./gradlew` |
| **Test Command** | `gradle :app:testDebugUnitTest --console=plain` (Robolectric + JVM unit tests; never use `adb`/Espresso) |

---

## 2. Package & File Map

- **`app/src/main/java/com/example/`**
  - `MainActivity.kt` — Entry point (`enableEdgeToEdge()`), permission requests, mounts `PocketLinkApp`.
  - **`data/model/`**
    - `PocketLinkModels.kt` — `AppThemeMode`, `NetworkRouteType`, `IndexedFile`, `HostSystemTelemetry`, `StorageVolume`, `TransferItem`, `ServerProfile`, `AppSettingsState`.
  - **`data/network/`**
    - `PocketLinkApi.kt` — HTTP client (`sharedBaseClient` OkHttp pool + `.newBuilder()`), host-scoped auth headers (`reqUrl.startsWith(baseUrl)`), REST endpoints, media stream URLs.
    - `YtMusicClient.kt` — InnerTube browse/charts (`FEmusic_charts`, `FEmusic_home`), song search + debounced suggestions, Up Next radio (`youtubei/v1/next`), multi-client audio resolver (`ANDROID_VR`, `IOS`, `ANDROID_MUSIC`) with stream cache, and LRCLIB synced/plain lyrics fetcher (`/api/get`, `/api/search`).
  - **`data/store/`**
    - `SettingsRepository.kt`, `AppLogger.kt` — DataStore settings persistence (including YouTube Music streaming options) and diagnostics buffer.
  - **`media/`**
    - `AudioPlaybackController.kt` — ViewModel-facing async Media3 `MediaController` client binding to `MediaPlaybackService`, supporting both host streams and external `pocketlink-yt://` URIs, related radio appending, and UI `AudioPlaybackState`.
    - `TransferManager.kt` — Upload/download queue.
  - **`ui/`**
    - `PocketLinkApp.kt` — Root shell (`PocketLinkApp`), `OverlayRoute` backstack (including `OverlayRoute.YtMusic` and standalone disconnected mini player), `MainConnectedShell`, outer `ModalNavigationDrawer`, and `NowPlaying` container-transform overlay.
    - `PocketLinkViewModel.kt` — State holder, `sharedYtClient`, YT discovery/search/radio/lyrics state, bounded Coil `ImageLoader`, clean shutdown in `onCleared()`.
    - **`theme/Theme.kt`** — Light, Dark, AMOLED color schemes and `LocalPocketLinkColors` / `LocalPocketLinkDarkTheme`.
    - **`components/PocketLinkComponents.kt`** — `LiquidGlassPill`, `liquidBackdropBlur`, `FloatingDockScrimBackdrop`, `FloatingNavigationDock`, `MusicQuickAccessMenu`, `ShortcutCatalog`, `PersistentMiniPlayer`, `AnimatedEqualizer`.
    - **`screens/`**
      - `HomeDashboardScreen.kt` & `HomeNavigationDrawerSheet` — Dashboard cards + hoisted 3-bar drawer content (with `"yt_music"` quick action).
      - `YtMusicScreen.kt` — Standalone YouTube Music discovery, search + suggestions, mood/genre filters, Quick Picks carousel, Top Charts list, active-track queue/equalizer state, and disabled-state toggle card.
      - `ExploreScreen.kt`, `FileManagerScreen.kt`, `DocumentsBrowserScreen.kt`, `MediaBrowserScreen.kt`, `PhotoGalleryScreen.kt`, `SearchScreen.kt`
      - `NowPlayingScreen.kt` — Apple Music–inspired full audio player with Coil artwork, `"YT STREAM"` badge, and interactive LRCLIB synced/plain lyrics pane.
      - `VideoPlayerScreen.kt` — Media3 ExoPlayer video player with customizable overlay/gestures/fit/speed.
      - `SettingsScreen.kt` & `SettingsDetailScreens.kt` — Theme, Dock & Shortcuts, **YT Music Streaming**, Video Player, Navigation, Audio, and Storage settings.
      - `ConnectionScreen.kt` (includes standalone `YT Music` launch card and Settings shortcut), `LocalStorageSetupScreen.kt`, `TransferManagerScreen.kt`, `DiagnosticsScreen.kt`, `AboutScreen.kt`.
- **`app/src/main/java/io/github/augztn/pocketlink/ssdbridge/`**
  - `BridgeService.kt`, `NativeApiServer.kt` — Embedded HTTP host server (closes `mediaIndex` in `stop()`).
  - `MediaIndex.kt` — SQLite + `MediaIndexScanner` (provides `close()` to shut down executor & DB cleanly).
  - `MediaPlaybackService.kt` — Media3 `MediaSessionService` + `PocketLinkMediaEngine` + `RoutingHttpDataSource` + `ResolvingDataSource.Factory` (`pocketlink-yt://<videoId>`) + `MediaPlaybackBridge.applySkipSilence(...)`.

---

## 3. Detailed Summary of Recent Changes

### 3.1 Apple Music–Inspired `NowPlayingScreen` & Synced Lyrics
- **Atmospheric Color-Mesh Backdrop:** Deterministic multi-stop palette selected per track (`AppleMusicPalettes`), cross-faded with `animateColorAsState(tween(700))` and layered via custom `Canvas` radial glows + subtle dark gradient scrim.
- **Minimal Top Chrome:** Top drag grabber bar (`38.dp × 5.dp`), left frosted minimize chevron (`testTag("now_playing_minimize_btn")`), uppercase folder label / `"YT STREAM"` badge, and right frosted **Quick Destinations** grid button (`testTag("now_playing_shortcuts_btn")`).
- **Play/Pause Artwork Scale Physics & Coil Remote Artwork:** Square artwork (supports remote YT/album artwork when `showArtwork` is enabled) scales smoothly (`1.0f` playing vs `0.87f` paused) with spring physics (`dampingRatio = 0.72f`) and animated elevation shadow (`28.dp` vs `8.dp`). Supports horizontal swipe-to-skip and vertical swipe-to-dismiss.
- **Interactive Lyrics Pane (`now_playing_lyrics_btn` / `now_playing_lyrics_pane`):** Fetches synced or plain lyrics from LRCLIB via `YtMusicClient.fetchLyrics(...)`, highlights the active timestamp line, auto-scrolls with playback position, and supports tap-to-seek for synced lines.
- **Left-Aligned Track Header & Inline Speed Pill:** Marquee-scrolling `track.displayTitle`, `track.artist` / stream subtitle, and a right frosted speed badge (`1.0×`) expanding a translucent `LazyRow` speed selector (`0.75×`–`2.0×`).
- **Thumb-Free Capsule Scrubber (`AppleMusicScrubber`):** Interactive `6.dp` capsule progress bar that expands to `10.dp` while dragging/tapping, paired with tabular (`fontFeatureSettings = "tnum"`) elapsed/remaining timestamps and a stream format pill.
- **Pure White Transport & Bottom Utility Row:** Clean white Previous / Play-Pause (`testTag("now_playing_play_pause_btn")`) / Next controls, Shuffle, Repeat (`OFF`/`ALL`/`ONE`), Lyrics toggle, and **Playing Next** queue sheet (`AutoMirrored.Rounded.QueueMusic`).

### 3.2 Upgraded Opening & Closing Animations (`PocketLinkApp.kt`)
- **True Bounding-Box Container Transform for Now Playing:**
  - Tracks live root coordinates via `onGloballyPositioned { coords -> ... boundsInRoot() }` from both `PersistentMiniPlayer` (`miniPlayerBounds`) and the dock's Music pill (`musicButtonBounds`).
  - **Target Anchor Selection:**
    - If `settings.showMiniPlayer && (!miniPlayerDismissed || audioState.isPlaying) && audioState.currentTrack != null && miniPlayerBounds != null` → expands from and collapses directly back into `miniPlayerBounds`.
    - Otherwise → expands from and collapses directly back into `musicButtonBounds`.
  - **Aspect-Correct Clipping Container (Zero Squashing):** Instead of distorting `NowPlayingScreen` via uneven `scaleX`/`scaleY`, `PocketLinkApp` interpolates the clipped container box (`curLeft`, `curTop`, `curWidthPx`, `curHeightPx`, `curCornerDp`) between `targetAnchor` and full viewport bounds while keeping inner `NowPlayingScreen` scaling uniform and smoothly faded.
  - **Hardware-Accelerated Background Depth Recede:** GPU-composited background scale-back (`lerp(1f, 0.935f, p)`), rounded frame (`24.dp * p`), and dark dim scrim (`0.48f * p`).
  - **Standard Overlays:** Emphasized spring entry (`dampingRatio = 0.90f, stiffness = 460f`) and `FastOutLinearInEasing` exit (`210ms`) for all `OverlayRoute` sheets.

### 3.3 3-Bar Navigation Drawer Layering Fix
- Extracted `HomeNavigationDrawerSheet` from `HomeDashboardScreen.kt` and hoisted `ModalNavigationDrawer` cleanly above `MainConnectedShell` in `PocketLinkApp.kt`.
- Opening the 3-bar drawer renders the scrim and drawer cleanly **above** the floating dock and mini player, includes `.statusBarsPadding().navigationBarsPadding()`, and restricts swipe-to-open gestures to `selectedTab == MainTab.HOME && overlayStackEmpty && !dockQuickMenuExpanded`.

### 3.4 Floating Shortcut FAB Removal & Music/NowPlaying Access
- Legacy fields `floatingShortcutEnabled` and `floatingShortcutTapAction` remain unused in `AppSettingsState` only for backward compatibility with existing DataStore entries.
- Users access all customizable shortcuts (`Explore`, `Files`, `Music`, `YT Music`, `Videos`, `Photos`, `Documents`, `Favorites`, `Upload`) via long-pressing the **Music** pill in `FloatingNavigationDock` (`MusicQuickAccessMenu`), tapping Quick Destinations in `NowPlayingScreen`, or using Home Drawer / Quick Actions.

### 3.5 Real-Time Frosted Glass & GPU Backdrop Blur
- **`BackdropGlassState` & `Modifier.backdropGlassSource(state)` (`PocketLinkComponents.kt`, `PocketLinkApp.kt`):** On Android 12+ (API 31+), scrollable screen content records into a hardware-accelerated Compose `GraphicsLayer` (`rememberGraphicsLayer()`). Sibling floating glass overlays (`FloatingNavigationDock`, `PersistentMiniPlayer`, `MusicQuickAccessMenu`) receive `LocalBackdropGlassState` strictly outside/above the recorded source box (guarded by `isRecording` to guarantee zero recursive draws) and render the live shifted `GraphicsLayer` through `RenderEffect.createBlurEffect(26.dp, 26.dp, CLAMP)` underneath a translucent frosted tint and crisp `0.95.dp` specular hairline border.
- `FloatingDockScrimBackdrop(useLiquidGlass)` keeps the bottom scrim light and airy in glass mode so scrolling content visibly blurs through the floating dock and mini player. `Modifier.liquidBackdropBlur(radius)` continues to blur content behind `dockQuickMenuExpanded`.

### 3.6 Video Player Customization & Settings
- Enhanced `VideoPlayerScreen.kt` with ExoPlayer controls, brightness/volume/seek gestures, pinch-to-zoom, aspect fit modes (`FIT`, `FILL`, `ZOOM`), customizable auto-hide timeout, seek increments, default playback speed, tap-to-toggle playback, and auto-fullscreen—persisted via `SettingsRepository.updateVideoOptions(...)`.

### 3.7 Music Playback, Background MediaSessionService & Performance Optimizations
- **Migrated Audio Playback to Jetpack Media3 `MediaSessionService` + `MediaController`:**
  - `MediaPlaybackService` (`io.github.augztn.pocketlink.ssdbridge`) extends `MediaSessionService`, owns `ExoPlayer` + `MediaSession`, declares `WAKE_LOCK` (`C.WAKE_MODE_NETWORK`), and provides system media notifications/lock-screen controls with single-owner AudioFocus.
  - `PocketLinkMediaEngine` configures `DefaultRenderersFactory`, `DefaultAudioSink`, `DefaultExtractorsFactory`, and `DefaultLoadControl` (15s–50s buffer).
- **Host & Connect Performance:** Offloaded `LocalHostController.getStatus()` to `Dispatchers.IO`, tuned `PocketLinkApi.sharedBaseClient`, enabled SQLite WAL in `MediaIndexDatabase`, and parallelized `HomeDashboardScreen.loadAll()`.

### 3.8 Discover Streaming (NewPipeExtractor), Dual-Transport HTTP 403 Fix, Embedded Audio Metadata & Spotify-Style Infinite Autoplay (`∞`)
- **Two-Pass Strict Opus-First Waterfall (`NewPipeAudioExtractor`, `YtMusicClient`):**
  - **Pass 1 (Opus-Only First):** Queries `NewPipeExtractor` (primary, appending progressive muxed `itag=18` fallback) and InnerTube clients (`ANDROID_VR_171`, `VISIONOS`, `ANDROID_VR_165`, `ANDROID_VR_143`, `ANDROID_VR` 1.61, `TVHTML5`, `TV_EMBED`, `ANDROID_MUSIC`, `ANDROID_TESTSUITE`, `IOS`) partitioning Opus (`audio/webm; codecs="opus"`, `itag 774/251/250/249`) ahead of non-Opus formats. Native mobile clients omit `Origin` and send `userAgent`, `utcOffsetMinutes`, `timeZone`, and `clientScreen` inside `context.client`.
  - **Pass 2 & 3 (Graceful Fallback + Native HLS):** If no Opus stream passes the >1 MiB probe, Pass 2 probes non-Opus adaptive M4A, progressive `itag=18`, and `VISIONOS`/`IOS` HLS `.m3u8` (`hlsManifestUrl` verified for `#EXTM3U`), with Piped/Invidious proxies as Pass 3. `MediaPlaybackService` uses `media3-exoplayer-hls` and handles `HlsSwitchException` in `onPlayerError` to seamlessly swap the active `MediaItem` to `MimeTypes.APPLICATION_M3U8` while routing HLS traffic through standard `OkHttpDataSource`.
  - **HTTP 403 Anti-Block & Seek Pipeline (`ChunkedYoutubeDataSource`, `YtMusicClient.probeStreamUrl`, `AudioPlaybackController`, `NowPlayingScreen`):**
    - `probeStreamUrl` validates Chunk 1 (`0..16 KB`) AND Chunk 2 strictly past YouTube's **`1 MiB` (`1,048,576` byte) 403 wall** via `computeSecondProbeOffset(totalBytes)` (`>= 1,114,112` bytes / 60% of track), immediately rejecting 1-MiB-walled streams before playback so scrubbing/seeking past 60s works reliably.
    - `ChunkedYoutubeDataSource` executes direct OkHttp 512 KB bounded chunks with a single authoritative `User-Agent`, parses `clen=`, caches redirected edge hosts, and auto-flips between `&range=` query mode and HTTP `Range:` header mode on HTTP 403.
    - `AppleMusicScrubber` commits `targetMs` directly on tap and drag release (`onScrubEnd: (Long) -> Unit`) eliminating single-tap seek drops.
    - `AudioPlaybackController` supports up to 3 automatic client failovers (`MAX_DISCOVER_FAILOVER_RETRIES = 3`), blacklisting failed clients in `YtMusicClient.failedClientsByVideo`.
  - **Distinct 4-Tier Opus Buckets (BitChord + Mochy-Music):** `HIGH` targets `itag 774`/`251`; `MEDIUM` targets `itag 250`; `LOW` targets `itag 249`; `AUTO` resolves to `HIGH` on unmetered Wi-Fi/Ethernet and `MEDIUM` on metered cellular.
- **Spotify-Style Infinite Autoplay (`∞`) & Infinite Discover Browsing:**
  - `YtMusicClient.fetchRadioNext` follows YouTube Music `nextRadioContinuationData` / `nextContinuationData` tokens (with NewPipe fallback), and `YtMusicClient.findSeedVideoIdForTrack` + `fetchMoreDiscoverTracks` power continuous pagination and recommendations.
  - `AudioPlaybackController.onNearQueueEnd` triggers automatically whenever `remaining <= 3` or playback finishes (`STATE_ENDED`, Repeat OFF) — working for **both Discover queues AND local/host music playlists**, appending tagged `"Suggested for you · Autoplay ∞"` tracks (`isAutoplayRecommendation = true`) without duplicates.
  - `NowPlayingScreen` shows `∞ Autoplay` in queue stats, an inline **Autoplay Similar Songs** toggle (`now_playing_autoplay_switch`), a `"Suggested for you · Autoplay ∞"` section divider, and a `"Load more suggestions ∞"` button (`now_playing_load_more_btn`). `YtMusicScreen` auto-paginates near list end with a `"Load more tracks ∞"` button (`yt_music_load_more_btn`) and clickable `"Autoplay ∞"` chip (`discover_autoplay_chip`).
- **Telegram-Music-Addon + BitChord Embedded Audio Metadata (`AudioMetadataExtractor`, `MediaIndex` v2, `LocalLosslessMatcher`):**
  - Pure-Kotlin header-only parser reads FLAC (`STREAMINFO` + `VORBIS_COMMENT`), ID3v2.3/v2.4 (`TIT2`/`TPE1`/`TPE2`/`TALB`/`TLEN`/`USLT`), MP4/ALAC (`mvhd`, `©nam`/`©ART`/`aART`/`©alb`/`©lyr`), WAV (`fmt `, `LIST/INFO`), and sidecar `.lrc` files, with `MediaMetadataRetriever` fallback.
  - Host `MediaIndex` SQLite v2 persists `title`, `artist`, `album`, `duration_ms`, `sample_rate`, `bit_depth`, and `has_lyrics`; `NativeApiServer` exposes them in `GET /api/index/media` and serves `GET /api/media/lyrics?path=...&volume=...`.
  - `LocalLosslessMatcher` paginates host music in safe 200-item pages (fixing HTTP 400), scores Title + Multi-Artist + Album + Duration (`>45s` guard) + Hi-Res tie-breaker, and formats badges like `"LOCAL FLAC · 24-bit/96kHz"` or `"HOST FLAC · LOSSLESS"`.
  - **3-Stage Lyrics Detection (`PocketLinkViewModel.resolveLyricsForTrack`):** (1) Local embedded tag / sidecar `.lrc`, (2) Host `/api/media/lyrics`, (3) Metadata-scored LRCLIB `/api/get` & `/api/search` (`selectBestLrclibCandidate`).
- **Diagnostic Log Generator & Live Tier Probe (`DiagnosticLogGenerator`, `DiagnosticsScreen`):** Reports device/network state, Discover quality, live ExoPlayer decoded audio format, full `StreamResolutionTrace`, lossless metadata counts, active lyrics source, Host/Cloudflare Tunnel telemetry, and `AppLogger` entries.

### 3.9 Cloudflare Tunnel Host Setup & Client Connect Link (`io.github.augztn.pocketlink.ssdbridge.tunnel`)
- **Extensible Tunnel Provider Architecture (`TunnelProviderId`, `TunnelProviderRegistry`, `TunnelProviderAdapter`, `TunnelManager`):** Registers `CLOUDFLARE` with AndroidKeyStore AES-256-GCM secret storage.
- **Two Real Cloudflare Modes (100% Play Policy compliant — zero Dynamic Code Loading):**
  - **Built-in Edge Tunnel (`EDGE_BRIDGE`):** Pure-Kotlin OkHttp WSS reverse proxy to a user-deployed Cloudflare Worker + Durable Object (`CloudflareWorkerTemplate`).
  - **Cloudflare Zero Trust (`ZERO_TRUST`):** Validates `eyJ…` Named Tunnel tokens and runs live HTTPS `/health` + `CF-Ray` verification.
- **`mobile-server`-Style Quick ON/OFF & Client Connect Link (`CloudflareTunnelCard`, `ConnectionScreen`, `TunnelModels`):**
  - Master one-tap ON/OFF `Switch` on both Host Setup (`cloudflare_quick_switch`) and Connection Screen (`connection_cf_quick_switch`), with collapsible configuration (`cloudflare_config_toggle`) when active.
  - One-tap **Copy / Share Client Connect Link** (`cloudflare_share_connect_btn`) generating `pocketlink://connect?url=...&user=...&name=...`, paired with **Paste Link / URL** smart auto-fill (`connection_paste_link_btn`) that auto-selects Port `443` for HTTPS/Cloudflare URLs.

---

## 4. Warning Fixes & Logcat Diagnostics Reference

### Fixed in App Code
1. **`OnBackInvokedCallback` / Predictive Back (`AndroidManifest.xml`, `MainActivity.kt`, `PocketLinkApp.kt`, `NowPlayingScreen.kt`):** Explicit `android:enableOnBackInvokedCallback="true"`, `PRIORITY_SYSTEM_NAVIGATION_OBSERVER` (API 36+), and `PredictiveBackHandler` for overlays/Now Playing.
2. **Media3 `MediaSessionService` & AudioFocus (`AndroidManifest.xml`, `MediaPlaybackService.kt`, `AudioPlaybackController.kt`, `VideoPlayerScreen.kt`):** Single-owner `AudioAttributes(..., true)` and tuned `DefaultAudioSink` / `DefaultLoadControl` buffers.
3. **Resource & Bitmap Lifecycle (`NativeApiServer.kt`, `MediaIndex.kt`, `PocketLinkApi.kt`, `PocketLinkViewModel.kt`, `MediaBrowserScreen.kt`, `VideoPlayerScreen.kt`):** Closed executors/DB on stop, pooled `OkHttpClient`, bounded Coil memory cache (`16%`), and detached `PlayerView.player = null` in `onRelease`.

### Known System / Emulator HAL Warnings (Not App Bugs)
- `Codec2Client` / `CCodec`, `ColorUtils`, `LoudnessCodecController` — MediaCodec / HAL pipeline logs.
- `AudioTrack->getLatency()` & `AudioTrack: retrograde timestamp time corrected` — emulator AudioTrack clock jitter logs.
- `HWUI: Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED` — emulator OpenGL surface fallback.
- `WindowOnBackDispatcher: sendCancelIfRunning: isInProgress=false` — AOSP dispatcher log when root `BackHandler` callbacks unregister.

---

## 5. Preserved Compose `testTag` Identifiers (Do Not Break)

- Home & Drawer: `open_drawer_menu_btn`, `drawer_quick_music`, `drawer_quick_yt_music`, `drawer_quick_videos`, `drawer_quick_photos`, `drawer_quick_docs`, `drawer_item_internal`, `drawer_item_usb`, `drawer_item_host`, `drawer_item_search`, `drawer_item_transfers`, `drawer_item_switch_server`, `drawer_item_settings`, `dashboard_transfers_btn`, `dashboard_refresh_btn`, `dashboard_settings_btn`, `dashboard_search_bar`.
- Connection & Settings: `connection_yt_music_card`, `connection_settings_btn`, `connection_paste_link_btn`, `connection_cf_quick_switch`, `cloudflare_active_banner`, `settings_cat_yt_streaming`, `yt_streaming_master_switch`, `yt_streaming_reset_btn`, `yt_quality_auto`, `yt_quality_high`, `yt_quality_medium`, `yt_quality_low`.
- Host Setup & Cloudflare Tunnel: `cloudflare_tunnel_card`, `cloudflare_quick_switch`, `cloudflare_config_toggle`, `cloudflare_share_connect_btn`, `cloudflare_tunnel_status_badge`, `cloudflare_tunnel_toggle_btn`, `cloudflare_tunnel_verify_btn`, `cloudflare_copy_url_btn`, `cf_mode_edge_bridge`, `cf_mode_zero_trust`, `cf_worker_url_input`, `cf_worker_secret_input`, `cf_generate_secret_btn`, `cf_copy_worker_script_btn`, `cf_zerotrust_url_input`, `cf_zerotrust_token_input`, `cf_auto_start_switch`, `cloudflare_tunnel_logs_toggle`.
- Discover Screen: `yt_music_search_input`, `yt_music_enable_btn`, `yt_music_settings_btn`, `yt_music_diagnostics_btn`, `discover_quality_<prefKey>`, `discover_autoplay_chip`, `yt_music_load_more_btn`, `discover_local_lossless_card`, `discover_local_lossless_switch`, `discover_local_lossless_refresh_btn`.
- Diagnostics & Log Generator: `log_generator_card`, `generate_log_report_btn`, `run_stream_probe_btn`, `copy_log_report_btn`, `export_log_file_btn`, `share_log_report_btn`, `generated_log_preview`.
- Navigation & Mini Player: `nav_tab_<id>`, `mini_player_bar`, `mini_player_play_pause`, `mini_player_next`, `mini_player_dismiss`, `music_menu_shortcut_<id>`.
- Now Playing: `now_playing_minimize_btn`, `now_playing_shortcuts_btn`, `now_playing_play_pause_btn`, `now_playing_shortcut_<id>`, `now_playing_lyrics_btn`, `now_playing_lyrics_pane`, `now_playing_quality_btn`, `now_playing_quality_<prefKey>`, `now_playing_autoplay_switch`, `now_playing_load_more_btn`.
