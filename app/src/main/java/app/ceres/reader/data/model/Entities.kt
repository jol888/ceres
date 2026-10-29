package app.ceres.reader.data.model

import androidx.room.Entity
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
    var latestChapterTitle: String? = null,
    var tocUrl: String = "",
    override var variable: String? = null,
) : RuleData {
    fun toBook() = Book(
        bookUrl = bookUrl, origin = origin, originName = originName, name = name,
        author = author, kind = kind, coverUrl = coverUrl, intro = intro, wordCount = wordCount,
        latestChapterTitle = latestChapterTitle, tocUrl = tocUrl, variable = variable,
    )
}
