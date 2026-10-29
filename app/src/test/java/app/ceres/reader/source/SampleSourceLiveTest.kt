package app.ceres.reader.source

import app.ceres.reader.util.parseBookSources
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real site. Run with `CERES_LIVE=1 ./gradlew testDebugUnitTest --tests '*SampleSourceLiveTest*'`. */
class SampleSourceLiveTest {
    @Test
    fun wikisource() {
        assumeTrue(System.getenv("CERES_LIVE") == "1")
        val source = parseBookSources(File("src/main/assets/sample_sources.json").readText()).first()

        val results = WebBook.search(source, "西游记")
        println("search: " + results.take(5).map { "${it.name} -> ${it.bookUrl}" })
        val hit = results.first { it.name == "西遊記" }

        val book = hit.toBook()
        val body = WebBook.getBookInfo(source, book)
        println("info: author=${book.author} intro=${book.intro?.take(60)}")

        val chapters = WebBook.getChapterList(source, book, body)
        println("toc: ${chapters.size} ${chapters.take(3).map { it.title + " " + it.url }}")
        assertTrue(chapters.size >= 100)

        val paragraphs = WebBook.getContent(source, book, chapters[0], chapters[1].url)
        println("content: ${paragraphs.size} paragraphs\n" + paragraphs.take(3).joinToString("\n"))
        assertTrue(paragraphs.size > 10)
        assertTrue(paragraphs.joinToString("").contains("花果山"))
    }
}
