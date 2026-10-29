package app.ceres.reader.source

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.BookListRule
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.SearchBook
import app.ceres.reader.rule.AnalyzeRule
import app.ceres.reader.rule.AnalyzeUrl
import app.ceres.reader.rule.JsEngine
import app.ceres.reader.rule.RuleLog
import app.ceres.reader.util.formatAuthor
import app.ceres.reader.util.formatBookName
import app.ceres.reader.util.htmlToParagraphs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SourceException(msg: String) : Exception(msg)

/** Blocking source operations; call from a background dispatcher. */
object WebBook {

    fun search(source: BookSource, key: String, page: Int = 1): List<SearchBook> {
        val searchUrl = source.searchUrl?.takeIf { it.isNotBlank() }
            ?: throw SourceException("书源未配置搜索地址")
        val au = AnalyzeUrl(searchUrl, key = key, page = page, baseUrl = source.bookSourceUrl, source = source)
        val res = au.getStrResponse()
        val list = parseBookList(source, res.body().orEmpty(), res.url(), source.ruleSearch, isSearch = true)
        if (list.isEmpty() && res.code() >= 400) throw SourceException("服务器返回 HTTP ${res.code()}")
        return list
    }

    fun parseBookList(
        source: BookSource,
        body: String,
        baseUrl: String,
        rule: BookListRule?,
        isSearch: Boolean,
    ): List<SearchBook> {
        if (body.isBlank()) {
            RuleLog.log("页面为空")
            return emptyList()
        }
        val analyzeRule = AnalyzeRule(null, source).setContent(body, baseUrl)
        val pattern = source.bookUrlPattern?.takeIf { it.isNotBlank() }
        if (pattern != null && runCatching { Regex(pattern).matches(baseUrl) }.getOrDefault(false)) {
            RuleLog.log("链接为详情页，按详情解析")
            return listOfNotNull(parseInfoAsSearch(source, body, baseUrl))
        }
        var listRule = rule?.bookList.orEmpty()
        var reverse = false
        if (listRule.startsWith("-")) {
            reverse = true; listRule = listRule.substring(1)
        } else if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        val elements = if (listRule.isBlank()) emptyList() else analyzeRule.getElements(listRule)
        RuleLog.log("列表数: ${elements.size}")
        if (elements.isEmpty() && pattern == null && isSearch) {
            return listOfNotNull(parseInfoAsSearch(source, body, baseUrl))
        }
        val result = ArrayList<SearchBook>()
        for (item in elements) {
            val sb = SearchBook(origin = source.bookSourceUrl, originName = source.bookSourceName)
            analyzeRule.ruleData = sb
            analyzeRule.setContent(item)
            sb.name = formatBookName(analyzeRule.getString(rule?.name))
            if (sb.name.isEmpty()) continue
            sb.author = formatAuthor(analyzeRule.getString(rule?.author))
            sb.kind = analyzeRule.getStringList(rule?.kind).joinToString(",").ifEmpty { null }
            sb.wordCount = analyzeRule.getString(rule?.wordCount).ifEmpty { null }
            sb.latestChapterTitle = analyzeRule.getString(rule?.lastChapter).ifEmpty { null }
            sb.intro = analyzeRule.getString(rule?.intro).ifEmpty { null }
            sb.coverUrl = rule?.coverUrl?.takeIf { it.isNotBlank() }
                ?.let { analyzeRule.getString(it, isUrl = true) }
            sb.bookUrl = analyzeRule.getString(rule?.bookUrl, isUrl = true).ifEmpty { baseUrl }
            result.add(sb)
        }
        if (reverse) result.reverse()
        return result
    }

    private fun parseInfoAsSearch(source: BookSource, body: String, baseUrl: String): SearchBook? {
        val book = Book(bookUrl = baseUrl, origin = source.bookSourceUrl, originName = source.bookSourceName)
        runCatching { parseBookInfo(source, book, body, baseUrl) }.getOrNull() ?: return null
        if (book.name.isBlank()) return null
        return SearchBook(
            bookUrl = book.bookUrl, origin = book.origin, originName = book.originName,
            name = book.name, author = book.author, kind = book.kind, coverUrl = book.coverUrl,
            intro = book.intro, wordCount = book.wordCount, latestChapterTitle = book.latestChapterTitle,
            tocUrl = book.tocUrl, variable = book.variable,
        )
    }

    /** Fetches and fills book info. Returns the info page body (reused for TOC when tocUrl == bookUrl). */
    fun getBookInfo(source: BookSource, book: Book): String {
        val au = AnalyzeUrl(book.bookUrl, baseUrl = source.bookSourceUrl, source = source, ruleData = book)
        val res = au.getStrResponse()
        parseBookInfo(source, book, res.body().orEmpty(), res.url())
        // Keep the original form (it may carry `,{options}`) so the TOC step can reuse this body.
        if (book.tocUrl == res.url()) book.tocUrl = book.bookUrl
        return res.body().orEmpty()
    }

    fun parseBookInfo(source: BookSource, book: Book, body: String, baseUrl: String) {
        val rule = source.ruleBookInfo
        val ar = AnalyzeRule(book, source).setContent(body, baseUrl)
        rule?.init?.takeIf { it.isNotBlank() }?.let { init ->
            ar.getElement(init)?.let { ar.setContent(it) }
        }
        if (rule == null) {
            if (book.tocUrl.isBlank()) book.tocUrl = book.bookUrl
            return
        }
        val canReName = rule.canReName?.isNotBlank() == true
        formatBookName(ar.getString(rule.name)).takeIf { it.isNotEmpty() }?.let {
            if (book.name.isEmpty() || canReName) book.name = it
        }
        formatAuthor(ar.getString(rule.author)).takeIf { it.isNotEmpty() }?.let {
            if (book.author.isEmpty() || canReName) book.author = it
        }
        ar.getStringList(rule.kind).joinToString(",").takeIf { it.isNotEmpty() }?.let { book.kind = it }
        ar.getString(rule.wordCount).takeIf { it.isNotEmpty() }?.let { book.wordCount = it }
        ar.getString(rule.lastChapter).takeIf { it.isNotEmpty() }?.let { book.latestChapterTitle = it }
        ar.getString(rule.intro).takeIf { it.isNotEmpty() }?.let { book.intro = it }
        rule.coverUrl?.takeIf { it.isNotBlank() }?.let { ar.getString(it, isUrl = true) }
            ?.takeIf { it.isNotEmpty() }?.let { book.coverUrl = it }
        book.tocUrl = rule.tocUrl?.takeIf { it.isNotBlank() }
            ?.let { ar.getString(it, isUrl = true) }?.takeIf { it.isNotEmpty() } ?: baseUrl
    }

    fun getChapterList(source: BookSource, book: Book, cachedBody: String? = null): List<BookChapter> {
        val rule = source.ruleToc ?: throw SourceException("书源未配置目录规则")
        val tocUrl = book.tocUrl.ifBlank { book.bookUrl }
        rule.preUpdateJs?.takeIf { it.isNotBlank() }?.let {
            AnalyzeRule(book, source).evalJS(it)
        }
        val (firstUrl, firstBody) = if (cachedBody != null && sameUrl(tocUrl, book.bookUrl)) {
            tocUrl to cachedBody
        } else {
            val res = AnalyzeUrl(tocUrl, baseUrl = book.bookUrl, source = source, ruleData = book).getStrResponse()
            res.url() to res.body().orEmpty()
        }
        var listRule = rule.chapterList.orEmpty()
        var reverse = false
        if (listRule.startsWith("-")) {
            reverse = true; listRule = listRule.substring(1)
        } else if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        val chapters = ArrayList<BookChapter>()
        val visited = HashSet<String>()
        var pageUrl = firstUrl
        var body = firstBody
        var guard = 0
        while (true) {
            visited.add(pageUrl)
            val (list, nextUrls) = parseTocPage(source, book, rule, listRule, body, pageUrl)
            chapters.addAll(list)
            val next = nextUrls.firstOrNull { it.isNotBlank() && it !in visited } ?: break
            if (++guard > 500) break
            val res = AnalyzeUrl(next, baseUrl = pageUrl, source = source, ruleData = book).getStrResponse()
            pageUrl = res.url()
            body = res.body().orEmpty()
        }
        if (reverse) chapters.reverse()
        if (chapters.isEmpty()) throw SourceException("目录为空")
        val formatJs = rule.formatJs?.takeIf { it.isNotBlank() }
        chapters.forEachIndexed { i, c ->
            c.index = i
            c.bookUrl = book.bookUrl
            if (formatJs != null) {
                runCatching {
                    val ar = AnalyzeRule(book, source)
                    ar.chapter = c
                    val bindings = mapOf("index" to i + 1, "gInt" to 0, "title" to c.title)
                    val r = app.ceres.reader.rule.JsEngine.eval(
                        formatJs,
                        bindings + mapOf("java" to ar, "chapter" to c, "book" to book, "source" to source),
                        source.jsLib,
                    )
                    JsEngine.toStr(r).takeIf { it.isNotBlank() }?.let { c.title = it }
                }
            }
        }
        book.totalChapterNum = chapters.size
        chapters.lastOrNull()?.let { book.latestChapterTitle = it.title }
        return chapters
    }

    private fun parseTocPage(
        source: BookSource,
        book: Book,
        rule: app.ceres.reader.data.model.TocRule,
        listRule: String,
        body: String,
        baseUrl: String,
    ): Pair<List<BookChapter>, List<String>> {
        val ar = AnalyzeRule(book, source).setContent(body, baseUrl)
        val elements = ar.getElements(listRule)
        RuleLog.log("目录页 $baseUrl 章节数: ${elements.size}")
        val nextUrls = rule.nextTocUrl?.takeIf { it.isNotBlank() }
            ?.let { ar.getStringList(it, isUrl = true) }.orEmpty()
            .filter { it != baseUrl }
        val list = ArrayList<BookChapter>()
        for (item in elements) {
            val c = BookChapter(bookUrl = book.bookUrl, baseUrl = baseUrl)
            ar.chapter = c
            ar.setContent(item)
            c.title = ar.getString(rule.chapterName).trim()
            if (c.title.isEmpty()) continue
            c.isVolume = truthy(ar.getString(rule.isVolume))
            c.isVip = truthy(ar.getString(rule.isVip))
            c.isPay = truthy(ar.getString(rule.isPay))
            c.tag = ar.getString(rule.updateTime).ifEmpty { null }
            c.url = if (rule.chapterUrl.isNullOrBlank()) baseUrl else ar.getString(rule.chapterUrl, isUrl = true)
            if (c.url.isBlank()) c.url = if (c.isVolume) "${c.title}${list.size}" else baseUrl
            list.add(c)
        }
        ar.chapter = null
        return list to nextUrls
    }

    /** `a` may be the percent-encoded / redirected form of `b`. */
    private fun sameUrl(a: String, b: String): Boolean {
        if (a == b) return true
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua == ub
    }

    private fun truthy(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.isNotEmpty() && t != "false" && t != "0" && t != "null" && t != "undefined"
    }

    /** Returns cleaned paragraphs of a chapter. */
    fun getContent(source: BookSource, book: Book, chapter: BookChapter, nextChapterUrl: String?): List<String> {
        if (chapter.isVolume && chapter.url.startsWith(chapter.title)) return emptyList()
        val rule = source.ruleContent ?: throw SourceException("书源未配置正文规则")
        val first = AnalyzeUrl(
            chapter.url, baseUrl = chapter.baseUrl.ifBlank { book.bookUrl }, source = source,
            ruleData = book, chapter = chapter,
        ).getStrResponse()
        val sb = StringBuilder()
        val visited = HashSet<String>()
        var pageUrl = first.url()
        var body = first.body().orEmpty()
        var guard = 0
        while (true) {
            visited.add(pageUrl)
            val ar = AnalyzeRule(book, source).setContent(body, pageUrl)
            ar.chapter = chapter
            ar.nextChapterUrl = nextChapterUrl
            val text = ar.getString(rule.content)
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            if (rule.title?.isNotBlank() == true && guard == 0) {
                ar.getString(rule.title).takeIf { it.isNotBlank() }?.let { chapter.title = it }
            }
            val next = rule.nextContentUrl?.takeIf { it.isNotBlank() }
                ?.let { ar.getStringList(it, isUrl = true) }.orEmpty()
                .firstOrNull { it.isNotBlank() && it !in visited && it != nextChapterUrl }
                ?: break
            if (++guard > 50) break
            val res = AnalyzeUrl(next, baseUrl = pageUrl, source = source, ruleData = book, chapter = chapter)
                .getStrResponse()
            pageUrl = res.url()
            body = res.body().orEmpty()
        }
        var content = sb.toString()
        rule.replaceRegex?.takeIf { it.isNotBlank() }?.let { rr ->
            val ar = AnalyzeRule(book, source)
            ar.chapter = chapter
            content = content.split('\n').joinToString("\n") { it.trim() }
            content = ar.getString(rr, content)
        }
        val paragraphs = htmlToParagraphs(content).toMutableList()
        if (paragraphs.isNotEmpty() && sameTitle(paragraphs[0], chapter.title)) paragraphs.removeAt(0)
        return paragraphs
    }

    private fun sameTitle(a: String, b: String): Boolean {
        val norm = { s: String -> s.replace(Regex("\\s"), "") }
        return norm(a) == norm(b)
    }
}
