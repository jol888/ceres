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

class SourceRepository(
    private val dao: SourceDao,
    private val settings: Settings,
    private val openAsset: (String) -> java.io.InputStream,
) {
    private val parsed = ConcurrentHashMap<String, Pair<String, BookSource>>()

    val items: Flow<List<SourceItem>> = dao.observeItems()

    suspend fun get(url: String): BookSource? = dao.get(url)?.let(::toSource)

    suspend fun enabled(): List<BookSource> = dao.enabled().map(::toSource)

    suspend fun allEntities(): List<SourceEntity> = dao.all()

    fun toSource(entity: SourceEntity): BookSource {
        parsed[entity.url]?.let { (json, src) -> if (json == entity.json) return src }
        val src = GSON.fromJson(entity.json, BookSource::class.java)
        parsed[entity.url] = entity.json to src
        return src
    }

    private fun toEntity(s: BookSource, at: Long, enabled: Boolean = s.enabled) = SourceEntity(
        url = s.bookSourceUrl,
        name = s.bookSourceName.ifBlank { s.bookSourceUrl },
        group = s.bookSourceGroup,
        enabled = enabled,
        sortOrder = s.customOrder,
        hasReview = s.hasReview(),
        json = s.toJson(),
        updatedAt = at,
    ).also { e -> parsed[e.url] = e.json to s }

    /** Imports a JSON array/object of Legado sources. Returns the number imported. */
    suspend fun importJson(json: String): Int {
        val now = System.currentTimeMillis()
        val entities = withContext(Dispatchers.Default) { parseBookSources(json).map { toEntity(it, now) } }
        if (entities.isEmpty()) throw IllegalArgumentException("没有识别到书源（需要阅读格式的 JSON）")
        dao.upsertAll(entities)
        settings.dropSourceRemovals(entities.map { it.url })
        return entities.size
    }

    /** Imports the bundled Fanqie source; reached through the `fanqie://<name>.json` shorthand. */
    suspend fun importBuiltinFanqie(): Int = importStream { openAsset(FANQIE_ASSET) }

    /** The Fanqie source, importing the bundled one first if it is missing. */
    suspend fun fanqie(): BookSource {
        get(FANQIE_SOURCE_URL)?.let { return it }
        importBuiltinFanqie()
        return get(FANQIE_SOURCE_URL) ?: throw IllegalStateException("内置番茄书源导入失败")
    }

    suspend fun importFromUrl(url: String): Int {
        if (isBuiltinFanqie(url)) return importBuiltinFanqie()
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url.trim()).header("User-Agent", Http.DEFAULT_UA).build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body.string()
            }
        }
        return importJson(body).also { settings.addSourceSubscription(url) }
    }

    suspend fun importStream(open: () -> java.io.InputStream?): Int {
        val text = withContext(Dispatchers.IO) { open()?.use { it.readBytes().decodeToString() } }
            ?: throw java.io.IOException("无法读取文件")
        return importJson(text)
    }

    /** All sources as Legado JSON objects, with the current enabled state written back in. */
    suspend fun exportSources(): List<BookSource> =
        dao.all().map { toSource(it).copy(enabled = it.enabled) }

    suspend fun setEnabled(url: String, enabled: Boolean) = dao.setEnabled(url, enabled, System.currentTimeMillis())
    suspend fun setAllEnabled(enabled: Boolean) = dao.setAllEnabled(enabled, System.currentTimeMillis())

    suspend fun delete(url: String) {
        dao.delete(url)
        settings.addSourceRemoval(url, System.currentTimeMillis())
    }

    /** Writes sources pulled from sync as-is (keeps their remote change time). */
    suspend fun applySynced(upserts: List<SourceEntity>, deletes: List<String>) {
        if (upserts.isNotEmpty()) dao.upsertAll(upserts)
        if (deletes.isNotEmpty()) dao.deleteAll(deletes)
    }

    companion object {
        const val FANQIE_ASSET = "fanqie_novel_source.json"
        const val FANQIE_SOURCE_URL = "https://fanqienovel.com"
        private val FANQIE_SHORTHAND = Regex("^fanqie://[^/?#\\s]+\\.json$", RegexOption.IGNORE_CASE)

        /** `fanqie://<anything>.json` is shorthand for the bundled Fanqie source. */
        fun isBuiltinFanqie(url: String): Boolean = FANQIE_SHORTHAND.matches(url.trim())
    }
}
