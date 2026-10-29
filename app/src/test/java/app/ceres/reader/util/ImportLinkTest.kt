package app.ceres.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLinkTest {
    @Test
    fun legadoLinks() {
        assertEquals(
            ImportLink("bookSource", "https://a.com/s.json?x=1&y=2"),
            ImportLink.parse("legado://import/bookSource?src=https%3A%2F%2Fa.com%2Fs.json%3Fx%3D1%26y%3D2"),
        )
        assertEquals(
            ImportLink("bookSource", "https://a.com/s.json?x=1&y=2"),
            ImportLink.parse("legado://import/bookSource?src=https://a.com/s.json?x=1&y=2"),
        )
        assertEquals("rssSource", ImportLink.parse("legado://import/rssSource?src=http://a.com/r")?.type)
    }

    @Test
    fun yueduLinks() {
        val link = ImportLink.parse("yuedu://booksource/importonline?src=http://b.cn/x.json")!!
        assertEquals(true, link.isBookSource)
        assertEquals("http://b.cn/x.json", link.src)
    }

    @Test
    fun rejectsOthers() {
        assertNull(ImportLink.parse("https://a.com/?src=http://x"))
        assertNull(ImportLink.parse("legado://import/bookSource"))
        assertNull(ImportLink.parse("legado://import/bookSource?src=file:///etc/passwd"))
    }
}
