package app.ceres.reader.rule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXNode

/**
 * Legado "default" rule syntax plus `@CSS:` selectors.
 *
 * Default: segments separated by `@`, each `type.name.index` where type is
 * class / id / tag / text / children, or a raw CSS selector. Indexes: `.0`, `.-1`,
 * `!0:3` (exclude), `[0,2]`, `[1:5]`, `[!0]`, `[-1:0]` (ranges are inclusive).
 * The last segment of a string rule is the extractor: text, textNodes, ownText,
 * html, all, or an attribute name.
 */
class AnalyzeByJSoup(content: Any) {
    private val root: Element = toElement(content)

    fun getElements(rule: String): List<Element> = RuleSplitter.evaluate(rule) { single ->
        if (single.startsWith("@CSS:", true)) root.select(single.substring(5).trim())
        else selectDefault(root, single)
    }

    fun getStringList(rule: String): List<String> = RuleSplitter.evaluate(rule) { getStringsSingle(it) }

    fun getString(rule: String): String = getStringList(rule).joinToString("\n")

    fun getString0(rule: String): String = getStringList(rule).firstOrNull().orEmpty()

    private fun getStringsSingle(rule: String): List<String> {
        val isCss = rule.startsWith("@CSS:", true)
        val body = if (isCss) rule.substring(5).trim() else rule
        val at = body.lastIndexOf('@')
        val (elements, attr) = when {
            at < 0 && isCss -> root.select(body) to "text"
            at < 0 -> listOf(root) to body
            else -> {
                val elRule = body.substring(0, at)
                val els = if (elRule.isEmpty()) listOf(root)
                else if (isCss) root.select(elRule) else selectDefault(root, elRule)
                els to body.substring(at + 1)
            }
        }
        return extract(elements, attr.trim())
    }

    private fun extract(elements: List<Element>, attr: String): List<String> = when (attr) {
        "text" -> elements.map { it.text() }.filter { it.isNotEmpty() }
        "textNodes" -> elements.map { el ->
            el.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }.filter { it.isNotEmpty() }
        "ownText" -> elements.map { it.ownText() }.filter { it.isNotEmpty() }
        "html" -> {
            val els = Elements(elements.map { it.clone() })
            els.select("script, style").remove()
            listOf(els.outerHtml()).filter { it.isNotEmpty() }
        }
        "all" -> listOf(Elements(elements).outerHtml()).filter { it.isNotEmpty() }
        else -> elements.map { it.attr(attr) }.filter { it.isNotEmpty() }
    }

    companion object {
        fun toElement(content: Any): Element = when (content) {
            is Element -> content
            is JXNode -> if (content.isElement) content.asElement() else Jsoup.parse(content.toString())
            is TextNode -> Jsoup.parse(content.text())
            else -> {
                val s = content.toString()
                if (s.trimStart().startsWith("<?xml")) Jsoup.parse(s, "", org.jsoup.parser.Parser.xmlParser())
                else Jsoup.parse(s)
            }
        }

        fun selectDefault(root: Element, rule: String): List<Element> {
            var current: List<Element> = listOf(root)
            for (seg in splitSegments(rule)) {
                if (seg.isEmpty()) continue
                current = current.flatMap { applySegment(it, seg) }
                if (current.isEmpty()) break
            }
            return current
        }

        /** Splits on `@` outside of brackets. */
        private fun splitSegments(rule: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            rule.forEachIndexed { i, c ->
                when (c) {
                    '[', '(' -> depth++
                    ']', ')' -> if (depth > 0) depth--
                    '@' -> if (depth == 0) {
                        out.add(rule.substring(start, i)); start = i + 1
                    }
                }
            }
            out.add(rule.substring(start))
            return out.map { it.trim() }
        }

        /** A single index (`step == null`) or an inclusive range with optional open ends. */
        private class IndexItem(val start: Int?, val end: Int?, val step: Int?)

        private class IndexSpec(val exclude: Boolean, val items: List<IndexItem>)

        private fun applySegment(el: Element, segment: String): List<Element> {
            var base = segment
            var spec: IndexSpec? = null
            if (base.endsWith("]") && base.lastIndexOf('[') > 0 && !isCssAttrSelector(base)) {
                val open = base.lastIndexOf('[')
                spec = parseBracket(base.substring(open + 1, base.length - 1))
                base = base.substring(0, open)
            } else {
                val bang = base.lastIndexOf('!')
                if (bang > 0 && base.substring(bang + 1).matches(INDEX_LIST)) {
                    spec = IndexSpec(true, singles(base.substring(bang + 1)))
                    base = base.substring(0, bang)
                } else {
                    val dot = base.lastIndexOf('.')
                    if (dot > 0 && base.substring(dot + 1).matches(INDEX_LIST)) {
                        spec = IndexSpec(false, singles(base.substring(dot + 1)))
                        base = base.substring(0, dot)
                    }
                }
            }
            val parts = base.split('.', limit = 2)
            val name = parts.getOrNull(1).orEmpty()
            val found: List<Element> = when (parts[0]) {
                "children" -> el.children()
                "class" -> el.getElementsByClass(name)
                "tag" -> el.getElementsByTag(name)
                "id" -> listOfNotNull(el.getElementById(name))
                "text" -> el.getElementsContainingOwnText(name)
                else -> el.select(base)
            }
            return if (spec == null) found else filterIndex(found, spec)
        }

        private fun isCssAttrSelector(s: String): Boolean {
            val inner = s.substring(s.lastIndexOf('[') + 1, s.length - 1)
            return inner.isNotEmpty() && !inner.matches(Regex("!?\\s*-?\\d*(\\s*:\\s*-?\\d*){0,2}(\\s*,\\s*-?\\d*(\\s*:\\s*-?\\d*){0,2})*"))
        }

        private val INDEX_LIST = Regex("-?\\d+(:-?\\d+)*")

        /** `.0:2:5` / `!0:3` style: a colon-separated list of single indexes. */
        private fun singles(s: String) = s.split(':').map { val n = it.toInt(); IndexItem(n, n, null) }

        private fun parseBracket(inner: String): IndexSpec {
            val exclude = inner.trimStart().startsWith("!")
            val body = inner.trim().removePrefix("!")
            val items = body.split(',').map { part ->
                val p = part.trim()
                if (p.contains(':')) {
                    val nums = p.split(':').map { it.trim().toIntOrNull() }
                    IndexItem(nums.getOrNull(0), nums.getOrNull(1), nums.getOrNull(2) ?: 1)
                } else {
                    val n = p.toIntOrNull() ?: 0
                    IndexItem(n, n, null)
                }
            }
            return IndexSpec(exclude, items)
        }

        private fun filterIndex(list: List<Element>, spec: IndexSpec): List<Element> {
            val size = list.size
            if (size == 0) return list
            fun norm(i: Int) = if (i < 0) size + i else i
            val indexes = ArrayList<Int>()
            spec.items.forEach { item ->
                if (item.step == null) {
                    indexes.add(norm(item.start ?: 0))
                } else {
                    val s = item.start?.let(::norm) ?: 0
                    val e = item.end?.let(::norm) ?: (size - 1)
                    val st = kotlin.math.abs(item.step).coerceAtLeast(1)
                    if (s <= e) for (i in s..e step st) indexes.add(i)
                    else for (i in s downTo e step st) indexes.add(i)
                }
            }
            return if (spec.exclude) {
                val ex = indexes.toSet()
                list.filterIndexed { i, _ -> i !in ex }
            } else {
                indexes.filter { it in 0 until size }.map { list[it] }
            }
        }
    }
}
