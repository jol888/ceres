package app.ceres.reader.data.webdav

import app.ceres.reader.data.BookRepository
import app.ceres.reader.data.Settings
import app.ceres.reader.data.SourceRepository
import app.ceres.reader.data.SourceSubscription
import app.ceres.reader.data.WebDavSettings
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.SourceEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncBook(
    val bookUrl: String = "",
    val origin: String = "",
    val originName: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val tocUrl: String = "",
    val latestChapterTitle: String? = null,
    val wordCount: String? = null,
    val score: String? = null,
    val totalChapterNum: Int = 0,
    val durChapterIndex: Int = 0,
    val durChapterPos: Int = 0,
    val durChapterTitle: String? = null,
    val durChapterTime: Long = 0,
    val addTime: Long = 0,
    val variable: String? = null,
)

data class SyncRemoval(
    val bookUrl: String = "",
    val removedAt: Long = 0,
)

data class SyncSnapshot(
    val version: Int = 1,
    val app: String = "ceres",
    val updatedAt: Long = 0,
    val books: List<SyncBook> = emptyList(),
    val removed: List<SyncRemoval> = emptyList(),
)

data class SyncSource(
    val url: String = "",
    val name: String = "",
    val group: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val hasReview: Boolean = false,
    /** Full Legado source JSON. */
    val json: String = "",
    val updatedAt: Long = 0,
)

data class SourceSnapshot(
    val version: Int = 1,
    val app: String = "ceres",
    val updatedAt: Long = 0,
    val sources: List<SyncSource> = emptyList(),
    val removed: List<SyncRemoval> = emptyList(),
    val subscriptions: List<SourceSubscription> = emptyList(),
)

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val merged: Int,
    val message: String,
)

class WebDavSync(
    private val books: BookRepository,
    private val sources: SourceRepository,
    private val settings: Settings,
) {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val mutex = Mutex()

    fun configured(): Boolean = settings.webDav.value.let { it.enabled && it.url.isNotBlank() }

    suspend fun test(): String = withContext(Dispatchers.IO) {
        client().test()
    }

    suspend fun sync(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = settings.webDav.value
            requireConfigured(cfg)
            val client = client(cfg)
            client.ensureDir(DIR)

            val localBooks = books.shelfList()
            val localRemoved = settings.webDavRemovals()
            val remote = download(client) ?: SyncSnapshot()
            val merged = merge(localBooks, localRemoved, remote)
            applyLocal(merged)
            upload(client, merged)
            settings.clearWebDavRemovals(merged.removed.map { it.bookUrl }.toSet())
            val sourceCount = syncSources(client, upload = true)
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(
                uploaded = merged.books.size,
                downloaded = remote.books.size,
                merged = merged.books.size,
                message = "已同步 ${merged.books.size} 本（云端 ${remote.books.size} → 合并 ${merged.books.size}）、$sourceCount 个书源",
            )
        }
    }

    /** Merges the shared source file into local sources; returns the merged source count. */
    private suspend fun syncSources(client: WebDavClient, upload: Boolean): Int {
        val remote = downloadSources(client) ?: SourceSnapshot()
        val local = localSourceSnapshot()
        val merged = mergeSources(local, remote)
        val localByUrl = local.sources.associateBy { it.url }
        val upserts = merged.sources.filter { localByUrl[it.url] != it }.map { it.toEntity() }
        val keep = merged.sources.map { it.url }.toSet()
        val deletes = local.sources.map { it.url }.filter { it !in keep }
        sources.applySynced(upserts, deletes)
        settings.setSourceRemovals(merged.removed)
        settings.setSourceSubscriptions(merged.subscriptions)
        if (upload) uploadSources(client, merged)
        return merged.sources.size
    }

    private suspend fun localSourceSnapshot() = SourceSnapshot(
        sources = sources.allEntities().map { it.toSync() },
        removed = settings.sourceRemovals(),
        subscriptions = settings.sourceSubscriptions(),
    )

    /** Uploads a timestamped backup next to the shelf file; returns its remote path. */
    suspend fun uploadBackup(fileName: String, json: String): String = withContext(Dispatchers.IO) {
        val cfg = settings.webDav.value
        requireConfigured(cfg)
        val client = client(cfg)
        client.ensureDir(BACKUP_DIR)
        val path = "$BACKUP_DIR/$fileName"
        client.putText(path, json)
        path
    }

    suspend fun uploadOnly(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = settings.webDav.value
            requireConfigured(cfg)
            val client = client(cfg)
            client.ensureDir(DIR)
            val remote = download(client) ?: SyncSnapshot()
            val localBooks = books.shelfList()
            val localRemoved = settings.webDavRemovals()
            // Keep remote removals that are still newer; overlay local shelf as source of truth for books list.
            val snapshot = SyncSnapshot(
                updatedAt = System.currentTimeMillis(),
                books = localBooks.map { it.toSync() },
                removed = mergeRemovals(localRemoved, remote.removed, localBooks.map { it.bookUrl }.toSet()),
            )
            upload(client, snapshot)
            settings.clearWebDavRemovals(snapshot.removed.map { it.bookUrl }.toSet())
            // Local sources overwrite the cloud list, keeping still-relevant remote tombstones.
            val local = localSourceSnapshot()
            val remoteSources = downloadSources(client) ?: SourceSnapshot()
            val present = local.sources.map { it.url }.toSet()
            uploadSources(
                client,
                local.copy(
                    removed = mergeRemovals(local.removed, remoteSources.removed, present),
                    subscriptions = mergeSubscriptions(local.subscriptions, remoteSources.subscriptions),
                ),
            )
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(
                snapshot.books.size, 0, snapshot.books.size,
                "已上传书架 ${snapshot.books.size} 本、${local.sources.size} 个书源",
            )
        }
    }

    suspend fun downloadOnly(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = settings.webDav.value
            requireConfigured(cfg)
            val client = client(cfg)
            val remote = download(client) ?: throw WebDavException("云端尚无书架数据")
            val localBooks = books.shelfList()
            val localRemoved = settings.webDavRemovals()
            val merged = merge(localBooks, localRemoved, remote)
            applyLocal(merged)
            val sourceCount = syncSources(client, upload = false)
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(0, remote.books.size, merged.books.size, "已下载并合并 ${merged.books.size} 本、$sourceCount 个书源")
        }
    }

    /** Debounced progress upload after reading; no-op if auto sync off. */
    suspend fun pushProgressIfNeeded() {
        val cfg = settings.webDav.value
        if (!cfg.enabled || !cfg.autoSync || cfg.url.isBlank()) return
        runCatching { sync() }
    }

    suspend fun noteRemoval(bookUrl: String) {
        settings.addWebDavRemoval(bookUrl, System.currentTimeMillis())
        val cfg = settings.webDav.value
        if (cfg.enabled && cfg.autoSync && cfg.url.isNotBlank()) {
            runCatching { sync() }
        }
    }

    private fun requireConfigured(cfg: WebDavSettings) {
        if (!cfg.enabled) throw WebDavException("未启用 WebDAV")
        if (cfg.url.isBlank()) throw WebDavException("请填写 WebDAV 地址")
    }

    private fun client(cfg: WebDavSettings = settings.webDav.value) =
        WebDavClient(cfg.url, cfg.username, cfg.password)

    private fun download(client: WebDavClient): SyncSnapshot? {
        val text = client.getText(FILE) ?: return null
        return runCatching { gson.fromJson(text, SyncSnapshot::class.java) }
            .getOrElse { throw WebDavException("云端书架 JSON 无法解析：${it.message}") }
    }

    private fun upload(client: WebDavClient, snapshot: SyncSnapshot) {
        client.putText(FILE, gson.toJson(snapshot.copy(updatedAt = System.currentTimeMillis(), app = "ceres", version = 1)))
    }

    private fun downloadSources(client: WebDavClient): SourceSnapshot? {
        val text = client.getText(SOURCES_FILE) ?: return null
        return runCatching { gson.fromJson(text, SourceSnapshot::class.java) }
            .getOrElse { throw WebDavException("云端书源 JSON 无法解析：${it.message}") }
    }

    private fun uploadSources(client: WebDavClient, snapshot: SourceSnapshot) {
        client.putText(SOURCES_FILE, gson.toJson(snapshot.copy(updatedAt = System.currentTimeMillis(), app = "ceres", version = 1)))
    }

    private suspend fun applyLocal(snapshot: SyncSnapshot) {
        val remoteUrls = snapshot.books.map { it.bookUrl }.toSet()
        val removedUrls = snapshot.removed.map { it.bookUrl }.toSet()
        for (sb in snapshot.books) {
            val existing = books.get(sb.bookUrl)
            val book = sb.toBook(existing)
            book.inShelf = true
            books.save(book)
        }
        // Removals: drop from shelf if still present.
        for (url in removedUrls) {
            if (url in remoteUrls) continue
            val b = books.get(url) ?: continue
            if (b.inShelf) books.delete(b)
        }
    }

    companion object {
        const val DIR = "ceres"
        const val FILE = "ceres/bookshelf.json"
        const val SOURCES_FILE = "ceres/sources.json"
        const val BACKUP_DIR = "ceres/backups"
        private const val REMOVAL_KEEP_MS = 90L * 24 * 3600 * 1000

        /** Per source the newer change wins; a tombstone newer than the source's change deletes it. */
        fun mergeSources(local: SourceSnapshot, remote: SourceSnapshot): SourceSnapshot {
            val byUrl = LinkedHashMap<String, SyncSource>()
            for (s in remote.sources + local.sources) {
                if (s.url.isBlank() || s.json.isBlank()) continue
                val cur = byUrl[s.url]
                // local comes second, so it wins ties
                if (cur == null || s.updatedAt >= cur.updatedAt) byUrl[s.url] = s
            }
            val removed = mergeRemovals(local.removed, remote.removed, emptySet())
                .associate { it.bookUrl to it.removedAt }.toMutableMap()
            val kept = byUrl.values.filter { s ->
                val removedAt = removed[s.url] ?: return@filter true
                if (removedAt >= s.updatedAt) false else { removed.remove(s.url); true }
            }
            return SourceSnapshot(
                updatedAt = System.currentTimeMillis(),
                sources = kept.sortedWith(compareBy({ it.sortOrder }, { it.name })),
                removed = removed.map { SyncRemoval(it.key, it.value) }.sortedByDescending { it.removedAt },
                subscriptions = mergeSubscriptions(local.subscriptions, remote.subscriptions),
            )
        }

        fun mergeSubscriptions(a: List<SourceSubscription>, b: List<SourceSubscription>): List<SourceSubscription> {
            val map = LinkedHashMap<String, Long>()
            (a + b).forEach { s ->
                val url = s.url.trim()
                if (url.isEmpty()) return@forEach
                val prev = map[url]
                map[url] = if (prev == null || prev == 0L) s.addedAt else if (s.addedAt == 0L) prev else minOf(prev, s.addedAt)
            }
            return map.map { SourceSubscription(it.key, it.value) }.sortedBy { it.addedAt }
        }

        fun SourceEntity.toSync() = SyncSource(
            url = url, name = name, group = group, enabled = enabled, sortOrder = sortOrder,
            hasReview = hasReview, json = json, updatedAt = updatedAt,
        )

        fun SyncSource.toEntity() = SourceEntity(
            url = url, name = name.ifBlank { url }, group = group, enabled = enabled, sortOrder = sortOrder,
            hasReview = hasReview, json = json, updatedAt = updatedAt,
        )

        fun merge(
            localBooks: List<Book>,
            localRemoved: List<SyncRemoval>,
            remote: SyncSnapshot,
        ): SyncSnapshot {
            val removedMap = LinkedHashMap<String, Long>()
            mergeRemovals(localRemoved, remote.removed, emptySet()).forEach {
                removedMap[it.bookUrl] = it.removedAt
            }

            val bookMap = LinkedHashMap<String, Book>()
            for (b in localBooks) bookMap[b.bookUrl] = b.copy(inShelf = true)
            for (sb in remote.books) {
                val remoteBook = sb.toBook(bookMap[sb.bookUrl])
                val cur = bookMap[sb.bookUrl]
                bookMap[sb.bookUrl] = if (cur == null) remoteBook else pickNewer(cur, remoteBook)
            }

            val now = System.currentTimeMillis()
            removedMap.entries.removeIf { (_, at) -> now - at > REMOVAL_KEEP_MS }

            val books = bookMap.values.filter { book ->
                val removedAt = removedMap[book.bookUrl] ?: return@filter true
                // Tombstone wins only if newer than progress / add time.
                val stamp = maxOf(book.durChapterTime, book.addTime)
                if (removedAt >= stamp) {
                    false
                } else {
                    removedMap.remove(book.bookUrl)
                    true
                }
            }.sortedByDescending { it.durChapterTime }

            return SyncSnapshot(
                updatedAt = System.currentTimeMillis(),
                books = books.map { it.toSync() },
                removed = removedMap.map { SyncRemoval(it.key, it.value) }
                    .sortedByDescending { it.removedAt },
            )
        }

        fun mergeRemovals(
            local: List<SyncRemoval>,
            remote: List<SyncRemoval>,
            presentUrls: Set<String>,
        ): List<SyncRemoval> {
            val map = LinkedHashMap<String, Long>()
            (remote + local).forEach { r ->
                if (r.bookUrl.isBlank()) return@forEach
                map[r.bookUrl] = maxOf(map[r.bookUrl] ?: 0L, r.removedAt)
            }
            // Still on shelf → drop stale tombstone.
            presentUrls.forEach { map.remove(it) }
            val now = System.currentTimeMillis()
            return map.map { SyncRemoval(it.key, it.value) }
                .filter { now - it.removedAt <= REMOVAL_KEEP_MS }
                .sortedByDescending { it.removedAt }
        }

        fun pickNewer(a: Book, b: Book): Book {
            val (newer, older) = when {
                b.durChapterTime > a.durChapterTime -> b to a
                b.durChapterTime < a.durChapterTime -> a to b
                b.addTime > a.addTime -> b to a
                else -> a to b
            }
            return newer.copy(
                inShelf = true,
                name = newer.name.ifBlank { older.name },
                author = newer.author.ifBlank { older.author },
                origin = newer.origin.ifBlank { older.origin },
                originName = newer.originName.ifBlank { older.originName },
                kind = newer.kind ?: older.kind,
                coverUrl = newer.coverUrl ?: older.coverUrl,
                intro = newer.intro ?: older.intro,
                tocUrl = newer.tocUrl.ifBlank { older.tocUrl },
                latestChapterTitle = newer.latestChapterTitle ?: older.latestChapterTitle,
                wordCount = newer.wordCount ?: older.wordCount,
                score = newer.score ?: older.score,
                totalChapterNum = maxOf(newer.totalChapterNum, older.totalChapterNum),
                addTime = when {
                    newer.addTime == 0L -> older.addTime
                    older.addTime == 0L -> newer.addTime
                    else -> minOf(newer.addTime, older.addTime)
                },
                variable = newer.variable ?: older.variable,
            )
        }

        fun Book.toSync() = SyncBook(
            bookUrl = bookUrl, origin = origin, originName = originName, name = name, author = author,
            kind = kind, coverUrl = coverUrl, intro = intro, tocUrl = tocUrl,
            latestChapterTitle = latestChapterTitle, wordCount = wordCount, score = score, totalChapterNum = totalChapterNum,
            durChapterIndex = durChapterIndex, durChapterPos = durChapterPos,
            durChapterTitle = durChapterTitle, durChapterTime = durChapterTime,
            addTime = addTime, variable = variable,
        )

        fun SyncBook.toBook(existing: Book?): Book {
            val base = existing ?: Book(bookUrl = bookUrl)
            return base.copy(
                bookUrl = bookUrl.ifBlank { base.bookUrl },
                origin = origin.ifBlank { base.origin },
                originName = originName.ifBlank { base.originName },
                name = name.ifBlank { base.name },
                author = author.ifBlank { base.author },
                kind = kind ?: base.kind,
                coverUrl = coverUrl ?: base.coverUrl,
                intro = intro ?: base.intro,
                tocUrl = tocUrl.ifBlank { base.tocUrl },
                latestChapterTitle = latestChapterTitle ?: base.latestChapterTitle,
                wordCount = wordCount ?: base.wordCount,
                score = score ?: base.score,
                totalChapterNum = maxOf(totalChapterNum, base.totalChapterNum),
                durChapterIndex = durChapterIndex,
                durChapterPos = durChapterPos,
                durChapterTitle = durChapterTitle,
                durChapterTime = durChapterTime,
                inShelf = true,
                addTime = if (addTime > 0) addTime else base.addTime,
                variable = variable ?: base.variable,
            )
        }
    }
}
