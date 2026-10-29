package app.ceres.reader.data.model

import app.ceres.reader.rule.AnalyzeRule
import app.ceres.reader.rule.Kv
import app.ceres.reader.util.GSON
import app.ceres.reader.util.absoluteUrl
import app.ceres.reader.util.parseAnyMap
import app.ceres.reader.util.parseStringMap

/**
 * Legado-compatible book source. Field names must match the Legado JSON format so that
 * existing sources can be imported unchanged.
 */
data class BookSource(
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = 0,
    var bookUrlPattern: String? = null,
    var customOrder: Int = 0,
    var enabled: Boolean = true,
    var enabledExplore: Boolean = true,
    var jsLib: String? = null,
    var enabledCookieJar: Boolean? = true,
    var concurrentRate: String? = null,
    var header: String? = null,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var bookSourceComment: String? = null,
    var variableComment: String? = null,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var ruleExplore: BookListRule? = null,
    var searchUrl: String? = null,
    var ruleSearch: BookListRule? = null,
    var ruleBookInfo: BookInfoRule? = null,
    var ruleToc: TocRule? = null,
    var ruleContent: ContentRule? = null,
    var ruleReview: ReviewRule? = null,
) {
    fun getKey(): String = bookSourceUrl
    fun getTag(): String = bookSourceName

    fun getVariable(): String = Kv.get("sourceVariable_${getKey()}").orEmpty()
    fun setVariable(variable: String?) = Kv.put("sourceVariable_${getKey()}", variable)

    fun put(key: String, value: String?): String {
        Kv.put("v_${getKey()}_$key", value)
        return value.orEmpty()
    }

    fun get(key: String): String = Kv.get("v_${getKey()}_$key").orEmpty()

    fun getLoginInfo(): String? = Kv.get("userInfo_${getKey()}")
    fun putLoginInfo(info: String): Boolean {
        Kv.put("userInfo_${getKey()}", info)
        return true
    }
    fun removeLoginInfo() = Kv.put("userInfo_${getKey()}", null)
    fun getLoginInfoMap(): Map<String, String> = parseStringMap(getLoginInfo())

    fun getLoginHeader(): String? = Kv.get("loginHeader_${getKey()}")
    fun getLoginHeaderMap(): Map<String, String> = parseStringMap(getLoginHeader())
    fun putLoginHeader(header: String) = Kv.put("loginHeader_${getKey()}", header)
    fun removeLoginHeader() = Kv.put("loginHeader_${getKey()}", null)

    /** loginUrl as script: `@js:…`, `<js>…</js>` or bare JS defining `login()`; null for a web login URL. */
    fun getLoginJs(): String? {
        val raw = loginUrl?.trim().orEmpty()
        return when {
            raw.isEmpty() -> null
            raw.startsWith("@js:", true) -> raw.substring(4)
            raw.startsWith("<js>", true) -> raw.substring(4, raw.lastIndexOf('<').takeIf { it > 4 } ?: raw.length)
            raw.startsWith("http", true) || raw.startsWith("{") -> null
            else -> raw
        }
    }

    /** Page for web login: an http loginUrl, or `{"url": "..."}`. */
    fun getLoginWebUrl(): String? {
        val raw = loginUrl?.trim().orEmpty()
        val url = when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("{") -> parseAnyMap(raw)["url"]?.toString()
            else -> null
        }
        return url?.takeIf { it.isNotBlank() }?.let { absoluteUrl(getKey(), it) }
    }

    /** Runs the source's `login()` function; loginUi values are available via [getLoginInfoMap]. */
    fun login() {
        val js = getLoginJs()?.takeIf { it.isNotBlank() } ?: return
        evalJS(
            "$js\nif (typeof login == 'function') { login.apply(this); } " +
                "else { throw('Function login not implements!!!'); }"
        )
    }

    @JvmOverloads
    fun evalJS(js: String, result: Any? = null): Any? = AnalyzeRule(null, this).setContent(null, getKey()).evalJS(js, result)

    fun hasReview(): Boolean = ruleReview?.configuredSummaryUrl() != null

    fun toJson(): String = GSON.toJson(this)
}

data class BookListRule(
    var checkKeyWord: String? = null,
    var bookList: String? = null,
    var name: String? = null,
    var author: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var bookUrl: String? = null,
    var coverUrl: String? = null,
    var wordCount: String? = null,
)

data class BookInfoRule(
    var init: String? = null,
    var name: String? = null,
    var author: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String? = null,
    var wordCount: String? = null,
    var canReName: String? = null,
    var downloadUrls: String? = null,
)

data class TocRule(
    var preUpdateJs: String? = null,
    var chapterList: String? = null,
    var chapterName: String? = null,
    var chapterUrl: String? = null,
    var formatJs: String? = null,
    var isVolume: String? = null,
    var isVip: String? = null,
    var isPay: String? = null,
    var updateTime: String? = null,
    var nextTocUrl: String? = null,
)

data class ContentRule(
    var content: String? = null,
    var subContent: String? = null,
    var title: String? = null,
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null,
    var imageStyle: String? = null,
    var imageDecode: String? = null,
    var payAction: String? = null,
)

/**
 * Paragraph comment (段评) rules, same field names as Legado's ReviewRule.
 * Paragraph indexes returned by [summaryParagraphIndexRule] are 1-based.
 * URL templates can use `paraIndex`, `paraData`, `page` and (for replies) `reviewId`.
 */
data class ReviewRule(
    var enabled: Boolean = false,
    var reviewSummaryUrl: String? = null,
    var summaryListRule: String? = null,
    var summaryParagraphIndexRule: String? = null,
    var summaryParagraphDataRule: String? = null,
    var summaryCountRule: String? = null,
    var reviewDetailUrl: String? = null,
    var reviewDetailNextPageUrl: String? = null,
    var detailListRule: String? = null,
    var detailIdRule: String? = null,
    var detailAvatarRule: String? = null,
    var detailNameRule: String? = null,
    var detailBadgeRule: String? = null,
    var detailContentRule: String? = null,
    var reviewQuoteUrl: String? = null,
    var replyListRule: String? = null,
    var replyIdRule: String? = null,
    var replyAvatarRule: String? = null,
    var replyNameRule: String? = null,
    var replyBadgeRule: String? = null,
    var replyContentRule: String? = null,
) {
    fun configuredSummaryUrl(): String? {
        if (!enabled || summaryListRule.isNullOrBlank() ||
            summaryParagraphIndexRule.isNullOrBlank() || summaryCountRule.isNullOrBlank()
        ) return null
        return reviewSummaryUrl?.takeIf { it.isNotBlank() }
    }
}
