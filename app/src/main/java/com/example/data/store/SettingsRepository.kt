package com.example.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.HomeActivityItem
import com.example.data.model.YtAudioQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.pocketLinkDataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketlink_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.pocketLinkDataStore

    val settingsFlow: Flow<AppSettingsState> = dataStore.data.map { prefs ->
        val themeStr = prefs[KEY_THEME_MODE] ?: "dark"
        val themeMode = when (themeStr) {
            "light" -> AppThemeMode.LIGHT
            "system" -> AppThemeMode.SYSTEM
            else -> AppThemeMode.DARK
        }
        val quickActions = decodeList(prefs[KEY_QUICK_ACTIONS])
            .filter { it in AppSettingsState.ALL_QUICK_ACTIONS }
            .ifEmpty { AppSettingsState.DEFAULT_QUICK_ACTIONS }

        val rawOrder = decodeList(prefs[KEY_HOME_SECTION_ORDER])
        val order = rawOrder
            .filter { it in AppSettingsState.DEFAULT_HOME_SECTION_ORDER }
            .let { list ->
                if (list.size == AppSettingsState.DEFAULT_HOME_SECTION_ORDER.size) list
                else AppSettingsState.DEFAULT_HOME_SECTION_ORDER
            }

        val navOrder = decodeList(prefs[KEY_NAV_ORDER])
            .filter { it in AppSettingsState.DEFAULT_NAVIGATION_ORDER }
            .let { list ->
                val missing = AppSettingsState.DEFAULT_NAVIGATION_ORDER.filterNot { it in list }
                (list + missing).distinct()
            }

        val visibleNav = prefs[KEY_NAV_VISIBLE]
            ?.filter { it in AppSettingsState.DEFAULT_NAVIGATION_ORDER }
            ?.plus("home")
            ?.toSet()
            ?.takeIf { it.size >= 2 }
            ?: AppSettingsState.DEFAULT_NAVIGATION_ORDER.toSet()

        val floatingShortcuts = decodeList(prefs[KEY_FLOATING_SHORTCUTS])
            .filter { it in AppSettingsState.ALL_FLOATING_SHORTCUTS }
            .ifEmpty { AppSettingsState.DEFAULT_FLOATING_SHORTCUTS }

        AppSettingsState(
            themeMode = themeMode,
            amoled = prefs[KEY_AMOLED] ?: false,
            useLiquidGlass = prefs[KEY_LIQUID_GLASS] ?: true,
            navigationOrder = navOrder,
            visibleNavigationItems = visibleNav,
            quickActions = quickActions,
            homeSectionOrder = order,
            showQuickActions = prefs[KEY_SHOW_QUICK_ACTIONS] ?: true,
            showLibrary = prefs[KEY_SHOW_LIBRARY] ?: true,
            showRecentFiles = prefs[KEY_SHOW_RECENT_FILES] ?: true,
            showGlobalSearch = prefs[KEY_SHOW_GLOBAL_SEARCH] ?: true,
            showActiveTransfers = prefs[KEY_SHOW_ACTIVE_TRANSFERS] ?: true,
            showMiniPlayer = prefs[KEY_SHOW_MINI_PLAYER] ?: true,
            enableNowPlayingGestures = prefs[KEY_NOW_PLAYING_GESTURES] ?: true,
            showStatsForNerds = prefs[KEY_STATS_FOR_NERDS] ?: true,
            videoControlsTimeoutSeconds = prefs[KEY_VIDEO_TIMEOUT] ?: 4,
            videoSeekSeconds = prefs[KEY_VIDEO_SEEK] ?: 10,
            videoDefaultSpeed = prefs[KEY_VIDEO_SPEED] ?: 1.0f,
            videoFitMode = prefs[KEY_VIDEO_FIT] ?: "contain",
            videoGesturesEnabled = prefs[KEY_VIDEO_GESTURES] ?: true,
            videoPinchZoomEnabled = prefs[KEY_VIDEO_PINCH] ?: true,
            videoTapToTogglePlayback = prefs[KEY_VIDEO_TAP_TOGGLE] ?: false,
            videoAutoFullscreen = prefs[KEY_VIDEO_AUTO_FS] ?: false,
            ytStreamingEnabled = prefs[KEY_YT_STREAMING_ENABLED] ?: true,
            ytAudioQuality = YtAudioQuality.fromPref(prefs[KEY_YT_AUDIO_QUALITY]),
            ytResolverProfile = prefs[KEY_YT_RESOLVER_PROFILE] ?: "AUTO",
            ytCountryCode = prefs[KEY_YT_COUNTRY_CODE] ?: "US",
            ytLyricsEnabled = prefs[KEY_YT_LYRICS_ENABLED] ?: true,
            ytAutoQueueRelated = prefs[KEY_YT_AUTO_QUEUE] ?: true,
            ytShowArtwork = prefs[KEY_YT_SHOW_ARTWORK] ?: true,
            ytSkipSilence = prefs[KEY_YT_SKIP_SILENCE] ?: false,
            ytLocalLosslessEnabled = prefs[KEY_YT_LOCAL_LOSSLESS] ?: false,
            floatingShortcutEnabled = prefs[KEY_FLOATING_SHORTCUT] ?: false,
            floatingShortcutTapAction = prefs[KEY_FLOATING_TAP_ACTION] ?: "menu",
            floatingShortcutLongPressEnabled = prefs[KEY_FLOATING_LONG_PRESS] ?: true,
            floatingShortcuts = floatingShortcuts,
            photoFavorites = prefs[KEY_PHOTO_FAVORITES] ?: emptySet(),
            homeActivity = decodeActivity(prefs[KEY_HOME_ACTIVITY]),
        )
    }

    suspend fun setThemeMode(mode: AppThemeMode, amoled: Boolean? = null) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = when (mode) {
                AppThemeMode.LIGHT -> "light"
                AppThemeMode.DARK -> "dark"
                AppThemeMode.SYSTEM -> "system"
            }
            if (amoled != null) prefs[KEY_AMOLED] = amoled
        }
    }

    suspend fun setAmoled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AMOLED] = enabled
            if (enabled) prefs[KEY_THEME_MODE] = "dark"
        }
    }

    suspend fun setUseLiquidGlass(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_LIQUID_GLASS] = enabled
        }
    }

    suspend fun setNavigationLayout(order: List<String>, visibleItems: Set<String>) {
        val validOrder = order.filter { it in AppSettingsState.DEFAULT_NAVIGATION_ORDER }
            .let { it + AppSettingsState.DEFAULT_NAVIGATION_ORDER.filterNot { d -> d in it } }
        val validVisible = (visibleItems.filter { it in AppSettingsState.DEFAULT_NAVIGATION_ORDER } + "home").toSet()
        if (validVisible.size < 2) return
        dataStore.edit { prefs ->
            prefs[KEY_NAV_ORDER] = JSONArray(validOrder).toString()
            prefs[KEY_NAV_VISIBLE] = validVisible
        }
    }

    suspend fun resetNavigationLayout() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_NAV_ORDER)
            prefs.remove(KEY_NAV_VISIBLE)
        }
    }

    suspend fun setQuickActions(actions: List<String>) {
        val valid = actions.filter { it in AppSettingsState.ALL_QUICK_ACTIONS }.distinct()
        dataStore.edit { prefs ->
            prefs[KEY_QUICK_ACTIONS] = JSONArray(valid).toString()
        }
    }

    suspend fun setHomeSectionOrder(order: List<String>) {
        if (order.toSet() != AppSettingsState.DEFAULT_HOME_SECTION_ORDER.toSet()) return
        dataStore.edit { prefs ->
            prefs[KEY_HOME_SECTION_ORDER] = JSONArray(order).toString()
        }
    }

    suspend fun updateHomeVisibility(
        quickActions: Boolean? = null,
        library: Boolean? = null,
        recentFiles: Boolean? = null,
        globalSearch: Boolean? = null,
        activeTransfers: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            quickActions?.let { prefs[KEY_SHOW_QUICK_ACTIONS] = it }
            library?.let { prefs[KEY_SHOW_LIBRARY] = it }
            recentFiles?.let { prefs[KEY_SHOW_RECENT_FILES] = it }
            globalSearch?.let { prefs[KEY_SHOW_GLOBAL_SEARCH] = it }
            activeTransfers?.let { prefs[KEY_SHOW_ACTIVE_TRANSFERS] = it }
        }
    }

    suspend fun updateNowPlayingOptions(
        miniPlayer: Boolean? = null,
        gestures: Boolean? = null,
        statsForNerds: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            miniPlayer?.let { prefs[KEY_SHOW_MINI_PLAYER] = it }
            gestures?.let { prefs[KEY_NOW_PLAYING_GESTURES] = it }
            statsForNerds?.let { prefs[KEY_STATS_FOR_NERDS] = it }
        }
    }

    suspend fun resetNowPlayingOptions() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SHOW_MINI_PLAYER)
            prefs.remove(KEY_NOW_PLAYING_GESTURES)
            prefs.remove(KEY_STATS_FOR_NERDS)
        }
    }

    suspend fun updateVideoOptions(
        controlsTimeoutSeconds: Int? = null,
        seekSeconds: Int? = null,
        defaultSpeed: Float? = null,
        fitMode: String? = null,
        gesturesEnabled: Boolean? = null,
        pinchZoomEnabled: Boolean? = null,
        tapToTogglePlayback: Boolean? = null,
        autoFullscreen: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            controlsTimeoutSeconds?.let { prefs[KEY_VIDEO_TIMEOUT] = it }
            seekSeconds?.let { prefs[KEY_VIDEO_SEEK] = it }
            defaultSpeed?.let { prefs[KEY_VIDEO_SPEED] = it }
            fitMode?.let { prefs[KEY_VIDEO_FIT] = it }
            gesturesEnabled?.let { prefs[KEY_VIDEO_GESTURES] = it }
            pinchZoomEnabled?.let { prefs[KEY_VIDEO_PINCH] = it }
            tapToTogglePlayback?.let { prefs[KEY_VIDEO_TAP_TOGGLE] = it }
            autoFullscreen?.let { prefs[KEY_VIDEO_AUTO_FS] = it }
        }
    }

    suspend fun resetVideoOptions() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_VIDEO_TIMEOUT)
            prefs.remove(KEY_VIDEO_SEEK)
            prefs.remove(KEY_VIDEO_SPEED)
            prefs.remove(KEY_VIDEO_FIT)
            prefs.remove(KEY_VIDEO_GESTURES)
            prefs.remove(KEY_VIDEO_PINCH)
            prefs.remove(KEY_VIDEO_TAP_TOGGLE)
            prefs.remove(KEY_VIDEO_AUTO_FS)
        }
    }

    suspend fun updateYtStreamingOptions(
        enabled: Boolean? = null,
        audioQuality: YtAudioQuality? = null,
        resolverProfile: String? = null,
        countryCode: String? = null,
        lyricsEnabled: Boolean? = null,
        autoQueueRelated: Boolean? = null,
        showArtwork: Boolean? = null,
        skipSilence: Boolean? = null,
        localLosslessEnabled: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            enabled?.let { prefs[KEY_YT_STREAMING_ENABLED] = it }
            audioQuality?.let { prefs[KEY_YT_AUDIO_QUALITY] = it.prefKey }
            resolverProfile?.let { prefs[KEY_YT_RESOLVER_PROFILE] = it }
            countryCode?.let { prefs[KEY_YT_COUNTRY_CODE] = it.uppercase() }
            lyricsEnabled?.let { prefs[KEY_YT_LYRICS_ENABLED] = it }
            autoQueueRelated?.let { prefs[KEY_YT_AUTO_QUEUE] = it }
            showArtwork?.let { prefs[KEY_YT_SHOW_ARTWORK] = it }
            skipSilence?.let { prefs[KEY_YT_SKIP_SILENCE] = it }
            localLosslessEnabled?.let { prefs[KEY_YT_LOCAL_LOSSLESS] = it }
        }
    }

    suspend fun resetYtStreamingOptions() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_YT_STREAMING_ENABLED)
            prefs.remove(KEY_YT_AUDIO_QUALITY)
            prefs.remove(KEY_YT_RESOLVER_PROFILE)
            prefs.remove(KEY_YT_COUNTRY_CODE)
            prefs.remove(KEY_YT_LYRICS_ENABLED)
            prefs.remove(KEY_YT_AUTO_QUEUE)
            prefs.remove(KEY_YT_SHOW_ARTWORK)
            prefs.remove(KEY_YT_SKIP_SILENCE)
            prefs.remove(KEY_YT_LOCAL_LOSSLESS)
        }
    }

    suspend fun setFloatingShortcutConfig(
        enabled: Boolean? = null,
        shortcuts: List<String>? = null,
        tapAction: String? = null,
        longPressEnabled: Boolean? = null,
    ) {
        dataStore.edit { prefs ->
            enabled?.let { prefs[KEY_FLOATING_SHORTCUT] = it }
            shortcuts?.let { list ->
                val valid = list.filter { it in AppSettingsState.ALL_FLOATING_SHORTCUTS }.distinct()
                if (valid.isNotEmpty()) prefs[KEY_FLOATING_SHORTCUTS] = JSONArray(valid).toString()
            }
            tapAction?.let { prefs[KEY_FLOATING_TAP_ACTION] = if (it == "primary") "primary" else "menu" }
            longPressEnabled?.let { prefs[KEY_FLOATING_LONG_PRESS] = it }
        }
    }

    suspend fun resetFloatingShortcutConfig() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_FLOATING_SHORTCUTS)
            prefs.remove(KEY_FLOATING_TAP_ACTION)
            prefs.remove(KEY_FLOATING_LONG_PRESS)
        }
    }

    suspend fun togglePhotoFavorite(key: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PHOTO_FAVORITES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(key)) current.remove(key)
            prefs[KEY_PHOTO_FAVORITES] = current
        }
    }

    suspend fun recordActivity(item: HomeActivityItem) {
        dataStore.edit { prefs ->
            val current = decodeActivity(prefs[KEY_HOME_ACTIVITY]).toMutableList()
            current.removeAll { it.path == item.path && it.volume == item.volume && it.serverUrl == item.serverUrl }
            current.add(0, item)
            val trimmed = current.take(20)
            val array = JSONArray()
            trimmed.forEach { a ->
                array.put(
                    JSONObject()
                        .put("kind", a.kind)
                        .put("title", a.title)
                        .put("path", a.path)
                        .put("serverUrl", a.serverUrl)
                        .put("volume", a.volume)
                        .put("updatedAtMs", a.updatedAtMs)
                )
            }
            prefs[KEY_HOME_ACTIVITY] = array.toString()
        }
    }

    private fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun decodeActivity(raw: String?): List<HomeActivityItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path")
                val title = o.optString("title")
                val serverUrl = o.optString("serverUrl")
                if (path.isBlank() || title.isBlank() || serverUrl.isBlank()) continue
                add(
                    HomeActivityItem(
                        kind = o.optString("kind", "file"),
                        title = title,
                        path = path,
                        serverUrl = serverUrl,
                        volume = o.optString("volume").takeIf { it.isNotBlank() && it != "null" },
                        updatedAtMs = o.optLong("updatedAtMs", 0L),
                    )
                )
            }
        }
    }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_AMOLED = booleanPreferencesKey("amoled")
        private val KEY_LIQUID_GLASS = booleanPreferencesKey("use_liquid_glass")
        private val KEY_NAV_ORDER = stringPreferencesKey("nav_order")
        private val KEY_NAV_VISIBLE = stringSetPreferencesKey("nav_visible")
        private val KEY_QUICK_ACTIONS = stringPreferencesKey("quick_actions")
        private val KEY_HOME_SECTION_ORDER = stringPreferencesKey("home_section_order")
        private val KEY_SHOW_QUICK_ACTIONS = booleanPreferencesKey("show_quick_actions")
        private val KEY_SHOW_LIBRARY = booleanPreferencesKey("show_library")
        private val KEY_SHOW_RECENT_FILES = booleanPreferencesKey("show_recent_files")
        private val KEY_SHOW_GLOBAL_SEARCH = booleanPreferencesKey("show_global_search")
        private val KEY_SHOW_ACTIVE_TRANSFERS = booleanPreferencesKey("show_active_transfers")
        private val KEY_SHOW_MINI_PLAYER = booleanPreferencesKey("show_mini_player")
        private val KEY_NOW_PLAYING_GESTURES = booleanPreferencesKey("now_playing_gestures")
        private val KEY_STATS_FOR_NERDS = booleanPreferencesKey("stats_for_nerds")
        private val KEY_VIDEO_TIMEOUT = intPreferencesKey("video_timeout")
        private val KEY_VIDEO_SEEK = intPreferencesKey("video_seek")
        private val KEY_VIDEO_SPEED = floatPreferencesKey("video_speed")
        private val KEY_VIDEO_FIT = stringPreferencesKey("video_fit")
        private val KEY_VIDEO_GESTURES = booleanPreferencesKey("video_gestures")
        private val KEY_VIDEO_PINCH = booleanPreferencesKey("video_pinch")
        private val KEY_VIDEO_TAP_TOGGLE = booleanPreferencesKey("video_tap_toggle")
        private val KEY_VIDEO_AUTO_FS = booleanPreferencesKey("video_auto_fs")
        private val KEY_YT_STREAMING_ENABLED = booleanPreferencesKey("yt_streaming_enabled")
        private val KEY_YT_AUDIO_QUALITY = stringPreferencesKey("yt_audio_quality")
        private val KEY_YT_RESOLVER_PROFILE = stringPreferencesKey("yt_resolver_profile")
        private val KEY_YT_COUNTRY_CODE = stringPreferencesKey("yt_country_code")
        private val KEY_YT_LYRICS_ENABLED = booleanPreferencesKey("yt_lyrics_enabled")
        private val KEY_YT_AUTO_QUEUE = booleanPreferencesKey("yt_auto_queue_related")
        private val KEY_YT_SHOW_ARTWORK = booleanPreferencesKey("yt_show_artwork")
        private val KEY_YT_SKIP_SILENCE = booleanPreferencesKey("yt_skip_silence")
        private val KEY_YT_LOCAL_LOSSLESS = booleanPreferencesKey("yt_local_lossless_enabled")
        private val KEY_FLOATING_SHORTCUT = booleanPreferencesKey("floating_shortcut")
        private val KEY_FLOATING_TAP_ACTION = stringPreferencesKey("floating_tap_action")
        private val KEY_FLOATING_LONG_PRESS = booleanPreferencesKey("floating_long_press")
        private val KEY_FLOATING_SHORTCUTS = stringPreferencesKey("floating_shortcuts")
        private val KEY_PHOTO_FAVORITES = stringSetPreferencesKey("photo_favorites")
        private val KEY_HOME_ACTIVITY = stringPreferencesKey("home_activity")
    }
}
