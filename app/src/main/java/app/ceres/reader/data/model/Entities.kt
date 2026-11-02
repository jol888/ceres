package app.ceres.reader.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ceres.reader.util.parseStringMap
import app.ceres.reader.util.toJsonString

/** Objects that rules can store variables on via `@put` / `java.put`. */
interface RuleData {
    var variable: String?

    fun putVariable(key: String, value: String?): Boolean {
        val map = parseStringMap(variable).toMutableMap()
        if (value == null) map.remove(key) else map[key] = value
        variable = if (map.isEmpty()) null else toJsonString(map)
        return true
    }

    fun getVariable(key: String): String = parseStringMap(variable)[key].orEmpty()
}

const val LOCAL_ORIGIN = "local"

@Entity(tableName = "books")
data class Book(
    @PrimaryKey var bookUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var tocUrl: String = "",
    var latestChapterTitle: String? = null,
    var wordCount: String? = null,
    var score: String? = null,
    var totalChapterNum: Int = 0,
    var durChapterIndex: Int = 0,
    var durChapterPos: Int = 0,
    var durChapterTitle: String? = null,
    var durChapterTime: Long = 0,
    var inShelf: Boolean = false,
    var addTime: Long = System.currentTimeMillis(),
    override var variable: String? = null,
) : RuleData {
    val isLocal: Boolean get() = origin == LOCAL_ORIGIN

    /** Set by TOC rules via `book.setReverseToc(true)`; flips the parsed chapter order. */
    @Ignore
    var reverseToc: Boolean = false
}

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookUrl", "index"],
    indices = [Index("bookUrl")],
)
data class BookChapter(
    var bookUrl: String = "",
    var index: Int = 0,
    var url: String = "",
    var title: String = "",
    var baseUrl: String = "",
    var isVolume: Boolean = false,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var tag: String? = null,
    override var variable: String? = null,
) : RuleData

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val url: String,
    val name: String,
    val group: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val hasReview: Boolean,
    val json: String,
    /** Last local change (import / enable toggle); newer side wins in WebDAV sync. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** List row for the sources screen; leaves out the (large) `json` column. */
data class SourceItem(
    val url: String,
    val name: String,
    val group: String?,
    val enabled: Boolean,
    val hasReview: Boolean,
)

@Entity(tableName = "kv")
data class KvEntry(
    @PrimaryKey val key: String,
    val value: String,
)

/** Transient search result; converted to [Book] when opened. */
data class SearchBook(
    var bookUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var wordCount: String? = null,
    var score: String? = null,
    var latestChapterTitle: String? = null,
    var tocUrl: String = "",
    override var variable: String? = null,
) : RuleData {
    fun toBook() = Book(
        bookUrl = bookUrl, origin = origin, originName = originName, name = name,
        author = author, kind = kind, coverUrl = coverUrl, intro = intro, wordCount = wordCount,
        score = score, latestChapterTitle = latestChapterTitle, tocUrl = tocUrl, variable = variable,
    )
}

/** "评分 9.5 · 320.1万字"; null when the source gave neither. */
fun bookStats(score: String?, wordCount: String?): String? {
    val parts = listOfNotNull(
        score?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }?.let { "评分 $it" },
        wordCount?.trim()?.takeIf { it.isNotEmpty() && it != "0" }?.let { if (it.endsWith("字")) it else "${it}字" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
