package app.ceres.reader.source.fanqie

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.rule.Kv
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

    /** 默认用内置书源（assets）；可用环境变量指定别的版本。 */
    private val sourceFile: File? by lazy {
        buildList {
            System.getenv("CERES_FANQIE_SOURCE")?.let { add(File(it)) }
            add(File("src/main/assets/fanqie_novel_source.json"))
            add(File("app/src/main/assets/fanqie_novel_source.json"))
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
        assertFalse("评分为空", book.score.isNullOrBlank())
        assertFalse("字数为空", book.wordCount.isNullOrBlank())

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
        assertTrue("段评下标只能是 0（整章汇总）或从 2 开始的段落: ${summary.counts.keys.sorted().take(5)}",
            summary.counts.keys.all { it == 0 || it >= 2 })
        assertTrue("段评下标超出正文范围: ${summary.counts.keys.maxOrNull()} > ${paragraphs.size}",
            summary.counts.keys.all { it <= paragraphs.size })

        // 段评列表：取评论最多的一段，顺带验证翻页
        val top = summary.counts.entries.filter { it.key >= 2 }.maxByOrNull { it.value }!!
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

    @Test
    fun exploreRanksAndCategories() {
        val kinds = WebBook.exploreKinds(source)
        println("发现 ${kinds.size} 项: " + kinds.take(8).joinToString { it.title })
        assertTrue("缺少分组标题", kinds.any { it.url == null && it.title == "男频" })
        val ranks = kinds.dropWhile { it.title != "榜单" }.drop(1).takeWhile { it.url != null }
        assertEquals(
            listOf("热门榜", "飙升榜", "热搜榜", "潜力榜", "听书榜"),
            ranks.map { it.title },
        )
        val category = kinds.first { it.title == "都市日常" }

        for (rank in ranks) {
            val ranked = WebBook.explore(source, rank.url!!, 1)
            println("${rank.title} ${ranked.size} 本: " + ranked.take(3).joinToString { "${it.name}(${it.score})" })
            assertTrue("${rank.title}为空", ranked.size >= 10)
            assertTrue("${rank.title}缺少评分", ranked.all { !it.score.isNullOrBlank() })
            ranked.forEach { assertNotNull("bookUrl 解析不出书籍 id: ${it.bookUrl}", FanqieUrls.bookIdOf(it.bookUrl)) }
        }

        val page1 = WebBook.explore(source, category.url!!, 1)
        val page2 = WebBook.explore(source, category.url!!, 2)
        println("都市日常 第1页 ${page1.size} 本 / 第2页 ${page2.size} 本: " +
            page1.take(3).joinToString { "${it.name}(${it.score}, ${it.wordCount})" })
        assertTrue("分类第一页为空", page1.isNotEmpty())
        assertTrue("分类翻页没有新书", page2.any { b -> page1.none { it.bookUrl == b.bookUrl } })
        assertTrue("分类字数格式不对: ${page1.map { it.wordCount }}",
            page1.all { it.wordCount.orEmpty().matches(Regex("\\d+(\\.\\d)?万|\\d+")) })
    }

    @Test
    fun batchPrefetchServesChapters() {
        val book = Book(
            bookUrl = FanqieUrls.book(BOOK_ID),
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
        )
        WebBook.getBookInfo(source, book)
        val chapters = WebBook.getChapterList(source, book)
            .filter { !it.isVolume && !it.isVip && !it.isPay }
            .take(40)
        val started = System.currentTimeMillis()
        val stored = FanqieUrls.prefetchChapters(chapters.map { it.url })
        println("批量预取 ${chapters.size} 章，成功 $stored 章，用时 ${System.currentTimeMillis() - started}ms")
        assertEquals("批量接口没有返回全部免费章节", chapters.size, stored)

        val paragraphs = WebBook.getContent(source, book, chapters.first(), chapters[1].url)
        assertTrue("预取后的正文首段不对: ${paragraphs.firstOrNull()}",
            paragraphs.firstOrNull()?.startsWith("一个老旧的钨丝灯") == true)
        chapters.drop(1).forEach {
            val text = WebBook.getContent(source, book, it, null).joinToString("\n")
            assertTrue("《${it.title}》正文过短: ${text.length}", text.length > 200)
        }
    }

    /** 标题块 class 是 `chapterTitle1` 的书：标题也要剥掉，否则段评整体提前一段。 */
    @Test
    fun suffixedChapterTitleClassIsStripped() {
        val book = Book(
            bookUrl = FanqieUrls.book(SUFFIXED_TITLE_BOOK_ID),
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
        )
        WebBook.getBookInfo(source, book)
        val chapter = WebBook.getChapterList(source, book).first { !it.isVolume && !it.isVip && !it.isPay }
        val paragraphs = WebBook.getContent(source, book, chapter, null)
        println("《${book.name}》${chapter.title} 首段: ${paragraphs.firstOrNull()}")
        assertTrue("正文首段仍是章节标题: ${paragraphs.firstOrNull()}",
            paragraphs.firstOrNull()?.startsWith("东京，某处") == true)
    }

    /** 服务端已登记密钥，但本地缓存的密钥是错的：密文解不开，应轮换后直接解开。 */
    @Test
    fun staleLocalKeyIsRotatedOnDecryptFailure() {
        // 先正常读一次，保证服务端已为当前设备登记密钥，也顺带取走可能残留的预取正文。
        FanqieClient.chapter(BOOK_ID, FIRST_ITEM_ID)
        plantStaleKey()

        val html = FanqieClient.chapter(BOOK_ID, FIRST_ITEM_ID)
        assertTrue("轮换密钥后正文仍不对: ${html.take(120)}", html.contains("一个老旧的钨丝灯"))
        assertFalse("解密失败后没有换掉旧密钥", Kv.get(KEY_RECORD).orEmpty().contains(STALE_KEY))
    }

    /** 新设备从没登记过密钥，服务端返回 crypt_status=2 的 "Invalid"：应轮换密钥并重新拉取。 */
    @Test
    fun unregisteredKeyIsRotatedAndRefetched() {
        FanqieClient::class.java.getDeclaredField("deviceCache").apply { isAccessible = true }.set(null, null)
        Kv.put("fanqie_device", null)
        FanqieClient.bookInfo(BOOK_ID) // 只注册设备，不注册密钥
        plantStaleKey()

        val html = FanqieClient.chapter(BOOK_ID, FIRST_ITEM_ID)
        assertTrue("轮换密钥后正文仍不对: ${html.take(120)}", html.contains("一个老旧的钨丝灯"))
        assertFalse("crypt_status=2 后没有换掉旧密钥", Kv.get(KEY_RECORD).orEmpty().contains(STALE_KEY))
    }

    private fun plantStaleKey() {
        val deviceId = requireNotNull(FanqieClient.currentDeviceId())
        Kv.put(KEY_RECORD, """{"key":"$STALE_KEY","deviceId":"$deviceId","ts":${System.currentTimeMillis()}}""")
    }

    private companion object {
        const val FIRST_ITEM_ID = "7173216089122439711"
        const val KEY_RECORD = "fanqie_v1_key"
        const val STALE_KEY = "00000000000000000000000000000000"
        const val BOOK_ID = "7143038691944959011"
        /** 《孤独永恒白洞乐队与重力潮汐锁定》，标题块是 `<h1 class="chapterTitle1">`。 */
        const val SUFFIXED_TITLE_BOOK_ID = "7457847629901220888"
    }
}
