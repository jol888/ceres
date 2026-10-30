package app.ceres.reader.source.fanqie

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.source.ReviewLoader
import app.ceres.reader.source.WebBook
import app.ceres.reader.util.parseBookSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 真实网络联调：搜索 → 详情 → 目录 → 正文 → 段评统计/列表/回复。
 *
 * 默认跳过，避免 CI 依赖外网。手动运行：
 * `CERES_LIVE=1 ./gradlew :app:testDebugUnitTest --tests 'app.ceres.reader.source.fanqie.FanqieLiveTest'`
 */
class FanqieLiveTest {

    private val live = System.getenv("CERES_LIVE") == "1"

    /** 书源 JSON 单独开源；这里按环境变量、本仓库、相邻仓库的顺序查找。 */
    private val sourceFile: File? by lazy {
        buildList {
            System.getenv("CERES_FANQIE_SOURCE")?.let { add(File(it)) }
            add(File("fanqie_novel_source.json"))
            add(File("../fanqie_novel_source.json"))
            add(File("../fanqie-legado-source/fanqie_novel_source.json"))
            add(File("../../fanqie-legado-source/fanqie_novel_source.json"))
        }.firstOrNull { it.isFile }
    }

    private val source: BookSource by lazy {
        val file = requireNotNull(sourceFile)
        parseBookSources(file.readText()).also { assertEquals("书源条数", 1, it.size) }.first()
    }

    @Before
    fun requireLive() {
        assumeTrue("设置 CERES_LIVE=1 才执行真实网络测试", live)
        assumeTrue(
            "找不到 fanqie_novel_source.json（可用 CERES_FANQIE_SOURCE 指定）",
            sourceFile != null,
        )
    }

    @Test
    fun searchReturnsFanqieBooks() {
        val results = WebBook.search(source, "十日终焉", 1)
        println("搜索命中 ${results.size} 本: " + results.take(3).joinToString { "${it.name}<${it.bookUrl}>" })
        assertTrue("搜索结果为空", results.isNotEmpty())
        results.forEach {
            assertNotNull("bookUrl 解析不出书籍 id: ${it.bookUrl}", FanqieUrls.bookIdOf(it.bookUrl))
            assertTrue("书名缺失", it.name.isNotBlank())
        }
        val hit = results.firstOrNull { it.name == "十日终焉" }
        assertNotNull("搜索结果中没有《十日终焉》: ${results.map { it.name }}", hit)
        assertEquals(FanqieUrls.book(BOOK_ID), hit!!.bookUrl)
        assertFalse("简介缺失", hit.intro.isNullOrBlank())
    }

    @Test
    fun fullReadingFlowAndParagraphComments() {
        val book = Book(
            bookUrl = FanqieUrls.book(BOOK_ID),
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
        )

        // 详情
        WebBook.getBookInfo(source, book)
        println("详情: ${book.name} / ${book.author} / ${book.wordCount} / ${book.tocUrl}")
        assertEquals("十日终焉", book.name)
        assertEquals("杀虫队队员", book.author)
        assertEquals(FanqieUrls.toc(BOOK_ID), book.tocUrl)
        assertFalse("分类为空", book.kind.isNullOrBlank())
        assertFalse("简介为空", book.intro.isNullOrBlank())

        // 目录
        val chapters = WebBook.getChapterList(source, book)
        println("目录 ${chapters.size} 章，首章《${chapters.first().title}》，末章《${chapters.last().title}》")
        assertTrue("目录章节过少: ${chapters.size}", chapters.size > 100)
        chapters.forEach {
            assertTrue("章节地址不是 fanqie://chapter: ${it.url}", it.url.startsWith("fanqie://chapter/$BOOK_ID/"))
        }

        // 正文
        val chapter = chapters.first { !it.isVip && !it.isPay }
        val next = chapters.getOrNull(chapter.index + 1)?.url
        val paragraphs = WebBook.getContent(source, book, chapter, next)
        val text = paragraphs.joinToString("\n")
        println("正文《${chapter.title}》${chapter.url} ${paragraphs.size} 段 / ${text.length} 字: ${text.take(80)}…")
        assertTrue("正文过短: ${text.length} 字", text.length > 200)
        assertFalse("正文里仍带 HTML 标签", text.contains("<p>"))
        assertFalse("正文疑似未解密", text.contains("crypt_status"))
        assertTrue("正文首段疑似章节标题，标题块未剥掉: ${paragraphs.firstOrNull()}",
            paragraphs.firstOrNull()?.startsWith("一个老旧的钨丝灯") == true)

        // 段评统计
        val summary = ReviewLoader.summary(source, book, chapter)
        println("段评统计 ${summary.counts.size} 段，共 ${summary.counts.values.sum()} 条")
        assertTrue("没有解析到段评统计", summary.counts.isNotEmpty())
        assertTrue("段评下标应从 2 开始（第 1 段只有整章汇总）: ${summary.counts.keys.minOrNull()}",
            summary.counts.keys.all { it >= 2 })
        assertTrue("段评下标超出正文范围: ${summary.counts.keys.maxOrNull()} > ${paragraphs.size}",
            summary.counts.keys.all { it <= paragraphs.size })

        // 段评列表：取评论最多的一段，顺带验证翻页
        val top = summary.counts.entries.maxByOrNull { it.value }!!
        val paraIndex = top.key
        val paraData = summary.keys[paraIndex] ?: paraIndex.toString()
        val firstPage = ReviewLoader.detail(source, book, chapter, paraIndex, paraData, 1, null)
        println("第 $paraIndex 段（${top.value} 条）第 1 页 ${firstPage.items.size} 条，next=${firstPage.nextUrl}")
        assertTrue("段评列表为空", firstPage.items.isNotEmpty())
        assertTrue("段评缺少正文", firstPage.items.all { !it.content.isNullOrBlank() })
        assertTrue("段评缺少昵称", firstPage.items.any { !it.name.isNullOrBlank() })
        assertTrue("段评没有解析出时间", firstPage.items.any { !it.time.isNullOrBlank() })

        val allItems = firstPage.items.toMutableList()
        if (firstPage.nextUrl != null) {
            val secondPage = ReviewLoader.detail(source, book, chapter, paraIndex, paraData, 2, firstPage.nextUrl)
            println("段评第 2 页 ${secondPage.items.size} 条")
            assertTrue("段评第 2 页为空", secondPage.items.isNotEmpty())
            allItems.addAll(secondPage.items)
        }

        // 回复
        val target = allItems.firstOrNull { (it.replyCount ?: 0) > 0 && !it.id.isNullOrBlank() }
        val reviewId = target?.id
            ?: "7246635683459678988" // 之前实测回复数为 2 的评论，仅在动态挑选失败时兜底
        val replies = ReviewLoader.replies(source, book, chapter, paraIndex, paraData, reviewId, 1)
        println("评论 $reviewId 的回复 ${replies.size} 条: " +
            replies.take(2).joinToString { "${it.name}: ${it.content?.take(24)}" })
        assertTrue("回复列表为空", replies.isNotEmpty())
        assertTrue("回复缺少正文", replies.all { !it.content.isNullOrBlank() })
    }

    private companion object {
        const val BOOK_ID = "7143038691944959011"
    }
}
