package app.ceres.reader.rule

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.RuleData
import app.ceres.reader.util.absoluteUrl
import app.ceres.reader.util.isJsonLike
import app.ceres.reader.util.parseStringMap
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.jayway.jsonpath.ReadContext
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.seimicrawler.xpath.JXNode

class AnalyzeRule(
    var ruleData: RuleData? = null,
    override val source: BookSource? = null,
) : JsExtensions(source) {

    var chapter: BookChapter? = null
    var nextChapterUrl: String? = null
    var content: Any? = null
        private set
    var baseUrl: String? = null
        private set
    var redirectUrl: String? = null
    private var isJSON = false
    private val locals = HashMap<String, String>()
    private val ruleCache = HashMap<String, List<SourceRule>>()

    // Parsed forms of `content`, so every rule on a page shares one parse.
    private var htmlRoot: Element? = null
    private var jsonCtx: ReadContext? = null
    private var xpathDoc: Any? = null

    enum class Mode { XPath, Json, Default, Js, Regex }

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content !== this.content) {
            htmlRoot = null; jsonCtx = null; xpathDoc = null
        }
        this.content = content
        isJSON = when (content) {
            is Map<*, *> -> true
            is String -> content.isJsonLike()
            else -> false
        }
        if (baseUrl != null) {
            this.baseUrl = baseUrl
            if (redirectUrl == null) redirectUrl = baseUrl
        }
        return this
    }

    fun setLocal(key: String, value: String): AnalyzeRule {
        locals[key] = value
        return this
    }

    // ---------------- public API ----------------

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        return getStringList(splitSourceRule(rule), mContent, isUrl)
    }

    fun getStringList(ruleList: List<SourceRule>, mContent: Any?, isUrl: Boolean): List<String> {
        var result: Any? = mContent ?: content
        if (result != null && ruleList.isNotEmpty()) {
            for (sr in ruleList) {
                putRule(sr.putMap)
                sr.makeUpRule(result)
                val cur = result ?: break
                result = when (sr.mode) {
                    Mode.Js -> evalJS(sr.rule, cur)
                    Mode.Json -> jsonPath(cur).getStringList(sr.rule)
                    Mode.XPath -> xpath(cur).getStringList(sr.rule)
                    Mode.Default -> if (sr.rule.isEmpty()) JsEngine.toStr(cur) else jsoup(cur).getStringList(sr.rule)
                    Mode.Regex -> sr.rule
                }
                if (sr.replaceRegex.isNotEmpty()) {
                    result = when (val r = result) {
                        is List<*> -> r.map { replaceRegex(JsEngine.toStr(it), sr) }
                        else -> replaceRegex(JsEngine.toStr(r), sr)
                    }
                }
            }
        }
        val list: List<String> = when (val r = result) {
            null -> emptyList()
            is String -> r.split('\n')
            is List<*> -> r.map { JsEngine.toStr(it) }
            else -> listOf(JsEngine.toStr(r))
        }
        if (!isUrl) return list.filter { it.isNotEmpty() }
        return list.map { it.trim() }.filter { it.isNotEmpty() }
            .map { absoluteUrl(redirectUrl ?: baseUrl, it) }.distinct()
    }

    @JvmOverloads
    fun getString(rule: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (rule.isNullOrBlank()) {
            return if (isUrl) baseUrl.orEmpty() else ""
        }
        return getString(splitSourceRule(rule), mContent, isUrl)
    }

    fun getString(ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false, unescape: Boolean = true): String {
        var result: Any? = mContent ?: content
        var lastMode = Mode.Js
        if (result != null && ruleList.isNotEmpty()) {
            for (sr in ruleList) {
                putRule(sr.putMap)
                sr.makeUpRule(result)
                val cur = result ?: break
                lastMode = sr.mode
                if (sr.rule.isNotEmpty() || sr.replaceRegex.isEmpty()) {
                    result = when (sr.mode) {
                        Mode.Js -> evalJS(sr.rule, cur)
                        Mode.Json -> jsonPath(cur).getString(sr.rule)
                        Mode.XPath -> xpath(cur).getString(sr.rule)
                        Mode.Default -> when {
                            sr.rule.isEmpty() -> JsEngine.toStr(cur)
                            isUrl -> jsoup(cur).getString0(sr.rule)
                            else -> jsoup(cur).getString(sr.rule)
                        }
                        Mode.Regex -> sr.rule
                    }
                } else if (cur !is String) {
                    result = stringOf(cur)
                }
                if (result != null && sr.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(JsEngine.toStr(result), sr)
                }
            }
        }
        var str = JsEngine.toStr(result)
        val fromHtml = lastMode == Mode.Default || lastMode == Mode.XPath
        if (unescape && fromHtml && !isUrl && str.indexOf('&') > -1) str = Parser.unescapeEntities(str, false)
        if (isUrl) return if (str.isBlank()) baseUrl.orEmpty() else absoluteUrl(redirectUrl ?: baseUrl, str.trim())
        return str
    }

    fun getElement(rule: String): Any? = getElements(rule).firstOrNull()

    fun getElements(rule: String): List<Any> {
        val ruleList = splitSourceRule(rule, allInOne = true)
        var result: Any? = content
        if (result != null && ruleList.isNotEmpty()) {
            for (sr in ruleList) {
                putRule(sr.putMap)
                sr.makeUpRule(result)
                val cur = result ?: break
                result = when (sr.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(JsEngine.toStr(cur), sr.rule.split("&&"))
                    Mode.Js -> evalJS(sr.rule, cur)
                    Mode.Json -> jsonPath(cur).getList(sr.rule)
                    Mode.XPath -> xpath(cur).getElements(sr.rule)
                    Mode.Default -> jsoup(cur).getElements(sr.rule)
                }
                if (sr.replaceRegex.isNotEmpty()) result = replaceRegex(JsEngine.toStr(result), sr)
            }
        }
        return when (val r = result) {
            null -> emptyList()
            is List<*> -> r.filterNotNull()
            is String -> if (r.trim().startsWith("[")) parseJsonList(r) else listOf(r)
            else -> listOf(r)
        }
    }

    // ---------------- variables ----------------

    fun put(key: String, value: Any?): String {
        val v = JsEngine.toStr(value)
        chapter?.putVariable(key, v) ?: ruleData?.putVariable(key, v) ?: source?.put(key, v)
        return v
    }

    fun get(key: String): String {
        locals[key]?.let { return it }
        when (key) {
            "bookName" -> (ruleData as? Book)?.name?.let { return it }
            "title" -> chapter?.title?.let { return it }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key).orEmpty()
    }

    private fun putRule(map: Map<String, String>) {
        map.forEach { (k, rule) -> put(k, getString(rule)) }
    }

    // ---------------- JS ----------------

    fun evalJS(js: String, result: Any? = null): Any? {
        val bindings = HashMap<String, Any?>()
        bindings["java"] = this
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheStore
        bindings["source"] = source
        bindings["book"] = ruleData
        bindings["result"] = result
        bindings["baseUrl"] = baseUrl
        bindings["chapter"] = chapter
        bindings["title"] = chapter?.title
        // Serialising a large element is costly; most scripts never read `src`.
        if (js.contains("src")) bindings["src"] = content?.let { stringOf(it) }
        bindings["nextChapterUrl"] = nextChapterUrl
        bindings.putAll(locals)
        return JsEngine.eval(js, bindings, source?.jsLib)
    }

    /** JS helpers: `java.getString(rule)`, `java.getElements(rule)`, `java.setContent(x)`. */
    fun setContent(content: Any?): AnalyzeRule = setContent(content, null)

    fun getElementsList(rule: String): List<Any> = getElements(rule)

    fun ajaxUrl(url: String): StrResponse =
        AnalyzeUrl(url, baseUrl = baseUrl, source = source, ruleData = ruleData, chapter = chapter).getStrResponse()

    // ---------------- internals ----------------

    private fun jsonContent(cur: Any): Any = when (cur) {
        is String, is Map<*, *>, is List<*> -> cur
        else -> cur.toString()
    }

    private fun isPage(cur: Any) = cur is String && cur === content

    private fun jsoup(cur: Any): AnalyzeByJSoup =
        if (isPage(cur)) AnalyzeByJSoup(htmlRoot ?: AnalyzeByJSoup.toElement(cur).also { htmlRoot = it })
        else AnalyzeByJSoup(cur)

    private fun jsonPath(cur: Any): AnalyzeByJSonPath =
        if (isPage(cur)) AnalyzeByJSonPath(jsonCtx ?: AnalyzeByJSonPath.parse(cur).also { jsonCtx = it })
        else AnalyzeByJSonPath(jsonContent(cur))

    private fun xpath(cur: Any): AnalyzeByXPath =
        if (isPage(cur)) AnalyzeByXPath(xpathDoc ?: AnalyzeByXPath.parse(cur).also { xpathDoc = it })
        else AnalyzeByXPath(cur)

    private fun stringOf(any: Any): String = when (any) {
        is Element -> any.outerHtml()
        is JXNode -> any.toString()
        else -> JsEngine.toStr(any)
    }

    private fun parseJsonList(s: String): List<Any> = runCatching {
        val type = object : TypeToken<List<Any>>() {}.type
        com.google.gson.Gson().fromJson<List<Any>>(JsonParser.parseString(s), type)
    }.getOrDefault(listOf(s))

    private fun replaceRegex(result: String, sr: SourceRule): String {
        if (sr.replaceRegex.isEmpty()) return result
        val regex = compileRegex(sr.replaceRegex)
        val replacement = sr.replacement
        return if (sr.replaceFirst) {
            if (regex == null) return result.replaceFirst(sr.replaceRegex, replacement)
            val m = regex.find(result) ?: return ""
            m.value.replace(regex, replacement)
        } else {
            regex?.replace(result, replacement) ?: result.replace(sr.replaceRegex, replacement)
        }
    }

    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val cacheKey = "$allInOne|$isJSON|$ruleStr"
        ruleCache[cacheKey]?.let { return it }
        val list = ArrayList<SourceRule>()
        var mode = Mode.Default
        var start = 0
        if (allInOne && ruleStr.startsWith(":")) {
            mode = Mode.Regex
            start = 1
        }
        for (m in AnalyzeUrl.JS_PATTERN.findAll(ruleStr, start)) {
            if (m.range.first > start) {
                val tmp = ruleStr.substring(start, m.range.first).trim()
                if (tmp.isNotEmpty()) list.add(SourceRule(tmp, mode))
            }
            list.add(SourceRule(m.groups[2]?.value ?: m.groups[1]?.value.orEmpty(), Mode.Js))
            start = m.range.last + 1
        }
        if (ruleStr.length > start) {
            val tmp = ruleStr.substring(start).trim()
            if (tmp.isNotEmpty()) list.add(SourceRule(tmp, mode))
        }
        ruleCache[cacheKey] = list
        return list
    }

    /** One segment of a rule string, with its own extraction mode. */
    inner class SourceRule(ruleStr: String, mainMode: Mode = Mode.Default) {
        var mode: Mode
        var rule: String
        var replaceRegex = ""
        var replacement = ""
        var replaceFirst = false
        val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()

        init {
            mode = mainMode
            rule = when {
                mode == Mode.Js || mode == Mode.Regex -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> {
                    mode = Mode.Default; ruleStr
                }
                ruleStr.startsWith("@@") -> {
                    mode = Mode.Default; ruleStr.substring(2)
                }
                ruleStr.startsWith("@XPath:", true) -> {
                    mode = Mode.XPath; ruleStr.substring(7)
                }
                ruleStr.startsWith("@Json:", true) -> {
                    mode = Mode.Json; ruleStr.substring(6)
                }
                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                    mode = Mode.Json; ruleStr
                }
                ruleStr.startsWith("/") -> {
                    mode = Mode.XPath; ruleStr
                }
                else -> ruleStr
            }
            rule = splitPutRule(rule)
            parseTemplates()
        }

        private fun splitPutRule(s: String): String {
            var out = s
            PUT_PATTERN.findAll(s).forEach { m ->
                out = out.replace(m.value, "")
                putMap.putAll(parseStringMap(m.groupValues[1]))
            }
            return out
        }

        private fun parseTemplates() {
            var start = 0
            val matches = EVAL_PATTERN.findAll(rule).toList()
            if (matches.isNotEmpty()) {
                val first = matches.first()
                val before = rule.substring(0, first.range.first)
                if (mode != Mode.Js && mode != Mode.Regex && (first.range.first == 0 || !before.contains("##"))) {
                    mode = Mode.Regex
                }
                for (m in matches) {
                    if (m.range.first > start) splitRegex(rule.substring(start, m.range.first))
                    val tmp = m.value
                    when {
                        tmp.startsWith("@get:", true) -> {
                            ruleType.add(GET_RULE); ruleParam.add(tmp.substring(6, tmp.lastIndex))
                        }
                        tmp.startsWith("{{") -> {
                            ruleType.add(JS_RULE); ruleParam.add(tmp.substring(2, tmp.length - 2))
                        }
                        else -> splitRegex(tmp)
                    }
                    start = m.range.last + 1
                }
            }
            if (rule.length > start && ruleParam.isNotEmpty()) splitRegex(rule.substring(start))
            else if (ruleParam.isEmpty()) splitRegex(rule)
        }

        private fun splitRegex(ruleStr: String) {
            var start = 0
            val head = ruleStr.split("##")[0]
            val matches = REGEX_GROUP.findAll(head).toList()
            if (matches.isNotEmpty()) {
                if (mode != Mode.Js && mode != Mode.Regex) mode = Mode.Regex
                for (m in matches) {
                    if (m.range.first > start) {
                        ruleType.add(DEFAULT_RULE); ruleParam.add(ruleStr.substring(start, m.range.first))
                    }
                    ruleType.add(m.value.substring(1).toInt()); ruleParam.add(m.value)
                    start = m.range.last + 1
                }
            }
            if (ruleStr.length > start) {
                ruleType.add(DEFAULT_RULE); ruleParam.add(ruleStr.substring(start))
            }
        }

        private fun isRule(s: String) =
            s.startsWith('@') || s.startsWith("$.") || s.startsWith("$[") || s.startsWith("//")

        fun makeUpRule(result: Any?) {
            if (ruleParam.isNotEmpty() && (ruleParam.size > 1 || ruleType[0] != DEFAULT_RULE)) {
                val sb = StringBuilder()
                for (i in ruleParam.indices) {
                    val type = ruleType[i]
                    val param = ruleParam[i]
                    when {
                        type > DEFAULT_RULE -> {
                            val group = (result as? List<*>)?.getOrNull(type)
                            if (group != null) sb.append(JsEngine.toStr(group))
                            else if (result !is List<*>) sb.append(param)
                        }
                        type == JS_RULE -> {
                            if (isRule(param)) {
                                sb.append(getString(listOf(SourceRule(param)), result))
                            } else {
                                sb.append(JsEngine.toStr(evalJS(param, result)))
                            }
                        }
                        type == GET_RULE -> sb.append(get(param))
                        else -> sb.append(param)
                    }
                }
                rule = sb.toString()
            } else if (ruleParam.size == 1) {
                rule = ruleParam[0]
            }
            val parts = rule.split("##")
            rule = parts[0].trim()
            replaceRegex = parts.getOrElse(1) { "" }
            replacement = parts.getOrElse(2) { "" }
            replaceFirst = parts.size > 3
        }
    }

    companion object {
        private const val DEFAULT_RULE = 0
        private const val JS_RULE = -1
        private const val GET_RULE = -2
        private val PUT_PATTERN = Regex("@put:(\\{[^}]+?\\})", RegexOption.IGNORE_CASE)
        private val EVAL_PATTERN = Regex("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", RegexOption.IGNORE_CASE)
        private val REGEX_GROUP = Regex("\\$\\d{1,2}")

        private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Result<Regex>>()

        private fun compileRegex(pattern: String): Regex? {
            if (regexCache.size > 512) regexCache.clear()
            return regexCache.getOrPut(pattern) { runCatching { Regex(pattern) } }.getOrNull()
        }
    }
}
