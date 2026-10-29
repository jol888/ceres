package app.ceres.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val book: Book? = null,
    val chapters: List<BookChapter> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class DetailViewModel(private val bookUrl: String) : ViewModel() {
    val state = MutableStateFlow(DetailState())

    init {
        viewModelScope.launch {
            val book = AppGraph.books.get(bookUrl)
            val chapters = AppGraph.books.chapters(bookUrl)
            state.value = DetailState(book, chapters)
            if (book != null && chapters.isEmpty()) refresh()
        }
    }

    fun refresh() {
        val book = state.value.book ?: return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { AppGraph.books.refresh(book) }
                .onSuccess { list -> state.update { it.copy(book = book.copy(), chapters = list, loading = false) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    fun toggleShelf() {
        val book = state.value.book ?: return
        val updated = book.copy(inShelf = !book.inShelf)
        viewModelScope.launch {
            AppGraph.books.save(updated)
            state.update { it.copy(book = updated) }
        }
    }
}

@Composable
fun DetailScreen(bookUrl: String, onBack: () -> Unit, openReader: (Int?) -> Unit) {
    val vm: DetailViewModel = viewModel(key = bookUrl) { DetailViewModel(bookUrl) }
    val state by vm.state.collectAsState()
    val book = state.book
    var showAllIntro by remember { mutableStateOf(false) }

    Screen(title = book?.name ?: "详情", onBack = onBack, actions = {
        if (book != null && !book.isLocal) Action("刷新") { vm.refresh() }
    }) {
        if (book == null) {
            CenterMessage("加载中…"); return@Screen
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!book.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = book.coverUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .width(84.dp)
                                .height(112.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.name, style = MaterialTheme.typography.titleLarge)
                        if (book.author.isNotBlank()) Text("作者：${book.author}")
                        Text("来源：${book.originName}", style = MaterialTheme.typography.bodySmall)
                        book.kind?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        book.wordCount?.takeIf { it.isNotBlank() }?.let { Text("字数：$it", style = MaterialTheme.typography.bodySmall) }
                        book.latestChapterTitle?.let { Text("最新：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InkButton(
                        if (book.durChapterTitle != null) "继续阅读" else "开始阅读",
                        enabled = state.chapters.isNotEmpty(),
                    ) { openReader(null) }
                    InkButton(if (book.inShelf) "移出书架" else "加入书架") { vm.toggleShelf() }
                    if (!book.isLocal && state.chapters.isNotEmpty()) {
                        InkButton("缓存全部") { AppGraph.cache.download(book, 0, null) }
                    }
                }
            }
            book.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                item {
                    Text(
                        intro,
                        maxLines = if (showAllIntro) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(16.dp)
                            .combinedClickableCompat({ showAllIntro = !showAllIntro }, null),
                    )
                }
            }
            item {
                val status = when {
                    state.loading -> "正在获取目录…"
                    state.error != null -> "出错：${state.error}"
                    else -> "目录（${state.chapters.size} 章）"
                }
                Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                RowDivider()
            }
            itemsIndexed(state.chapters, key = { _, c -> c.index }) { i, c ->
                ListRow(
                    title = c.title,
                    trailing = if (i == book.durChapterIndex && book.durChapterTitle != null) "读到这里" else null,
                ) { openReader(i) }
            }
        }
    }
}
