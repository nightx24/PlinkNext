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
| **Media Stack** | AndroidX Media3 (`ExoPlayer`) + `OkHttpDataSource` + `MediaPlaybackService` (`MediaSessionCompat`) |
| **Build Command** | `gradle assembleDebug` (or `compile_applet`) — **never** use `./gradlew` |
| **Test Command** | `gradle :app:testDebugUnitTest --console=plain` (Robolectric + JVM unit tests; never use `adb`/Espresso) |

---

## 2. Package & File Map

- **`app/src/main/java/com/example/`**
  - `MainActivity.kt` — Entry point (`enableEdgeToEdge()`), permission requests, mounts `PocketLinkApp`.
  - **`data/model/`**
    - `PocketLinkModels.kt` — `AppThemeMode`, `NetworkRouteType`, `IndexedFile`, `HostSystemTelemetry`, `StorageVolume`, `TransferItem`, `ServerProfile`, `AppSettingsState`.
  - **`data/network/`**
    - `PocketLinkApi.kt` — HTTP client (`sharedBaseClient` OkHttp pool + `.newBuilder()`), auth headers, REST endpoints, media stream URLs.
  - **`data/store/`**
    - `SettingsRepository.kt`, `AppLogger.kt` — DataStore settings persistence and diagnostics buffer.
  - **`media/`**
    - `AudioPlaybackController.kt` — Singleton audio ExoPlayer + queue state + `MediaPlaybackService` notification sync.
    - `TransferManager.kt` — Upload/download queue.
  - **`ui/`**
    - `PocketLinkApp.kt` — Root shell (`PocketLinkApp`), `OverlayRoute` backstack, `MainConnectedShell`, outer `ModalNavigationDrawer`, and `NowPlaying` container-transform overlay.
    - `PocketLinkViewModel.kt` — State holder, bounded Coil `ImageLoader` (`MemoryCache` 16%, `respectCacheHeaders(false)`), clean shutdown in `onCleared()`.
    - **`theme/Theme.kt`** — Light, Dark, AMOLED color schemes and `LocalPocketLinkColors` / `LocalPocketLinkDarkTheme`.
    - **`components/PocketLinkComponents.kt`** — `LiquidGlassPill`, `liquidBackdropBlur`, `FloatingDockScrimBackdrop`, `FloatingNavigationDock`, `MusicQuickAccessMenu`, `ShortcutCatalog`, `PersistentMiniPlayer`, `AnimatedEqualizer`.
    - **`screens/`**
      - `HomeDashboardScreen.kt` & `HomeNavigationDrawerSheet` — Dashboard cards + hoisted 3-bar drawer content.
      - `ExploreScreen.kt`, `FileManagerScreen.kt`, `DocumentsBrowserScreen.kt`, `MediaBrowserScreen.kt`, `PhotoGalleryScreen.kt`, `SearchScreen.kt`
      - `NowPlayingScreen.kt` — Apple Music–inspired full audio player.
      - `VideoPlayerScreen.kt` — Media3 ExoPlayer video player with customizable overlay/gestures/fit/speed.
      - `SettingsScreen.kt` & `SettingsDetailScreens.kt` — Theme, Dock & Shortcuts, Video Player, Navigation, Audio, and Storage settings.
      - `ConnectionScreen.kt`, `LocalStorageSetupScreen.kt`, `TransferManagerScreen.kt`, `DiagnosticsScreen.kt`, `AboutScreen.kt`.
- **`app/src/main/java/io/github/augztn/pocketlink/ssdbridge/`**
  - `BridgeService.kt`, `NativeApiServer.kt` — Embedded HTTP host server (closes `mediaIndex` in `stop()`).
  - `MediaIndex.kt` — SQLite + `MediaIndexScanner` (provides `close()` to shut down executor & DB cleanly).
  - `MediaPlaybackService.kt` — Foreground media service wired with explicit `MediaButtonReceiver`.

---

## 3. Detailed Summary of Recent Changes

### 3.1 Apple Music–Inspired `NowPlayingScreen`
- **Atmospheric Color-Mesh Backdrop:** Deterministic multi-stop palette selected per track (`AppleMusicPalettes`), cross-faded with `animateColorAsState(tween(700))` and layered via custom `Canvas` radial glows + subtle dark gradient scrim.
- **Minimal Top Chrome:** Top drag grabber bar (`38.dp × 5.dp`), left frosted minimize chevron (`testTag("now_playing_minimize_btn")`), uppercase folder label, and right frosted **Quick Destinations** grid button (`testTag("now_playing_shortcuts_btn")`).
- **Play/Pause Artwork Scale Physics:** Square artwork scales smoothly (`1.0f` playing vs `0.87f` paused) with spring physics (`dampingRatio = 0.72f`) and animated elevation shadow (`28.dp` vs `8.dp`). Supports horizontal swipe-to-skip (`artworkOffsetX`) and vertical swipe-to-dismiss (`dismissOffsetY`, scaling down subtly with corner rounding during drag).
- **Left-Aligned Track Header & Inline Speed Pill:** Marquee-scrolling title (`basicMarquee()`), stream/folder subtitle, and a right frosted speed badge (`1.0×`) that expands/collapses a translucent `LazyRow` speed selector (`0.75×`–`2.0×`).
- **Thumb-Free Capsule Scrubber (`AppleMusicScrubber`):** Interactive `6.dp` capsule progress bar that expands to `10.dp` while dragging/tapping, paired with tabular (`fontFeatureSettings = "tnum"`) elapsed/remaining timestamps and a stream format pill (`FLAC`, `MP3`, etc.).
- **Pure White Transport & Bottom Utility Row:** Clean white Previous / Play-Pause (`testTag("now_playing_play_pause_btn")`) / Next controls without a filled FAB, above Shuffle, Repeat (`OFF`/`ALL`/`ONE`), and **Playing Next** queue sheet (`AutoMirrored.Rounded.QueueMusic`), plus dark-frosted `ModalBottomSheet` dialogs.

### 3.2 Upgraded Opening & Closing Animations (`PocketLinkApp.kt`)
- **True Bounding-Box Container Transform for Now Playing:**
  - Tracks live root coordinates via `onGloballyPositioned { coords -> ... boundsInRoot() }` from both `PersistentMiniPlayer` (`miniPlayerBounds`) and the dock's Music pill (`musicButtonBounds`).
  - **Target Anchor Selection:**
    - If `settings.showMiniPlayer && (!miniPlayerDismissed || audioState.isPlaying) && audioState.currentTrack != null && miniPlayerBounds != null` → expands from and collapses directly back into `miniPlayerBounds`.
    - Otherwise → expands from and collapses directly back into `musicButtonBounds`.
  - **Aspect-Correct Clipping Container (Zero Squashing):** Instead of distorting `NowPlayingScreen` via uneven `scaleX`/`scaleY`, `PocketLinkApp` interpolates the clipped container box (`curLeft`, `curTop`, `curWidthPx`, `curHeightPx`, `curCornerDp`) between `targetAnchor` and full viewport bounds while keeping inner `NowPlayingScreen` scaling uniform (`lerp(0.94f/0.76f, 1f, p)`) and smoothly faded.
  - **Hardware-Accelerated Background Depth Recede:** Replaced costly per-frame full-screen `RenderEffect` blur during morphing with GPU-composited background scale-back (`lerp(1f, 0.935f, p)`), rounded frame (`24.dp * p`), and dark dim scrim (`0.48f * p`).
  - **Standard Overlays:** Emphasized spring entry (`dampingRatio = 0.90f, stiffness = 460f`) and `FastOutLinearInEasing` exit (`210ms`) for all `OverlayRoute` sheets.

### 3.3 3-Bar Navigation Drawer Layering Fix
- Extracted `HomeNavigationDrawerSheet` from `HomeDashboardScreen.kt` and hoisted `ModalNavigationDrawer` cleanly above `MainConnectedShell` in `PocketLinkApp.kt`.
- **Why:** Previously `ModalNavigationDrawer` was inside `tabContent`, causing `FloatingDockScrimBackdrop`, `PersistentMiniPlayer`, and `FloatingNavigationDock` to paint on top of the open drawer.
- **Now:** Opening the 3-bar drawer renders the scrim and drawer cleanly **above** the floating dock and mini player, includes `.statusBarsPadding().navigationBarsPadding()`, and restricts swipe-to-open gestures to `selectedTab == MainTab.HOME && overlayStackEmpty && !dockQuickMenuExpanded`.

### 3.4 Floating Shortcut FAB Removal & Music/NowPlaying Access
- Removed `FloatingQuickShortcutFab` and `FloatingShortcutChipsStack` from `PocketLinkComponents.kt`, `PocketLinkApp.kt`, and UI toggles in `SettingsScreen.kt` / `SettingsDetailScreens.kt`.
- Legacy fields `floatingShortcutEnabled` and `floatingShortcutTapAction` remain unused in `AppSettingsState` only for backward compatibility with existing DataStore entries.
- Users access all customizable shortcuts (`Explore`, `Files`, `Music`, `Videos`, `Photos`, `Documents`, `Favorites`, `Upload`) via:
  1. Long-pressing the **Music** pill in `FloatingNavigationDock` (`MusicQuickAccessMenu`).
  2. Tapping the **Quick Destinations** (`testTag("now_playing_shortcuts_btn")`) button inside `NowPlayingScreen`.

### 3.5 Liquid Glass Readability & Blur
- Added `Modifier.liquidBackdropBlur(radius)` (API 31+ `Modifier.blur`) to blur main tab content behind the expanded dock quick-access menu (`dockQuickMenuExpanded`).
- Added `FloatingDockScrimBackdrop` beneath the floating dock and mini player so scrolling content dissolves gently instead of clashing behind translucent glass.
- Increased `LiquidGlassPill` frosted surface opacity in both light and dark modes for clear contrast.

### 3.6 Video Player Customization & Settings
- Enhanced `VideoPlayerScreen.kt` with ExoPlayer controls, brightness/volume/seek gestures, pinch-to-zoom, aspect fit modes (`FIT`, `FILL`, `ZOOM`), customizable auto-hide timeout, seek increments, default playback speed, tap-to-toggle playback, and auto-fullscreen—persisted via `SettingsRepository.updateVideoOptions(...)` and configurable from both the in-player customize sheet and `SettingsDetailScreens.kt`.

---

## 4. Warning Fixes & Logcat Diagnostics Reference

### Fixed in App Code
1. **`OnBackInvokedCallback` / Predictive Back (`AndroidManifest.xml` & `PocketLinkApp.kt`):**
   - Set `android:enableOnBackInvokedCallback="true"` on `<application>`.
   - Consolidated overlapping overlay/NowPlaying `BackHandler` instances in `PocketLinkApp` into a single stable `BackHandler` plus explicit `BackHandler` guards in `VideoPlayerScreen`, `NowPlayingScreen` sheets, and `MainConnectedShell` drawer/tab root.
2. **`MediaSessionCompat` Media Button Receiver (`AndroidManifest.xml` & `MediaPlaybackService.kt`):**
   - Registered `<receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">` and handled `ACTION_MEDIA_BUTTON` cleanly in `MediaPlaybackService`.
3. **Resource & Bitmap Lifecycle (`NativeApiServer.kt`, `MediaIndex.kt`, `PocketLinkApi.kt`, `PocketLinkViewModel.kt`, `MediaBrowserScreen.kt`, `VideoPlayerScreen.kt`):**
   - Closed `MediaIndexScanner` executor/SQLite DB on `NativeApiServer.stop()`.
   - Reused a shared base `OkHttpClient` (`sharedBaseClient.newBuilder()`).
   - Bounded Coil `ImageLoader` memory cache (`16%`), shut down stale loaders cleanly, sized video thumbnails (`360px`–`480px`), and detached `PlayerView.player = null` in `onRelease`.

### Known System / Emulator HAL Warnings (Not App Bugs)
- `Codec2Client` / `CCodec` (`query -- param skipped`, `max input size`), `ColorUtils`, `LoudnessCodecController` — emitted by Android's MediaCodec / HAL pipeline on hardware/emulator decoders.
- `AudioTrack->getLatency()` & `AudioTrack: retrograde timestamp time corrected` — internal ExoPlayer/Android AudioTrack emulator clock jitter logs.
- `HWUI: Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED` — Android emulator OpenGL surface fallback.
- `WindowOnBackDispatcher: sendCancelIfRunning: isInProgress=false` — emitted by Android 14+ AOSP `WindowOnBackDispatcher` whenever `androidx.activity.OnBackPressedDispatcher` disables/unregisters the last enabled `BackHandler` callback upon returning to the root screen or dismissing Compose bottom sheets.

---

## 5. Preserved Compose `testTag` Identifiers (Do Not Break)

- Home & Drawer: `open_drawer_menu_btn`, `drawer_quick_music`, `drawer_quick_videos`, `drawer_quick_photos`, `drawer_quick_docs`, `drawer_item_internal`, `drawer_item_usb`, `drawer_item_host`, `drawer_item_search`, `drawer_item_transfers`, `drawer_item_switch_server`, `drawer_item_settings`, `dashboard_transfers_btn`, `dashboard_refresh_btn`, `dashboard_settings_btn`, `dashboard_search_bar`.
- Navigation & Mini Player: `nav_tab_<id>`, `mini_player_bar`, `mini_player_play_pause`, `mini_player_next`, `mini_player_dismiss`, `music_menu_shortcut_<id>`.
- Now Playing: `now_playing_minimize_btn`, `now_playing_shortcuts_btn`, `now_playing_play_pause_btn`, `now_playing_shortcut_<id>`.
