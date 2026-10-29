package app.ceres.reader.util

import java.net.URLDecoder

/**
 * One-click import links from source collection sites:
 * `legado://import/bookSource?src=URL` and the older `yuedu://booksource/importonline?src=URL`.
 */
data class ImportLink(val type: String, val src: String) {
    val isBookSource: Boolean get() = type.equals("bookSource", true)

    companion object {
        fun parse(link: String): ImportLink? {
            val s = link.trim()
            val scheme = s.substringBefore("://", "").lowercase()
            if (scheme != "legado" && scheme != "yuedu") return null
            val path = s.substringAfter("://").substringBefore('?').trim('/')
            val type = when {
                scheme == "legado" && path.startsWith("import/", true) -> path.substringAfter('/')
                scheme == "yuedu" -> when (path.substringBefore('/').lowercase()) {
                    "booksource" -> "bookSource"
                    "rsssource" -> "rssSource"
                    "replacerule" -> "replaceRule"
                    else -> path.substringBefore('/')
                }
                else -> return null
            }
            val raw = s.substringAfter('?', "").split('&').firstOrNull { it.startsWith("src=") }
                ?.let { s.substringAfter("src=") } ?: return null
            // Collection sites send `src` both encoded and raw (raw URLs may contain `&`).
            val src = if (raw.contains("://")) raw
            else runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (!src.startsWith("http", true)) return null
            return ImportLink(type, src)
        }
    }
}
