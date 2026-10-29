package app.ceres.reader.source

import app.ceres.reader.data.model.Book
import app.ceres.reader.util.parseBookSources
import org.junit.Assume.assumeTrue
import org.junit.Test

class HeavyTocBench {
    @Test
    fun bench() {
        assumeTrue(System.getenv("CERES_BENCH") == "1")
        val html = buildString {
            append("<html><body><div class=nav><a class=next href='/toc'>下页</a></div><ul id=list>")
            repeat(3000) { append("<li><a href='/c/$it.html' data-id='$it'>第${it + 1}章 标题$it</a></li>") }
            append("</ul></body></html>")
        }
        val src = parseBookSources(
            """{"bookSourceUrl":"http://x","bookSourceName":"b",
               "ruleBookInfo":{"name":"tag.h1@text","author":"class.author@text","intro":"id.intro@text","kind":"class.kind@text"},
               "ruleToc":{"chapterList":"id.list@tag.li","chapterName":"tag.a@text##\\s+.*",
               "chapterUrl":"tag.a@data-id@js:'http://x/c/' + result + '.html'","nextTocUrl":"class.next@href"}}"""
        ).single()
        repeat(4) {
            val book = Book(bookUrl = "http://x/toc", origin = "http://x", originName = "b")
            val s = System.nanoTime()
            WebBook.parseBookInfo(src, book, html, "http://x/toc")
            val list = WebBook.getChapterList(src, book.also { it.tocUrl = "http://x/toc" }, html)
            println("[bench] round $it: ${list.size} chapters in %.0f ms".format((System.nanoTime() - s) / 1e6))
        }
    }
}
