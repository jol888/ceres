package app.ceres.reader

import app.ceres.reader.rule.AnalyzeRule
import app.ceres.reader.rule.JsEngine
import app.ceres.reader.util.GSON
import app.ceres.reader.data.model.BookSource
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline compatibility check against real Legado source collections.
 * `CERES_COMPAT=/dir/with/json ./gradlew testDebugUnitTest --tests '*CompatHarness*' -i`
 */
class CompatHarness {
    private val jsBlock = Regex("<js>([\\s\\S]*?)</js>", RegexOption.IGNORE_CASE)

    private val template = Regex("\\{\\{[\\s\\S]*?\\}\\}|@get:\\{[^}]+?\\}|@put:\\{[^}]+?\\}", RegexOption.IGNORE_CASE)

    /** What the engine would hand to Rhino at runtime (templates filled, `##` replace split off). */
    private fun asRun(js: String) = template.replace(js, "0").split("##")[0]

    private fun jsSnippets(rule: String): List<String> {
        val out = ArrayList<String>()
        jsBlock.findAll(rule).forEach { out.add(asRun(it.groupValues[1])) }
        val stripped = jsBlock.replace(rule, "")
        val at = stripped.indexOf("@js:", ignoreCase = true)
        if (at >= 0) out.add(asRun(stripped.substring(at + 4)))
        return out
    }

    private fun strings(e: JsonElement?, path: String, out: MutableList<Pair<String, String>>) {
        when {
            e == null || e.isJsonNull -> Unit
            e.isJsonObject -> e.asJsonObject.entrySet().forEach { (k, v) -> strings(v, "$path.$k", out) }
            e.isJsonArray -> e.asJsonArray.forEach { strings(it, path, out) }
            e.isJsonPrimitive && e.asJsonPrimitive.isString -> {
                val s = e.asString
                // rule objects stored as JSON strings
                if (s.trimStart().startsWith("{") && path.count { it == '.' } == 1 && path.startsWith(".rule")) {
                    runCatching { strings(JsonParser.parseString(s), path, out) }.onFailure { out.add(path to s) }
                } else out.add(path to s)
            }
        }
    }

    @Test
    fun run() {
        val dir = System.getenv("CERES_COMPAT") ?: ""
        assumeTrue(dir.isNotEmpty())
        val elements = LinkedHashMap<String, JsonElement>()
        File(dir).listFiles { f -> f.name.endsWith(".json") }!!.forEach { f ->
            val root = runCatching { JsonParser.parseString(f.readText()) }.getOrNull() ?: return@forEach
            (if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)).forEach { el ->
                val url = runCatching { el.asJsonObject["bookSourceUrl"].asString }.getOrNull() ?: return@forEach
                val type = runCatching { el.asJsonObject["bookSourceType"].asInt }.getOrDefault(0)
                if (type == 0) elements[url] = el
            }
        }
        println("[compat] text sources: ${elements.size}")

        val parseErrors = HashMap<String, Int>()
        val jsErrors = HashMap<String, MutableList<String>>()
        val ruleErrors = HashMap<String, Int>()
        var jsTotal = 0
        var jsFailedSources = 0
        var parsedOk = 0
        for ((url, el) in elements) {
            val src = runCatching { GSON.fromJson(el, BookSource::class.java) }
                .onFailure { parseErrors.merge(it.message?.take(90) ?: it.javaClass.simpleName, 1, Int::plus) }
                .getOrNull() ?: continue
            parsedOk++
            val all = ArrayList<Pair<String, String>>()
            strings(el, "", all)
            var failed = false
            for ((path, s) in all) {
                val isJsField = path == ".jsLib" || path == ".loginCheckJs" || path.endsWith(".preUpdateJs") ||
                    path.endsWith(".formatJs") || path == ".coverDecodeJs" || path.endsWith(".webJs") ||
                    (path == ".loginUrl" && !s.trim().startsWith("http") && s.isNotBlank())
                val snippets = if (isJsField) listOf(s.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")) else jsSnippets(s)
                for (js in snippets) {
                    jsTotal++
                    val err = JsEngine.syntaxError(js) ?: continue
                    failed = true
                    val key = err.substringBefore(" (check#").take(80)
                    jsErrors.getOrPut(key) { ArrayList() }.add("$url $path")
                }
                if (path.startsWith(".rule") && !path.endsWith("Js") && s.isNotBlank()) {
                    runCatching { AnalyzeRule(null, src).splitSourceRule(s) }
                        .onFailure { ruleErrors.merge("${it.javaClass.simpleName}: ${it.message?.take(60)}", 1, Int::plus) }
                }
            }
            if (failed) jsFailedSources++
        }
        println("[compat] parsed: $parsedOk / ${elements.size}")
        parseErrors.entries.sortedByDescending { it.value }.take(10).forEach { println("[compat]   parse ${it.value}x ${it.key}") }
        println("[compat] js snippets: $jsTotal, sources with js syntax errors: $jsFailedSources")
        jsErrors.entries.sortedByDescending { it.value.size }.take(25).forEach {
            println("[compat]   js ${it.value.size}x ${it.key}   e.g. ${it.value.first()}")
        }
        val byField = jsErrors.values.flatten().groupingBy { it.substringAfterLast(' ') }.eachCount()
        println("[compat] js errors by field: " + byField.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        jsErrors.values.flatten().filter { !it.endsWith(".loginUrl") }.forEach { println("[compat]   non-login: $it") }
        println("[compat] rule split errors: ${ruleErrors.values.sum()}")
        ruleErrors.entries.sortedByDescending { it.value }.take(10).forEach { println("[compat]   rule ${it.value}x ${it.key}") }
    }
}
