package app.ceres.reader.data

import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.SourceEntity
import app.ceres.reader.data.model.SourceItem
import app.ceres.reader.rule.Http
import app.ceres.reader.util.GSON
import app.ceres.reader.util.parseBookSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

class SourceRepository(private val dao: SourceDao) {
    private val parsed = ConcurrentHashMap<String, Pair<String, BookSource>>()

    val items: Flow<List<SourceItem>> = dao.observeItems()

    suspend fun get(url: String): BookSource? = dao.get(url)?.let(::toSource)

    suspend fun enabled(): List<BookSource> = dao.enabled().map(::toSource)

    fun toSource(entity: SourceEntity): BookSource {
        parsed[entity.url]?.let { (json, src) -> if (json == entity.json) return src }
        val src = GSON.fromJson(entity.json, BookSource::class.java)
        parsed[entity.url] = entity.json to src
        return src
    }

    /** Imports a JSON array/object of Legado sources. Returns the number imported. */
    suspend fun importJson(json: String): Int {
        val entities = withContext(Dispatchers.Default) {
            parseBookSources(json).map { s ->
                SourceEntity(
                    url = s.bookSourceUrl,
                    name = s.bookSourceName.ifBlank { s.bookSourceUrl },
                    group = s.bookSourceGroup,
                    enabled = s.enabled,
                    sortOrder = s.customOrder,
                    hasReview = s.hasReview(),
                    json = s.toJson(),
                ).also { e -> parsed[e.url] = e.json to s }
            }
        }
        if (entities.isEmpty()) throw IllegalArgumentException("没有识别到书源（需要阅读格式的 JSON）")
        dao.upsertAll(entities)
        return entities.size
    }

    suspend fun importFromUrl(url: String): Int {
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url.trim()).header("User-Agent", Http.DEFAULT_UA).build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body.string()
            }
        }
        return importJson(body)
    }

    suspend fun importStream(open: () -> java.io.InputStream?): Int {
        val text = withContext(Dispatchers.IO) { open()?.use { it.readBytes().decodeToString() } }
            ?: throw java.io.IOException("无法读取文件")
        return importJson(text)
    }

    suspend fun exportAll(): String {
        val list = dao.enabled().map { toSource(it) }
        return GSON.toJson(list)
    }

    suspend fun setEnabled(url: String, enabled: Boolean) = dao.setEnabled(url, enabled)
    suspend fun setAllEnabled(enabled: Boolean) = dao.setAllEnabled(enabled)
    suspend fun delete(url: String) = dao.delete(url)
}
