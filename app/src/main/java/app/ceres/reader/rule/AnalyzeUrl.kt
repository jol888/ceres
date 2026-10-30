package app.ceres.reader.rule

import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.RuleData
import app.ceres.reader.source.fanqie.FanqieUrls
import app.ceres.reader.util.absoluteUrl
import app.ceres.reader.util.isJsonLike
import app.ceres.reader.util.parseAnyMap
import app.ceres.reader.util.parseStringMap
import app.ceres.reader.util.toJsonString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64

/**
 * Legado URL rule: `url,{"method":"POST","body":"...","headers":{...},"charset":"gbk"}`
 * with `{{js}}` templates, `<a,b,c>` page lists and `@js:` / `<js></js>` scripts.
 */
class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    baseUrl: String? = null,
    override val source: BookSource? = null,
    private val ruleData: RuleData? = null,
    private val chapter: BookChapter? = null,
    private val extraParams: Map<String, String> = emptyMap(),
    headerOverride: Map<String, String>? = null,
    forceMethod: String? = null,
    forceBody: String? = null,
) : JsExtensions(source) {

    var url: String = ""
        private set
    var method: String = "GET"
        private set
    var body: String? = null
        private set
    private var charset: String? = null
    var type: String? = null
        private set
    private var retry = 0
    var useWebView = false
        private set
    var webJs: String? = null
    var sourceRegex: String? = null
    private val baseUrl: String
    val headerMap = LinkedHashMap<String, String>()

    init {
        this.baseUrl = (baseUrl ?: source?.bookSourceUrl).orEmpty().split(PARAM, 2)[0]
        loadSourceHeaders()
        loadLoginHeaders()
        headerOverride?.let { headerMap.putAll(it) }
        initUrl()
        forceMethod?.let { method = it }
        forceBody?.let { body = it }
    }

    private fun loadSourceHeaders() {
        val raw = source?.header?.trim().orEmpty()
        if (raw.isEmpty()) return
        val json = when {
            raw.startsWith("@js:", true) -> JsEngine.toStr(evalJs(raw.substring(4), raw))
            raw.startsWith("<js>", true) -> JsEngine.toStr(evalJs(raw.substring(4, raw.lastIndexOf("<")), raw))
            else -> raw
        }
        headerMap.putAll(parseStringMap(json))
    }

    private fun loadLoginHeaders() {
        source?.getLoginHeaderMap()?.let { headerMap.putAll(it) }
    }

    private fun initUrl() {
        var ruleUrl = analyzeJs(mUrl)
        ruleUrl = replaceKeyPageJs(ruleUrl)
        val parts = ruleUrl.split(PARAM, 2)
        url = absoluteUrl(baseUrl, parts[0].trim())
        if (parts.size > 1) applyOption(parts[1])
        if (method == "GET") url = encodeQuery(url)
    }

    private fun analyzeJs(ruleUrl: String): String {
        var start = 0
        var result = ruleUrl
        var found = false
        for (m in JS_PATTERN.findAll(ruleUrl)) {
            found = true
            if (m.range.first > start) {
                val tmp = ruleUrl.substring(start, m.range.first).trim()
                if (tmp.isNotEmpty()) result = tmp.replace("@result", result)
            }
            val js = m.groups[2]?.value ?: m.groups[1]?.value.orEmpty()
            result = JsEngine.toStr(evalJs(js, result))
            start = m.range.last + 1
        }
        if (!found) return ruleUrl
        if (ruleUrl.length > start) {
            val tmp = ruleUrl.substring(start).trim()
            if (tmp.isNotEmpty()) result = tmp.replace("@result", result)
        }
        return result
    }

    private fun replaceKeyPageJs(input: String): String {
        var s = input
        if (s.contains("{{") && s.contains("}}")) {
            s = EXP_PATTERN.replace(s) { m -> JsEngine.toStr(evalJs(m.groupValues[1], null)) }
        }
        if (page != null) {
            PAGE_PATTERN.find(s)?.let { m ->
                val pages = m.groupValues[1].split(',')
                val v = if (page <= pages.size) pages[page - 1] else pages.last()
                s = s.replaceFirst(m.value, v.trim())
            }
        }
        return s
    }

    private fun applyOption(json: String) {
        val opt = parseAnyMap(json)
        (opt["method"] as? String)?.let { method = it.uppercase() }
        (opt["charset"] as? String)?.let { charset = it }
        when (val h = opt["headers"]) {
            is Map<*, *> -> h.forEach { (k, v) -> headerMap[k.toString()] = JsEngine.toStr(v) }
            is String -> headerMap.putAll(parseStringMap(h))
        }
        when (val b = opt["body"]) {
            null -> Unit
            is String -> body = b
            else -> body = toJsonString(b)
        }
        (opt["type"] as? String)?.let { type = it }
        opt["retry"]?.let { retry = JsEngine.toStr(it).toIntOrNull() ?: 0 }
        useWebView = opt["webView"].let { it != null && it != false && it != "" && it != "false" }
        (opt["webJs"] as? String)?.takeIf { it.isNotBlank() }?.let { webJs = it }
        (opt["js"] as? String)?.takeIf { it.isNotBlank() }?.let {
            url = JsEngine.toStr(evalJs(it, url))
        }
    }

    private fun cs(): Charset = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private fun encodeValue(v: String): String =
        if (ENCODED.containsMatchIn(v) || v.all { it.code < 128 && it != ' ' }) v
        else URLEncoder.encode(v, cs())

    private fun encodeQuery(u: String): String {
        val q = u.indexOf('?')
        if (q < 0) return u
        val query = u.substring(q + 1).split('&').joinToString("&") { pair ->
            val i = pair.indexOf('=')
            if (i < 0) pair else pair.substring(0, i + 1) + encodeValue(pair.substring(i + 1))
        }
        return u.substring(0, q + 1) + query
    }

    private fun evalJs(js: String, result: Any?): Any? {
        val bindings = HashMap<String, Any?>()
        bindings["java"] = this
        bindings["baseUrl"] = baseUrl
        bindings["result"] = result
        bindings["key"] = key
        bindings["page"] = page
        bindings["book"] = ruleData
        bindings["chapter"] = chapter
        bindings["title"] = chapter?.title
        bindings["source"] = source
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheStore
        bindings.putAll(extraParams)
        return JsEngine.eval(js, bindings, source?.jsLib)
    }

    fun put(key: String, value: Any?): String {
        val v = JsEngine.toStr(value)
        chapter?.putVariable(key, v) ?: ruleData?.putVariable(key, v) ?: source?.put(key, v)
        return v
    }

    fun get(key: String): String {
        extraParams[key]?.let { return it }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key).orEmpty()
    }

    fun getStrResponse(): StrResponse {
        if (url.startsWith(FanqieUrls.SCHEME, true)) {
            return StrResponse(url, FanqieUrls.resolve(url, baseUrl))
        }
        if (url.startsWith("data:", true)) {
            val data = url.substringAfter("base64,", "")
            val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrDefault(ByteArray(0))
            return StrResponse(url, String(bytes, cs()))
        }
        val host = Host.current
        if (useWebView && host != null) {
            RuleLog.log("⇒ WebView $method $url")
            val html = if (method == "POST") execute { resp, bytes -> Http.decode(bytes, resp, charset) } else null
            val text = host.webView(html, url, webJs, headerMap, sourceRegex)
            return StrResponse(url, text)
        }
        if (useWebView) RuleLog.log("无 WebView 环境，按普通请求处理")
        return execute { resp, bytes ->
            val text = if (type != null) bytes.toHex() else Http.decode(bytes, resp, charset)
            val headers = resp.headers.names().associate { it.lowercase() to resp.headers(it).joinToString("; ") }
            StrResponse(resp.request.url.toString(), text, resp.code, headers)
        }
    }

    fun getByteArray(): ByteArray {
        if (url.startsWith(FanqieUrls.SCHEME, true)) {
            return FanqieUrls.resolve(url, baseUrl).toByteArray()
        }
        if (url.startsWith("data:", true)) {
            return runCatching { Base64.getMimeDecoder().decode(url.substringAfter("base64,", "")) }
                .getOrDefault(ByteArray(0))
        }
        return execute { _, bytes -> bytes }
    }

    private fun <T> execute(read: (okhttp3.Response, ByteArray) -> T): T {
        RuleLog.log("⇒ $method $url")
        val builder = Request.Builder().url(url)
        headerMap.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        if (headerMap.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", Http.DEFAULT_UA)
        if (source?.enabledCookieJar != false) {
            val stored = CookieStore.getCookie(url)
            if (stored.isNotEmpty()) {
                val explicit = headerMap.entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
                val merged = CookieStore.cookieToMap(stored) + CookieStore.cookieToMap(explicit)
                builder.header("Cookie", CookieStore.mapToCookie(merged))
            }
        }
        when (method) {
            "POST" -> builder.post(buildBody())
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        val request = builder.build()
        var lastError: Throwable? = null
        repeat(retry + 1) {
            try {
                Http.client.newCall(request).execute().use { resp ->
                    val bytes = resp.body.bytes()
                    if (source?.enabledCookieJar != false) {
                        val setCookies = resp.headers("Set-Cookie").map { it.substringBefore(';') }
                        if (setCookies.isNotEmpty()) CookieStore.replaceCookie(url, setCookies.joinToString("; "))
                    }
                    return read(resp, bytes)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("请求失败: $url")
    }

    private fun buildBody(): okhttp3.RequestBody {
        val b = body.orEmpty()
        val explicit = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        if (explicit != null) return b.toByteArray(cs()).toRequestBody(explicit.toMediaType())
        return when {
            b.isJsonLike() -> b.toByteArray(cs()).toRequestBody("application/json; charset=${cs().name()}".toMediaType())
            b.contains('=') -> {
                val form = b.split('&').joinToString("&") { pair ->
                    val i = pair.indexOf('=')
                    if (i < 0) pair else pair.substring(0, i + 1) + encodeValue(pair.substring(i + 1))
                }
                form.toByteArray().toRequestBody("application/x-www-form-urlencoded".toMediaType())
            }
            else -> b.toByteArray(cs()).toRequestBody("text/plain; charset=${cs().name()}".toMediaType())
        }
    }

    companion object {
        val PARAM = Regex("\\s*,\\s*(?=\\{)")
        val JS_PATTERN = Regex("(?is)<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)")
        val EXP_PATTERN = Regex("\\{\\{([\\w\\W]*?)\\}\\}")
        private val PAGE_PATTERN = Regex("<(.*?)>")
        private val ENCODED = Regex("%[0-9A-Fa-f]{2}")
    }
}
