package app.ceres.reader.util

import org.jsoup.parser.Parser
import java.net.URI
import java.net.URL
import java.security.MessageDigest

fun md5(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

fun absoluteUrl(base: String?, relative: String?): String {
    val rel = relative?.trim().orEmpty()
    if (rel.isEmpty()) return base.orEmpty()
    if (rel.startsWith("http://", true) || rel.startsWith("https://", true) ||
        rel.startsWith("data:", true) || rel.startsWith("local:", true) ||
        rel.startsWith("fanqie:", true)
    ) return rel
    if (base.isNullOrBlank()) return rel
    val cleanBase = base.substringBefore(",{").trim()
    return runCatching { URL(URL(cleanBase), rel).toString() }.getOrElse { rel }
}

fun hostOf(url: String): String = runCatching { URI(url.substringBefore(",{").trim()).host }
    .getOrNull().orEmpty()

private val blockTag = Regex("(?i)<(br|/?p|/?div|/?h[1-6]|/?li|/?tr|/?section|/?article)(\\s[^>]*)?/?>")
private val anyTag = Regex("(?s)<[^>]+>")
private val commentOrScript = Regex("(?is)<!--.*?-->|<script.*?</script>|<style.*?</style>")

/** Converts HTML (or plain text) chapter content into clean paragraphs. */
fun htmlToParagraphs(html: String): List<String> {
    var s = html
    if (s.contains('<')) {
        s = commentOrScript.replace(s, "")
        s = blockTag.replace(s, "\n")
        s = anyTag.replace(s, "")
    }
    if (s.contains('&')) s = Parser.unescapeEntities(s, false)
    return s.replace("\r", "").split('\n')
        .map { it.trim { c -> c.isWhitespace() || c == '\u3000' || c == '\u00A0' } }
        .filter { it.isNotEmpty() }
}

fun htmlFormat(html: String): String = htmlToParagraphs(html).joinToString("\n") { "\u3000\u3000$it" }

fun formatBookName(name: String?): String = name.orEmpty()
    .replace(Regex("\\s+作\\s*者.*|\\s+\\S+\\s+著"), "")
    .trim()

fun formatAuthor(author: String?): String = author.orEmpty()
    .replace(Regex("^(作\\s*者|著\\s*者)[：:\\s]*"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
