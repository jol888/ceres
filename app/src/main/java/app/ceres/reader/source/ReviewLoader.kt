package app.ceres.reader.source

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.ReviewRule
import app.ceres.reader.rule.AnalyzeRule
import app.ceres.reader.rule.AnalyzeUrl
import app.ceres.reader.rule.RuleLog
import app.ceres.reader.util.absoluteUrl
import app.ceres.reader.util.parseAnyMap

/** Paragraph comment counts keyed by 1-based paragraph index. */
data class ReviewSummary(val counts: Map<Int, Int>, val keys: Map<Int, String>) {
    companion object {
        val EMPTY = ReviewSummary(emptyMap(), emptyMap())
    }
}

data class ReviewItem(
    val id: String?,
    val avatar: String?,
    val name: String?,
    val badges: List<String>,
    val content: String?,
    val time: String?,
    val likeCount: Int?,
    val replyCount: Int?,
    val replyToName: String?,
    val replies: List<ReviewItem>,
)

data class ReviewPage(val items: List<ReviewItem>, val nextUrl: String?)

object ReviewLoader {

    fun summary(source: BookSource, book: Book, chapter: BookChapter): ReviewSummary {
        val rule = source.ruleReview ?: return ReviewSummary.EMPTY
        val url = rule.configuredSummaryUrl() ?: return ReviewSummary.EMPTY
        val res = AnalyzeUrl(url, baseUrl = chapter.url, source = source, ruleData = book, chapter = chapter)
            .getStrResponse()
        val body = res.body().orEmpty()
        if (body.isBlank()) return ReviewSummary.EMPTY
        val ar = AnalyzeRule(book, source).setContent(body, res.url())
        ar.chapter = chapter
        val items = runCatching { ar.getElements(rule.summaryListRule!!) }
            .onFailure { RuleLog.log("段评统计列表规则出错: ${it.message}") }
            .getOrDefault(emptyList())
        val counts = HashMap<Int, Int>()
        val keys = HashMap<Int, String>()
        items.forEachIndexed { i, item ->
            ar.setContent(item)
            val indexValue = safe { ar.getString(rule.summaryParagraphIndexRule) }
            val paraIndex = parseInt(indexValue) ?: (i + 1)
            val count = parseInt(safe { ar.getString(rule.summaryCountRule) }) ?: 0
            if (paraIndex != 0 && count > 0) {
                counts[paraIndex] = count
                keys[paraIndex] = rule.summaryParagraphDataRule?.takeIf { it.isNotBlank() }
                    ?.let { safe { ar.getString(it) } }?.takeIf { it.isNotBlank() }
                    ?: indexValue?.takeIf { it.isNotBlank() }
                    ?: paraIndex.toString()
            }
        }
        RuleLog.log("段评统计: ${counts.size} 段有评论")
        return ReviewSummary(counts, keys)
    }

    fun detail(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paraIndex: Int,
        paraData: String,
        page: Int,
        pageUrl: String?,
    ): ReviewPage {
        val rule = source.ruleReview ?: throw SourceException("段评规则未配置")
        val url = pageUrl ?: rule.reviewDetailUrl?.takeIf { it.isNotBlank() }
            ?: throw SourceException("段评详情地址未配置")
        val params = mapOf("paraIndex" to paraIndex.toString(), "paraData" to paraData, "page" to page.toString())
        val res = AnalyzeUrl(
            url, page = page, baseUrl = chapter.url, source = source, ruleData = book,
            chapter = chapter, extraParams = params,
        ).getStrResponse()
        val ar = AnalyzeRule(book, source).setContent(res.body().orEmpty(), res.url())
        ar.chapter = chapter
        params.forEach { (k, v) -> ar.setLocal(k, v) }
        val list = rule.detailListRule?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ar.getElements(it) }.getOrDefault(emptyList()) }.orEmpty()
        val next = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            ?.let { safe { ar.getString(it) } }?.trim()?.takeIf { it.isNotBlank() }
            ?.let { if (AnalyzeUrl.PARAM.containsMatchIn(it)) it else absoluteUrl(res.url(), it) }
        val items = list.mapNotNull { parseItem(ar, it, rule, res.url(), isReply = false) }
        return ReviewPage(items, next?.takeIf { items.isNotEmpty() })
    }

    fun replies(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paraIndex: Int,
        paraData: String,
        reviewId: String,
        page: Int,
    ): List<ReviewItem> {
        val rule = source.ruleReview ?: return emptyList()
        val url = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val params = mapOf(
            "paraIndex" to paraIndex.toString(), "paraData" to paraData,
            "reviewId" to reviewId, "page" to page.toString(),
        )
        val res = AnalyzeUrl(
            url, page = page, baseUrl = chapter.url, source = source, ruleData = book,
            chapter = chapter, extraParams = params,
        ).getStrResponse()
        val ar = AnalyzeRule(book, source).setContent(res.body().orEmpty(), res.url())
        ar.chapter = chapter
        params.forEach { (k, v) -> ar.setLocal(k, v) }
        val list = rule.replyListRule?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ar.getElements(it) }.getOrDefault(emptyList()) }.orEmpty()
        return list.mapNotNull { parseItem(ar, it, rule, res.url(), isReply = true) }
    }

    private fun parseItem(ar: AnalyzeRule, item: Any, rule: ReviewRule, baseUrl: String, isReply: Boolean): ReviewItem? {
        ar.setContent(item)
        val id = safe { ar.getString(if (isReply) rule.replyIdRule else rule.detailIdRule) }
        val avatar = safe { ar.getString(if (isReply) rule.replyAvatarRule else rule.detailAvatarRule) }
            ?.takeIf { it.isNotBlank() }?.let { absoluteUrl(baseUrl, it) }
        val name = safe { ar.getString(if (isReply) rule.replyNameRule else rule.detailNameRule) }
        val badgeRule = if (isReply) rule.replyBadgeRule else rule.detailBadgeRule
        val badges = badgeRule?.takeIf { it.isNotBlank() }
            ?.let { safe { ar.getStringList(it) } }.orEmpty()
            .flatMap(::splitBadge).distinct()
        val raw = safe { ar.getString(if (isReply) rule.replyContentRule else rule.detailContentRule) }
        val protocol = raw?.trim()?.takeIf { it.startsWith("{") && it.endsWith("}") }?.let { parseAnyMap(it) }
            ?.takeIf { it.isNotEmpty() }
        val content = protocol?.get("text")?.toString() ?: raw
        val replies = if (!isReply && rule.reviewQuoteUrl.isNullOrBlank() && !rule.replyListRule.isNullOrBlank()) {
            runCatching { ar.getElements(rule.replyListRule!!) }.getOrDefault(emptyList()).mapNotNull {
                val sub = AnalyzeRule(ar.ruleData, ar.source)
                sub.chapter = ar.chapter
                parseItem(sub, it, rule, baseUrl, isReply = true)
            }.also { ar.setContent(item) }
        } else emptyList()
        if (name.isNullOrBlank() && content.isNullOrBlank()) return null
        return ReviewItem(
            id = id?.takeIf { it.isNotBlank() },
            avatar = avatar,
            name = name?.takeIf { it.isNotBlank() },
            badges = badges,
            content = content,
            time = protocol?.get("time")?.toString(),
            likeCount = parseInt(protocol?.get("likeCount")),
            replyCount = if (isReply) null else parseInt(protocol?.get("replyCount")),
            replyToName = protocol?.get("replyToName")?.toString(),
            replies = replies,
        )
    }

    private fun splitBadge(v: String): List<String> {
        val raw = v.trim()
        if (raw.isEmpty()) return emptyList()
        val sep = when {
            '\n' in raw -> '\n'
            '|' in raw -> '|'
            ',' in raw && !raw.contains("://") -> ','
            else -> return listOf(raw)
        }
        return raw.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseInt(v: Any?): Int? = when (v) {
        null -> null
        is Number -> v.toInt()
        else -> v.toString().trim().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    }

    private inline fun <T> safe(block: () -> T): T? = runCatching(block)
        .onFailure { RuleLog.log("段评规则出错: ${it.message}") }
        .getOrNull()
}
