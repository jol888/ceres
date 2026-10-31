package app.ceres.reader.data.webdav

import app.ceres.reader.data.model.Book
import org.junit.Assert.assertEquals
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
}
