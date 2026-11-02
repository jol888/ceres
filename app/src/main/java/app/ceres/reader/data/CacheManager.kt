package app.ceres.reader.data

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CacheProgress(val done: Int, val total: Int, val failed: Int, val running: Boolean)

/** Downloads chapters in the background (app process scope). */
class CacheManager(private val scope: CoroutineScope, private val books: BookRepository) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, CacheProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CacheProgress>> = _progress.asStateFlow()

    fun isRunning(bookUrl: String) = jobs[bookUrl]?.isActive == true

    fun cancel(bookUrl: String) {
        jobs.remove(bookUrl)?.cancel()
        _progress.update { it - bookUrl }
    }

    /** Caches chapters [from, from + count) (or to the end when count is null). */
    fun download(book: Book, from: Int, count: Int?) {
        if (book.isLocal || isRunning(book.bookUrl)) return
        jobs[book.bookUrl] = scope.launch {
            val chapters = books.chapters(book.bookUrl)
            val end = if (count == null) chapters.size else minOf(chapters.size, from + count)
            val targets = (from.coerceAtLeast(0) until end).map { chapters[it] }
                .filter { !it.isVolume && !books.isCached(book, it) }
            var done = 0
            var failed = 0
            fun publish(running: Boolean) = _progress.update {
                it + (book.bookUrl to CacheProgress(done, targets.size, failed, running))
            }
            publish(true)
            for (batch in targets.chunked(BATCH_SIZE)) {
                val batched = prefetch(book, batch)
                for (c in batch) {
                    val next = chapters.getOrNull(c.index + 1)
                    runCatching { books.content(book, c, next) }
                        .onSuccess { done++ }
                        .onFailure { if (it is CancellationException) throw it; failed++ }
                    publish(true)
                    if (!batched) delay(150)
                }
                if (batched) delay(BATCH_DELAY_MS)
            }
            publish(false)
            jobs.remove(book.bookUrl)
        }
    }

    /** Silently preloads the next chapters while reading. */
    fun preload(book: Book, fromIndex: Int, count: Int) {
        if (book.isLocal || count <= 0) return
        scope.launch {
            val chapters = books.chapters(book.bookUrl)
            val range = fromIndex.coerceAtLeast(0) until minOf(chapters.size, fromIndex + count)
            prefetch(book, range.map { chapters[it] }.filter { !it.isVolume })
            for (i in range) {
                val c = chapters[i]
                if (c.isVolume || books.isCached(book, c)) continue
                runCatching { books.content(book, c, chapters.getOrNull(i + 1)) }
            }
        }
    }

    /** True when the batch API served these chapters; failures fall back to per-chapter fetching. */
    private suspend fun prefetch(book: Book, chapters: List<BookChapter>): Boolean =
        runCatching { books.prefetch(book, chapters) > 0 }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    private companion object {
        const val BATCH_SIZE = 30
        const val BATCH_DELAY_MS = 500L
    }
}
