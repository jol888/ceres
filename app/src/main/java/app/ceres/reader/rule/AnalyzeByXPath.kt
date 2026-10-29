package app.ceres.reader.rule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXDocument
import org.seimicrawler.xpath.JXNode

class AnalyzeByXPath(content: Any) {
    private val node: Any = parse(content)

    private fun select(xpath: String): List<JXNode> = runCatching {
        when (node) {
            is JXNode -> node.sel(xpath)
            else -> (node as JXDocument).selN(xpath)
        }
    }.getOrNull().orEmpty()

    fun getElements(rule: String): List<JXNode> = RuleSplitter.evaluate(rule) { select(it) }

    fun getStringList(rule: String): List<String> = RuleSplitter.evaluate(rule) { single ->
        select(single).map { it.asString() }.filter { it.isNotEmpty() }
    }

    fun getString(rule: String): String = getStringList(rule).joinToString("\n")

    companion object {
        fun parse(content: Any): Any = when (content) {
            is JXDocument -> content
            is JXNode -> if (content.isElement) content else JXDocument.create(content.toString())
            is Document -> JXDocument.create(content)
            is Element -> JXDocument.create(Elements(content))
            else -> {
                var html = content.toString()
                // jsoup drops orphan table rows; wrap them so XPath can see them
                if (html.trimStart().startsWith("<tr")) html = "<table>$html</table>"
                else if (html.trimStart().startsWith("<td")) html = "<table><tr>$html</tr></table>"
                JXDocument.create(Jsoup.parse(html))
            }
        }
    }
}
