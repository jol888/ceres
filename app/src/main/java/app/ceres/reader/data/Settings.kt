package app.ceres.reader.data

import android.content.Context
import app.ceres.reader.data.webdav.SyncRemoval
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReaderSettings(
    val fontSizeSp: Int = 20,
    val lineSpacing: Float = 1.6f,
    val paragraphSpacingDp: Int = 10,
    val paddingHorizontalDp: Int = 20,
    val paddingVerticalDp: Int = 20,
    val boldText: Boolean = false,
    /** Full e-ink refresh every N page turns; 0 disables. */
    val refreshEvery: Int = 6,
    val volumeKeyTurn: Boolean = true,
    /** Tapping the left zone also goes forward (one-handed mode). */
    val tapLeftNext: Boolean = false,
    val showReviews: Boolean = true,
    val preloadCount: Int = 3,
    val keepScreenOn: Boolean = true,
    /** Swap black/white for night / reverse e-ink reading. */
    val invertColors: Boolean = false,
    /** Show current time in the reader header. */
    val showClock: Boolean = true,
)

data class WebDavSettings(
    val enabled: Boolean = false,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** After reading / shelf changes, push a bidirectional sync. */
    val autoSync: Boolean = false,
    val lastSyncAt: Long = 0,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _reader = MutableStateFlow(loadReader())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    private val _webDav = MutableStateFlow(loadWebDav())
    val webDav: StateFlow<WebDavSettings> = _webDav.asStateFlow()

    private fun loadReader(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = prefs.getInt("fontSizeSp", d.fontSizeSp),
            lineSpacing = prefs.getFloat("lineSpacing", d.lineSpacing),
            paragraphSpacingDp = prefs.getInt("paragraphSpacingDp", d.paragraphSpacingDp),
            paddingHorizontalDp = prefs.getInt("paddingHorizontalDp", d.paddingHorizontalDp),
            paddingVerticalDp = prefs.getInt("paddingVerticalDp", d.paddingVerticalDp),
            boldText = prefs.getBoolean("boldText", d.boldText),
            refreshEvery = prefs.getInt("refreshEvery", d.refreshEvery),
            volumeKeyTurn = prefs.getBoolean("volumeKeyTurn", d.volumeKeyTurn),
            tapLeftNext = prefs.getBoolean("tapLeftNext", d.tapLeftNext),
            showReviews = prefs.getBoolean("showReviews", d.showReviews),
            preloadCount = prefs.getInt("preloadCount", d.preloadCount),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            invertColors = prefs.getBoolean("invertColors", d.invertColors),
            showClock = prefs.getBoolean("showClock", d.showClock),
        )
    }

    private fun loadWebDav(): WebDavSettings {
        val d = WebDavSettings()
        return WebDavSettings(
            enabled = prefs.getBoolean("webdav.enabled", d.enabled),
            url = prefs.getString("webdav.url", d.url).orEmpty(),
            username = prefs.getString("webdav.username", d.username).orEmpty(),
            password = prefs.getString("webdav.password", d.password).orEmpty(),
            autoSync = prefs.getBoolean("webdav.autoSync", d.autoSync),
            lastSyncAt = prefs.getLong("webdav.lastSyncAt", d.lastSyncAt),
        )
    }

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val s = transform(_reader.value)
        prefs.edit()
            .putInt("fontSizeSp", s.fontSizeSp)
            .putFloat("lineSpacing", s.lineSpacing)
            .putInt("paragraphSpacingDp", s.paragraphSpacingDp)
            .putInt("paddingHorizontalDp", s.paddingHorizontalDp)
            .putInt("paddingVerticalDp", s.paddingVerticalDp)
            .putBoolean("boldText", s.boldText)
            .putInt("refreshEvery", s.refreshEvery)
            .putBoolean("volumeKeyTurn", s.volumeKeyTurn)
            .putBoolean("tapLeftNext", s.tapLeftNext)
            .putBoolean("showReviews", s.showReviews)
            .putInt("preloadCount", s.preloadCount)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("invertColors", s.invertColors)
            .putBoolean("showClock", s.showClock)
            .apply()
        _reader.value = s
    }

    fun updateWebDav(transform: (WebDavSettings) -> WebDavSettings) {
        val s = transform(_webDav.value)
        prefs.edit()
            .putBoolean("webdav.enabled", s.enabled)
            .putString("webdav.url", s.url)
            .putString("webdav.username", s.username)
            .putString("webdav.password", s.password)
            .putBoolean("webdav.autoSync", s.autoSync)
            .putLong("webdav.lastSyncAt", s.lastSyncAt)
            .apply()
        _webDav.value = s
    }

    fun touchWebDavSync(at: Long) {
        updateWebDav { it.copy(lastSyncAt = at) }
    }

    fun webDavRemovals(): List<SyncRemoval> {
        val raw = prefs.getString("webdav.removals", null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<SyncRemoval>>(raw, object : TypeToken<List<SyncRemoval>>() {}.type)
        }.getOrNull().orEmpty()
    }

    fun addWebDavRemoval(bookUrl: String, removedAt: Long) {
        val map = webDavRemovals().associate { it.bookUrl to it.removedAt }.toMutableMap()
        map[bookUrl] = maxOf(map[bookUrl] ?: 0L, removedAt)
        prefs.edit().putString(
            "webdav.removals",
            gson.toJson(map.map { SyncRemoval(it.key, it.value) }),
        ).apply()
    }

    fun clearWebDavRemovals(keepUrls: Set<String>) {
        val next = webDavRemovals().filter { it.bookUrl in keepUrls }
        prefs.edit().putString("webdav.removals", gson.toJson(next)).apply()
    }
}
