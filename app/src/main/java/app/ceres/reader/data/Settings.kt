package app.ceres.reader.data

import android.content.Context
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
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _reader = MutableStateFlow(load())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    private fun load(): ReaderSettings {
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
            .apply()
        _reader.value = s
    }
}
