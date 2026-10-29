package app.ceres.reader.data

import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.SourceEntity
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

    fun observeAll(): Flow<List<SourceEntity>> = dao.observeAll()

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
        val sources = withContext(Dispatchers.Default) { parseBookSources(json) }
        val entities = sources.map { s ->
            SourceEntity(
                url = s.bookSourceUrl,
                name = s.bookSourceName.ifBlank { s.bookSourceUrl },
                group = s.bookSourceGroup,
                enabled = s.enabled,
                sortOrder = s.customOrder,
                hasReview = s.hasReview(),
                json = s.toJson(),
            )
        }
        dao.upsertAll(entities)
        return entities.size
    }

    suspend fun importFromUrl(url: String): Int {
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url.trim()).header("User-Agent", Http.DEFAULT_UA).build()
            Http.client.newCall(req).execute().use { it.body.string() }
        }
        return importJson(body)
    }

    suspend fun exportAll(): String {
        val list = dao.enabled().map { toSource(it) }
        return GSON.toJson(list)
    }

    suspend fun setEnabled(url: String, enabled: Boolean) = dao.setEnabled(url, enabled)
    suspend fun setAllEnabled(enabled: Boolean) = dao.setAllEnabled(enabled)
    suspend fun delete(url: String) = dao.delete(url)
}
