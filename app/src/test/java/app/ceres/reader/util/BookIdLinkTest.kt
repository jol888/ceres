package app.ceres.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookIdLinkTest {
    @Test
    fun shareText() {
        assertEquals(
            "7276384138653862966",
            BookIdLink.parse(
                "我在番茄小说看《某书》，快来一起看吧！https://changdunovel.com/wap/share-v2.html?aid=1967" +
                    "&book_id=7276384138653862966&share_type=0&share_code=abc&user_id=123456789&entrance=reader",
            ),
        )
        assertEquals("7143038691944959011", BookIdLink.parse("https://x.cn/s?bookId=7143038691944959011"))
    }

    @Test
    fun pagesAndBareIds() {
        assertEquals("7143038691944959011", BookIdLink.parse("https://fanqienovel.com/page/7143038691944959011?enter_from=x"))
        assertEquals("7143038691944959011", BookIdLink.parse("fanqie://book/7143038691944959011"))
        assertEquals("7143038691944959011", BookIdLink.parse("  7143038691944959011\n"))
    }

    @Test
    fun rejectsOthers() {
        assertNull(BookIdLink.parse(null))
        assertNull(BookIdLink.parse("《某书》第 12345678 章"))
        assertNull(BookIdLink.parse("https://fanqienovel.com/reader/7143038691944959011"))
        assertNull(BookIdLink.parse("https://a.com/?book_id=12"))
    }
}
