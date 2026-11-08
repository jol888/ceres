package app.ceres.reader.util

/**
 * Fanqie book ids from shared text: app share links (`…share-v2.html?book_id=…`),
 * web pages (`fanqienovel.com/page/<id>`), `fanqie://book/<id>`, or a bare id.
 */
object BookIdLink {
    private val QUERY = Regex("[?&#](?:book_id|bookId|bookid)=(\\d{6,})")
    private val PATH = Regex("(?:fanqienovel\\.com/page/|fanqie://(?:book|toc)/)(\\d{6,})", RegexOption.IGNORE_CASE)
    private val BARE = Regex("\\d{6,}")

    fun parse(text: String?): String? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        QUERY.find(s)?.let { return it.groupValues[1] }
        PATH.find(s)?.let { return it.groupValues[1] }
        return s.takeIf { BARE.matches(it) }
    }
}
