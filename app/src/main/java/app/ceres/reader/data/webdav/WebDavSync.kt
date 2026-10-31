package app.ceres.reader.data.webdav

import app.ceres.reader.data.BookRepository
import app.ceres.reader.data.Settings
import app.ceres.reader.data.WebDavSettings
import app.ceres.reader.data.model.Book
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

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val merged: Int,
    val message: String,
)

class WebDavSync(
    private val books: BookRepository,
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
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(
                uploaded = merged.books.size,
                downloaded = remote.books.size,
                merged = merged.books.size,
                message = "已同步 ${merged.books.size} 本（云端 ${remote.books.size} → 合并 ${merged.books.size}）",
            )
        }
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
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(snapshot.books.size, 0, snapshot.books.size, "已上传书架 ${snapshot.books.size} 本")
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
            settings.touchWebDavSync(System.currentTimeMillis())
            SyncResult(0, remote.books.size, merged.books.size, "已下载并合并 ${merged.books.size} 本")
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
        private const val REMOVAL_KEEP_MS = 90L * 24 * 3600 * 1000

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
            latestChapterTitle = latestChapterTitle, wordCount = wordCount, totalChapterNum = totalChapterNum,
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
