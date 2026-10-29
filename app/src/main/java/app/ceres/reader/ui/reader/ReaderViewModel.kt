package app.ceres.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ceres.reader.AppGraph
import app.ceres.reader.data.ReaderSettings
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.source.ReviewItem
import app.ceres.reader.source.ReviewSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ReaderUi(
    val bookName: String = "",
    val inShelf: Boolean = true,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val pages: List<TextPage> = emptyList(),
    val pageIndex: Int = 0,
    val metrics: PageMetrics? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Incremented on every page turn; drives the periodic full refresh. */
    val turnTick: Int = 0,
)

data class ReviewSheetState(
    val paraIndex: Int,
    val paraText: String,
    val count: Int,
    val items: List<ReviewItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val page: Int = 0,
    val nextUrl: String? = null,
)

class ReaderViewModel(private val bookUrl: String, private val startIndex: Int?) : ViewModel() {
    private val books = AppGraph.books

    val ui = MutableStateFlow(ReaderUi())
    val review = MutableStateFlow<ReviewSheetState?>(null)

    private var book: Book? = null
    var chapters: List<BookChapter> = emptyList()
        private set

    private class Loaded(val index: Int, val title: String, val paragraphs: List<String>, val summary: ReviewSummary)

    private sealed interface Target {
        data object Start : Target
        data object End : Target
        data class Pos(val pos: Int) : Target
    }

    private var current: Loaded? = null
    private var spec: PageSpec? = null
    private var settings: ReaderSettings = AppGraph.settings.reader.value
    private var pendingTarget: Target? = null
    private var loadJob: Job? = null
    private var reviewJob: Job? = null

    init {
        viewModelScope.launch {
            val b = books.get(bookUrl)
            if (b == null) {
                ui.update { it.copy(loading = false, error = "书籍不存在") }
                return@launch
            }
            book = b
            ui.update { it.copy(bookName = b.name, inShelf = b.inShelf) }
            var list = books.chapters(bookUrl)
            if (list.isEmpty()) {
                list = runCatching { books.refresh(b) }.getOrElse { e ->
                    ui.update { it.copy(loading = false, error = "获取目录失败：${e.message}") }
                    return@launch
                }
            }
            chapters = list
            val index = (startIndex ?: b.durChapterIndex).coerceIn(0, list.lastIndex)
            val target = if (startIndex == null || startIndex == b.durChapterIndex) Target.Pos(b.durChapterPos) else Target.Start
            open(index, target)
        }
        viewModelScope.launch {
            AppGraph.settings.reader.drop(1).collect { s ->
                val reloadReviews = s.showReviews != settings.showReviews
                settings = s
                if (reloadReviews) current?.let { open(it.index, currentPos()) } else relayout(currentPos())
            }
        }
    }

    private fun currentPos(): Target {
        val u = ui.value
        return u.pages.getOrNull(u.pageIndex)?.let { Target.Pos(it.startPos) } ?: Target.Start
    }

    fun setSpec(newSpec: PageSpec) {
        if (newSpec == spec) return
        val target = pendingTarget ?: currentPos()
        spec = newSpec
        pendingTarget = null
        relayout(target)
    }

    private fun relayout(target: Target) {
        if (current == null) return
        viewModelScope.launch { layout(target) }
    }

    private fun open(index: Int, target: Target) {
        val b = book ?: return
        if (index !in chapters.indices) return
        loadJob?.cancel()
        review.value = null
        val chapter = chapters[index]
        ui.update {
            it.copy(
                loading = true, error = null, pages = emptyList(), pageIndex = 0,
                chapterIndex = index, chapterCount = chapters.size, chapterTitle = chapter.title,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val (paragraphs, summary) = coroutineScope {
                    val summary = async { reviewSummary(b, chapter) }
                    books.content(b, chapter, chapters.getOrNull(index + 1)) to summary.await()
                }
                current = Loaded(index, chapter.title, paragraphs, summary)
                layout(target)
                AppGraph.cache.preload(b, index + 1, settings.preloadCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ui.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private val summaries = HashMap<String, ReviewSummary>()

    private suspend fun reviewSummary(b: Book, chapter: BookChapter): ReviewSummary {
        if (!settings.showReviews) return ReviewSummary.EMPTY
        summaries[chapter.url]?.let { return it }
        val s = runCatching { withTimeoutOrNull(6_000) { books.reviewSummary(b, chapter) } }.getOrNull()
            ?: return ReviewSummary.EMPTY
        summaries[chapter.url] = s
        return s
    }

    private suspend fun layout(target: Target) {
        val cur = current ?: return
        val sp = spec
        if (sp == null) {
            pendingTarget = target
            return
        }
        val s = settings
        val pages = withContext(Dispatchers.Default) {
            Paginator.paginate(cur.title, cur.paragraphs, cur.summary.counts, sp, s)
        }
        val index = when (target) {
            Target.Start -> 0
            Target.End -> pages.lastIndex
            is Target.Pos -> pages.indexOfLast { it.startPos <= target.pos }.coerceAtLeast(0)
        }
        ui.update {
            it.copy(
                pages = pages, pageIndex = index, metrics = Paginator.metrics(sp, s), loading = false,
                error = null, chapterIndex = cur.index, chapterTitle = cur.title, chapterCount = chapters.size,
            )
        }
        saveProgress()
    }

    private fun saveProgress() {
        val b = book ?: return
        val u = ui.value
        val page = u.pages.getOrNull(u.pageIndex) ?: return
        val title = chapters.getOrNull(u.chapterIndex)?.title
        viewModelScope.launch { books.saveProgress(b, u.chapterIndex, page.startPos, title) }
    }

    private fun tick() = ui.update { it.copy(turnTick = it.turnTick + 1) }

    fun next() {
        val u = ui.value
        if (u.loading) return
        if (u.pageIndex < u.pages.lastIndex) {
            ui.update { it.copy(pageIndex = it.pageIndex + 1) }
            saveProgress()
            tick()
        } else if (u.chapterIndex < chapters.lastIndex) {
            open(u.chapterIndex + 1, Target.Start)
            tick()
        }
    }

    fun prev() {
        val u = ui.value
        if (u.loading) return
        if (u.pageIndex > 0 && u.pages.isNotEmpty()) {
            ui.update { it.copy(pageIndex = it.pageIndex - 1) }
            saveProgress()
            tick()
        } else if (u.chapterIndex > 0) {
            open(u.chapterIndex - 1, Target.End)
            tick()
        }
    }

    fun jump(index: Int) = open(index, Target.Start)
    fun nextChapter() = open(ui.value.chapterIndex + 1, Target.Start)
    fun prevChapter() = open(ui.value.chapterIndex - 1, Target.Start)
    fun retry() = open(ui.value.chapterIndex, Target.Start)

    /** Drops the cached copy of the current chapter and fetches it again. */
    fun reloadChapter() {
        val b = book ?: return
        val idx = ui.value.chapterIndex
        val chapter = chapters.getOrNull(idx) ?: return
        val pos = currentPos()
        summaries.remove(chapter.url)
        viewModelScope.launch {
            if (!b.isLocal) runCatching { books.content(b, chapter, chapters.getOrNull(idx + 1), useCache = false) }
            open(idx, pos)
        }
    }

    fun refreshToc() {
        val b = book ?: return
        viewModelScope.launch {
            runCatching { books.refresh(b, infoToo = false) }
                .onSuccess { list ->
                    chapters = list
                    ui.update { it.copy(chapterCount = list.size) }
                }
                .onFailure { e -> ui.update { it.copy(error = "刷新目录失败：${e.message}") } }
        }
    }

    fun addToShelf() {
        val b = book ?: return
        b.inShelf = true
        viewModelScope.launch {
            books.save(b)
            ui.update { it.copy(inShelf = true) }
        }
    }

    // ---------------- paragraph comments ----------------

    fun openReview(paraIndex: Int) {
        val cur = current ?: return
        val count = cur.summary.counts[paraIndex] ?: return
        review.value = ReviewSheetState(paraIndex, cur.paragraphs.getOrNull(paraIndex - 1).orEmpty(), count)
        loadReviews(reset = true)
    }

    fun loadReviews(reset: Boolean = false) {
        val b = book ?: return
        val cur = current ?: return
        val state = review.value ?: return
        if (!reset && state.nextUrl == null) return
        val chapter = chapters.getOrNull(cur.index) ?: return
        val page = if (reset) 1 else state.page + 1
        val key = cur.summary.keys[state.paraIndex] ?: state.paraIndex.toString()
        review.value = state.copy(loading = true, error = null)
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            runCatching {
                books.reviewPage(b, chapter, state.paraIndex, key, page, if (reset) null else state.nextUrl)
            }.onSuccess { result ->
                review.update { s ->
                    s?.copy(
                        items = if (reset) result.items else s.items + result.items,
                        loading = false, page = page, nextUrl = result.nextUrl,
                    )
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                review.update { it?.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun closeReview() {
        reviewJob?.cancel()
        review.value = null
    }
}
