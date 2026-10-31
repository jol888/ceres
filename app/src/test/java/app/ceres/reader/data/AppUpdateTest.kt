package app.ceres.reader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun versionGtComparesDotSegments() {
        assertTrue(AppUpdate.versionGt("0.1.3", "0.1.2"))
        assertTrue(AppUpdate.versionGt("0.2.0", "0.1.9"))
        assertTrue(AppUpdate.versionGt("1.0.0", "0.9.9"))
        assertFalse(AppUpdate.versionGt("0.1.2", "0.1.2"))
        assertFalse(AppUpdate.versionGt("0.1.1", "0.1.2"))
    }

    @Test
    fun isNewerPrefersVersionCodeThenName() {
        val byCode = AppRelease("v9.9.9", "9.9.9", versionCode = 9999, notes = "", htmlUrl = "", apkUrl = null, apkName = null)
        assertTrue(AppUpdate.isNewer(byCode))
        val olderCode = AppRelease("v0.0.1", "0.0.1", versionCode = 1, notes = "", htmlUrl = "", apkUrl = null, apkName = null)
        assertFalse(AppUpdate.isNewer(olderCode))
    }
}
