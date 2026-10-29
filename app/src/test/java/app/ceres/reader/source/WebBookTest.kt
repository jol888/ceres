package app.ceres.reader.source

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.util.parseBookSources
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebBookTest {
    private val server = MockWebServer()
    private lateinit var base: String

    private fun html(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun json(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json").body(body).build()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                return when (url.encodedPath) {
                    "/search" -> if (url.queryParameter("q") == "无") html("") else {
                        assertEquals("红楼", url.queryParameter("q"))
                        html(
                            """<div class="book"><a href="/book/1">红楼梦</a><span class="author">曹雪芹</span><p class="intro">简介一</p></div>
                               <div class="book"><a href="/book/2">红楼续</a><span class="author">无名</span></div>"""
                        )
                    }
                    "/book/1" -> html(
                        """<h1>红楼梦</h1><div class="info"><span class="author">作者：曹雪芹</span></div>
                           <img src="/cover/1.jpg"><div id="intro">第一行<br>第二行</div><a class="toc" href="/book/1/toc">目录</a>"""
                    )
                    "/book/1/toc" -> if (url.queryParameter("p") == "2") {
                        html("""<ul class="chapters"><li><a href="/c/3">第三章 终</a></li></ul>""")
                    } else {
                        html(
                            """<ul class="chapters"><li><a href="/c/1">第一章 开始</a></li><li><a href="/c/2">第二章 继续</a></li></ul>
                               <a class="next" href="/book/1/toc?p=2">下一页</a>"""
                        )
                    }
                    "/rank" -> if (url.queryParameter("p") == "1") {
                        html("""<div class="book"><a href="/book/1">红楼梦</a><span class="author">曹雪芹</span></div>""")
                    } else html("")
                    "/%E4%B9%A6/2" -> html("""<h1>单页书</h1><ul class="chapters"><li><a href="/c/1">第一章</a></li></ul>""")
                    "/c/1" -> html(
                        """<div id="content"><p>第一章 开始</p><p>段一</p><p>段二</p><p>广告：请访问</p></div>
                           <a href="/c/1_2">下一页</a>"""
                    )
                    "/c/1_2" -> html("""<div id="content"><p>段三</p></div>""")
                    "/api/review/summary" -> {
                        assertEquals("1", url.queryParameter("cid"))
                        json("""{"data":[{"idx":1,"count":3},{"idx":3,"count":1},{"idx":2,"count":0}]}""")
                    }
                    "/api/review/detail" -> {
                        val page = url.queryParameter("page")
                        assertEquals("1", url.queryParameter("para"))
                        json(
                            """{"data":{"list":[{"id":"r$page","user":{"name":"U$page","avatar":"/a.png"},
                              "text":"好$page","time":"昨天","likes":5,
                              "replies":[{"id":"x","user":{"name":"U2"},"text":"同意"}]}],
                              "next":${page == "1"}}}"""
                        )
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.close()

    private fun source(): BookSource = parseBookSources(
        """
        {
          "bookSourceUrl": "$base",
          "bookSourceName": "测试站",
          "searchUrl": "/search?q={{key}}&page={{page}}",
          "ruleSearch": {"bookList": "class.book", "name": "tag.a.0@text", "author": "class.author@text",
                         "bookUrl": "tag.a.0@href", "intro": "class.intro@text"},
          "ruleBookInfo": "{\"name\":\"tag.h1@text\",\"author\":\"@CSS:.info .author@text##作者：\",\"intro\":\"id.intro@textNodes\",\"tocUrl\":\"@CSS:a.toc@href\",\"coverUrl\":\"tag.img@src\"}",
          "ruleToc": {"chapterList": "@CSS:ul.chapters > li > a", "chapterName": "text", "chapterUrl": "href",
                      "nextTocUrl": "@CSS:a.next@href"},
          "ruleContent": {"content": "id.content@html", "nextContentUrl": "text.下一页@href", "replaceRegex": "##广告.*"},
          "ruleReview": {
            "enabled": true,
            "reviewSummaryUrl": "/api/review/summary?cid={{chapter.url.split('/').pop()}}",
            "summaryListRule": "$.data[*]",
            "summaryParagraphIndexRule": "$.idx",
            "summaryCountRule": "$.count",
            "reviewDetailUrl": "/api/review/detail?cid={{chapter.url.split('/').pop()}}&para={{paraIndex}}&page={{page}}",
            "reviewDetailNextPageUrl": "@js:JSON.parse(src).data.next ? baseUrl.replace(/page=\\d+/, 'page=' + (Number(page) + 1)) : ''",
            "detailListRule": "$.data.list[*]",
            "detailIdRule": "$.id",
            "detailNameRule": "$.user.name",
            "detailAvatarRule": "$.user.avatar",
            "detailContentRule": "<js>JSON.stringify({text: String(result.text), time: String(result.time), likeCount: result.likes})</js>",
            "replyListRule": "$.replies[*]",
            "replyNameRule": "$.user.name",
            "replyContentRule": "$.text"
          }
        }
        """.trimIndent()
    ).single()

    @Test
    fun fullFlow() {
        val src = source()
        val results = WebBook.search(src, "红楼")
        assertEquals(listOf("红楼梦", "红楼续"), results.map { it.name })
        assertEquals("曹雪芹", results[0].author)
        assertEquals("$base/book/1", results[0].bookUrl)

        val book = results[0].toBook()
        val infoBody = WebBook.getBookInfo(src, book)
        assertEquals("曹雪芹", book.author)
        assertEquals("$base/book/1/toc", book.tocUrl)
        assertEquals("$base/cover/1.jpg", book.coverUrl)
        assertEquals("第一行\n第二行", book.intro)

        val chapters = WebBook.getChapterList(src, book, infoBody)
        assertEquals(listOf("第一章 开始", "第二章 继续", "第三章 终"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals("$base/c/1", chapters[0].url)

        val paragraphs = WebBook.getContent(src, book, chapters[0], chapters[1].url)
        assertEquals(listOf("段一", "段二", "段三"), paragraphs)

        val summary = ReviewLoader.summary(src, book, chapters[0])
        assertEquals(mapOf(1 to 3, 3 to 1), summary.counts)

        val page1 = ReviewLoader.detail(src, book, chapters[0], 1, summary.keys[1]!!, 1, null)
        assertEquals(1, page1.items.size)
        val item = page1.items[0]
        assertEquals("U1", item.name)
        assertEquals("好1", item.content)
        assertEquals("昨天", item.time)
        assertEquals(5, item.likeCount)
        assertEquals("$base/a.png", item.avatar)
        assertEquals("同意", item.replies.single().content)
        assertTrue(page1.nextUrl!!.contains("page=2"))

        val page2 = ReviewLoader.detail(src, book, chapters[0], 1, "1", 2, page1.nextUrl)
        assertEquals("好2", page2.items.single().content)
        assertEquals(null, page2.nextUrl)
    }

    @Test
    fun tocOnInfoPageIsNotRefetched() {
        val src = source()
        val book = Book(bookUrl = "$base/书/2", origin = src.bookSourceUrl, originName = src.bookSourceName)
        val before = server.requestCount
        val body = WebBook.getBookInfo(src, book)
        val chapters = WebBook.getChapterList(src, book, body)
        assertEquals(listOf("第一章"), chapters.map { it.title })
        assertEquals(1, server.requestCount - before)
    }

    @Test
    fun ruleCanReverseToc() {
        val src = source().apply { ruleToc!!.preUpdateJs = "book.setReverseToc(true)" }
        val book = Book(bookUrl = "$base/book/1", tocUrl = "$base/book/1/toc", origin = src.bookSourceUrl)
        val chapters = WebBook.getChapterList(src, book)
        assertEquals(listOf("第三章 终", "第二章 继续", "第一章 开始"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
    }

    @Test
    fun exploreKindsInAllForms() {
        val src = source()
        src.exploreUrl = "榜单::\n总榜::/rank?p={{page}}&&新书::/new"
        assertEquals(
            listOf(ExploreKind("榜单", null), ExploreKind("总榜", "/rank?p={{page}}"), ExploreKind("新书", "/new")),
            WebBook.exploreKinds(src),
        )
        src.exploreUrl = """[{"title":"分类","url":""},{"title":"玄幻","url":"/x/{{page}}","style":{}}]"""
        assertEquals(listOf(ExploreKind("分类", null), ExploreKind("玄幻", "/x/{{page}}")), WebBook.exploreKinds(src))
        src.exploreUrl = "@js:[1, 2].map(i => '第' + i + '页::/p/' + i).join('\\n')"
        assertEquals(listOf(ExploreKind("第1页", "/p/1"), ExploreKind("第2页", "/p/2")), WebBook.exploreKinds(src))
    }

    @Test
    fun exploreFallsBackToSearchRule() {
        val src = source()
        val page1 = WebBook.explore(src, "/rank?p={{page}}", 1)
        assertEquals(listOf("红楼梦"), page1.map { it.name })
        assertEquals("$base/book/1", page1[0].bookUrl)
        assertEquals(emptyList<Any>(), WebBook.explore(src, "/rank?p={{page}}", 2))
    }

    @Test
    fun emptySearchPageIsNoResults() {
        assertEquals(emptyList<Any>(), WebBook.search(source(), "无"))
    }
}
