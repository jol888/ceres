package app.ceres.reader.data

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.source.ReviewLoader
import app.ceres.reader.source.ReviewPage
import app.ceres.reader.source.ReviewSummary
import app.ceres.reader.source.SourceException
import app.ceres.reader.source.WebBook
import app.ceres.reader.util.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BookRepository(
    private val db: AppDatabase,
    private val sources: SourceRepository,
    private val cacheRoot: File,
) {
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()

    fun shelf(): Flow<List<Book>> = bookDao.shelf()
    fun observe(url: String): Flow<Book?> = bookDao.observe(url)
    suspend fun get(url: String): Book? = bookDao.get(url)
    suspend fun save(book: Book) = bookDao.upsert(book)
    suspend fun chapters(bookUrl: String): List<BookChapter> = chapterDao.list(bookUrl)

    suspend fun saveProgress(book: Book, index: Int, pos: Int, title: String?) {
        book.durChapterIndex = index
        book.durChapterPos = pos
        book.durChapterTitle = title
        book.durChapterTime = System.currentTimeMillis()
        bookDao.saveProgress(book.bookUrl, index, pos, title, book.durChapterTime)
    }

    suspend fun delete(book: Book) {
        bookDao.delete(book.bookUrl)
        chapterDao.deleteByBook(book.bookUrl)
        bookDir(book).deleteRecursively()
    }

    private suspend fun source(book: Book) =
        sources.get(book.origin) ?: throw SourceException("书源不存在或已删除: ${book.originName}")

    /** Refreshes book info and TOC from the source, persisting both. */
    suspend fun refresh(book: Book, infoToo: Boolean = true): List<BookChapter> {
        if (book.isLocal) return chapters(book.bookUrl)
        val src = source(book)
        val chapters = runInterruptible(Dispatchers.IO) {
            val body = if (infoToo || book.tocUrl.isBlank()) WebBook.getBookInfo(src, book) else null
            WebBook.getChapterList(src, book, body)
        }
        bookDao.upsert(book)
        chapterDao.replace(book.bookUrl, chapters)
        return chapters
    }

    // ---------------- content cache ----------------

    fun bookDir(book: Book) = File(cacheRoot, md5(book.bookUrl))
    private fun chapterFile(book: Book, chapter: BookChapter) = File(bookDir(book), md5(chapter.url) + ".txt")

    fun isCached(book: Book, chapter: BookChapter) = chapterFile(book, chapter).exists()

    fun cachedCount(book: Book): Int = bookDir(book).list()?.size ?: 0

    fun writeCache(book: Book, chapter: BookChapter, paragraphs: List<String>) {
        val f = chapterFile(book, chapter)
        f.parentFile?.mkdirs()
        f.writeText(paragraphs.joinToString("\n"))
    }

    fun clearCache(book: Book) {
        if (!book.isLocal) bookDir(book).deleteRecursively()
    }

    private val inFlight = ConcurrentHashMap<String, Mutex>()

    suspend fun content(book: Book, chapter: BookChapter, next: BookChapter?, useCache: Boolean = true): List<String> {
        val f = chapterFile(book, chapter)
        if (useCache && f.exists()) return readCache(f)
        if (book.isLocal) throw SourceException("本地章节内容缺失")
        val lock = inFlight.getOrPut(f.path) { Mutex() }
        try {
            return lock.withLock {
                if (useCache && f.exists()) return@withLock readCache(f)
                val src = source(book)
                val paragraphs = runInterruptible(Dispatchers.IO) {
                    WebBook.getContent(src, book, chapter, next?.url)
                }
                if (paragraphs.isNotEmpty()) runInterruptible(Dispatchers.IO) { writeCache(book, chapter, paragraphs) }
                paragraphs
            }
        } finally {
            if (!lock.isLocked) inFlight.remove(f.path, lock)
        }
    }

    private suspend fun readCache(f: File) =
        runInterruptible(Dispatchers.IO) { f.readText().split('\n').filter { it.isNotEmpty() } }

    // ---------------- paragraph comments ----------------

    suspend fun reviewSummary(book: Book, chapter: BookChapter): ReviewSummary {
        if (book.isLocal) return ReviewSummary.EMPTY
        val src = sources.get(book.origin) ?: return ReviewSummary.EMPTY
        if (!src.hasReview()) return ReviewSummary.EMPTY
        return runInterruptible(Dispatchers.IO) { ReviewLoader.summary(src, book, chapter) }
    }

    suspend fun reviewPage(
        book: Book, chapter: BookChapter, paraIndex: Int, paraData: String, page: Int, pageUrl: String?,
    ): ReviewPage {
        val src = source(book)
        return runInterruptible(Dispatchers.IO) {
            ReviewLoader.detail(src, book, chapter, paraIndex, paraData, page, pageUrl)
        }
    }

    suspend fun hasReviewSource(book: Book): Boolean =
        !book.isLocal && sources.get(book.origin)?.hasReview() == true
}
