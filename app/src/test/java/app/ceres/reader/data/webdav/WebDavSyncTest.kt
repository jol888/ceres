package app.ceres.reader.data.webdav

import app.ceres.reader.data.SourceSubscription
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.bookStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSyncTest {
    private val now = System.currentTimeMillis()

    @Test
    fun pickNewerPrefersLaterProgress() {
        val a = Book(bookUrl = "u", name = "A", durChapterIndex = 1, durChapterTime = now - 1000, addTime = now - 10_000)
        val b = Book(bookUrl = "u", name = "B", durChapterIndex = 5, durChapterTime = now, addTime = now - 10_000)
        val m = WebDavSync.pickNewer(a, b)
        assertEquals(5, m.durChapterIndex)
        assertEquals("B", m.name)
    }

    @Test
    fun mergeUnionsShelfAndAppliesTombstone() {
        val local = listOf(
            Book(bookUrl = "keep", name = "Keep", durChapterTime = now - 5000, addTime = now - 20_000, inShelf = true),
            Book(bookUrl = "gone", name = "Gone", durChapterTime = now - 8000, addTime = now - 20_000, inShelf = true),
        )
        val remote = SyncSnapshot(
            books = listOf(
                SyncBook(bookUrl = "keep", name = "KeepRemote", durChapterIndex = 3, durChapterTime = now - 1000, addTime = now - 20_000),
                SyncBook(bookUrl = "cloud", name = "Cloud", durChapterTime = now - 4000, addTime = now - 20_000),
            ),
            removed = listOf(SyncRemoval("gone", removedAt = now - 2000)),
        )
        val merged = WebDavSync.merge(local, emptyList(), remote)
        val urls = merged.books.map { it.bookUrl }.toSet()
        assertTrue(urls.contains("keep"))
        assertTrue(urls.contains("cloud"))
        assertTrue(!urls.contains("gone"))
        assertEquals(3, merged.books.first { it.bookUrl == "keep" }.durChapterIndex)
    }

    @Test
    fun newerProgressBeatsTombstone() {
        val local = listOf(
            Book(bookUrl = "revived", name = "R", durChapterTime = now, addTime = now - 10_000, inShelf = true),
        )
        val remote = SyncSnapshot(
            books = emptyList(),
            removed = listOf(SyncRemoval("revived", removedAt = now - 5000)),
        )
        val merged = WebDavSync.merge(local, emptyList(), remote)
        assertEquals(listOf("revived"), merged.books.map { it.bookUrl })
        assertTrue(merged.removed.none { it.bookUrl == "revived" })
    }

    private fun src(url: String, at: Long, enabled: Boolean = true) =
        SyncSource(url = url, name = url, enabled = enabled, json = "{\"bookSourceUrl\":\"$url\"}", updatedAt = at)

    @Test
    fun mergeSourcesKeepsNewerChangeAndUnionsRest() {
        val local = SourceSnapshot(
            sources = listOf(src("a", now - 1000, enabled = false), src("onlyLocal", now - 1000)),
            subscriptions = listOf(SourceSubscription("https://x/s.json", now - 5000)),
        )
        val remote = SourceSnapshot(
            sources = listOf(src("a", now, enabled = true), src("onlyRemote", now - 3000)),
            subscriptions = listOf(SourceSubscription("https://x/s.json", now - 9000), SourceSubscription("https://y/s.json", now)),
        )
        val merged = WebDavSync.mergeSources(local, remote)
        assertEquals(setOf("a", "onlyLocal", "onlyRemote"), merged.sources.map { it.url }.toSet())
        assertTrue("newer remote toggle should win", merged.sources.first { it.url == "a" }.enabled)
        assertEquals(listOf("https://x/s.json", "https://y/s.json"), merged.subscriptions.map { it.url })
        assertEquals(now - 9000, merged.subscriptions.first().addedAt)
    }

    @Test
    fun sourceTombstoneDeletesOnlyOlderSources() {
        val local = SourceSnapshot(
            sources = listOf(src("reimported", now)),
            removed = listOf(SyncRemoval("deleted", now - 1000)),
        )
        val remote = SourceSnapshot(
            sources = listOf(src("deleted", now - 5000)),
            removed = listOf(SyncRemoval("reimported", now - 2000)),
        )
        val merged = WebDavSync.mergeSources(local, remote)
        assertEquals(listOf("reimported"), merged.sources.map { it.url })
        assertEquals(listOf("deleted"), merged.removed.map { it.bookUrl })
    }

    @Test
    fun bookStatsFormatsScoreAndWords() {
        assertEquals("评分 9.9 · 320.1万字", bookStats("9.9", "320.1万"))
        assertEquals("120万字", bookStats(null, "120万字"))
        assertEquals("评分 8.5", bookStats("8.5", ""))
        assertNull(bookStats("0", null))
    }
}
