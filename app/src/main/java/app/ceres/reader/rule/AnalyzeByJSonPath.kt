package app.ceres.reader.rule

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ReadContext

class AnalyzeByJSonPath(json: Any) {
    private val ctx: ReadContext = when (json) {
        is ReadContext -> json
        is String -> JsonPath.using(CONF).parse(json)
        else -> JsonPath.using(CONF).parse(json)
    }

    private fun read(path: String): Any? = runCatching { ctx.read<Any?>(path) }.getOrNull()

    fun getString(rule: String): String {
        if (rule.isBlank()) return ""
        if (rule.contains("{$.")) {
            return INNER.replace(rule) { getString(it.groupValues[1]) }
        }
        return getStringList(rule).joinToString("\n")
    }

    fun getStringList(rule: String): List<String> = RuleSplitter.evaluate(rule) { single ->
        if (single.contains("{$.")) {
            listOf(INNER.replace(single) { m -> getString(m.groupValues[1]) })
        } else when (val v = read(single)) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.let(JsEngine::toStr) }
            else -> listOf(JsEngine.toStr(v))
        }
    }

    fun getList(rule: String): List<Any> = RuleSplitter.evaluate(rule) { single ->
        when (val v = read(single)) {
            null -> emptyList()
            is List<*> -> v.filterNotNull()
            else -> listOf(v)
        }
    }

    fun getObject(rule: String): Any? = read(rule)

    companion object {
        private val CONF: Configuration = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS)
        private val INNER = Regex("\\{(\\$\\..+?)\\}")
    }
}
