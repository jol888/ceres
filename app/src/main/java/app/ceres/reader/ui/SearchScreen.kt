package app.ceres.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.SearchBook
import app.ceres.reader.source.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Search results grouped by name + author across sources. */
data class SearchGroup(val name: String, val author: String, val books: List<SearchBook>)

data class SearchState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val groups: List<SearchGroup> = emptyList(),
    val message: String? = null,
)

class SearchViewModel : ViewModel() {
    val state = MutableStateFlow(SearchState())
    private var job: Job? = null

    fun search(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            val sources = AppGraph.sources.enabled().filter { !it.searchUrl.isNullOrBlank() }
            if (sources.isEmpty()) {
                state.value = SearchState(message = "没有启用的书源，请先到「书源」导入。")
                return@launch
            }
            state.value = SearchState(running = true, total = sources.size)
            val sem = Semaphore(6)
            val jobs = sources.map { src ->
                launch {
                    sem.withPermit {
                        val result = runCatching {
                            withTimeout(25_000) { runInterruptible(Dispatchers.IO) { WebBook.search(src, k) } }
                        }
                        state.update { s ->
                            val books = result.getOrNull().orEmpty()
                            s.copy(
                                done = s.done + 1,
                                failed = s.failed + if (result.isFailure) 1 else 0,
                                groups = merge(s.groups, books, k),
                            )
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            state.update {
                it.copy(running = false, message = if (it.groups.isEmpty()) "没有找到结果" else null)
            }
        }
    }

    private fun merge(groups: List<SearchGroup>, books: List<SearchBook>, key: String): List<SearchGroup> {
        val map = LinkedHashMap<String, SearchGroup>()
        groups.forEach { map[it.name + "\u0000" + it.author] = it }
        books.forEach { b ->
            val id = b.name + "\u0000" + b.author
            val g = map[id]
            map[id] = if (g == null) SearchGroup(b.name, b.author, listOf(b)) else g.copy(books = g.books + b)
        }
        // exact title matches first, then by number of sources
        return map.values.sortedWith(
            compareByDescending<SearchGroup> { it.name == key }
                .thenByDescending { it.name.contains(key) || it.author.contains(key) }
                .thenByDescending { it.books.size }
        )
    }

    suspend fun open(book: SearchBook): String {
        val existing = AppGraph.books.get(book.bookUrl)
        if (existing == null) AppGraph.books.save(book.toBook())
        return book.bookUrl
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, openDetail: (String) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsState()
    var key by rememberSaveable { mutableStateOf("") }
    var pickGroup by remember { mutableStateOf<SearchGroup?>(null) }
    val scope = rememberCoroutineScope()

    fun open(b: SearchBook) = scope.launch { openDetail(vm.open(b)) }

    Screen(title = "搜索", onBack = onBack) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                placeholder = { Text("书名或作者") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(key) }),
                modifier = Modifier.weight(1f),
            )
            InkButton("搜索") { vm.search(key) }
        }
        if (state.total > 0) {
            Text(
                (if (state.running) "搜索中 " else "已完成 ") + "${state.done}/${state.total} 个书源" +
                    if (state.failed > 0) "，失败 ${state.failed}" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.message?.let { CenterMessage(it) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.groups, key = { it.name + "\u0000" + it.author }) { g ->
                val first = g.books.first()
                ListRow(
                    title = g.name,
                    subtitle = listOfNotNull(
                        g.author.takeIf { it.isNotBlank() },
                        first.kind?.takeIf { it.isNotBlank() },
                        first.latestChapterTitle?.let { "最新：$it" },
                    ).joinToString(" · ") + (first.intro?.let { "\n" + it.take(80) } ?: ""),
                    trailing = if (g.books.size > 1) "${g.books.size} 源" else first.originName,
                ) {
                    if (g.books.size == 1) open(first) else pickGroup = g
                }
            }
        }
    }

    pickGroup?.let { g ->
        OptionsDialog("选择书源", g.books.map { b ->
            "${b.originName}" + (b.latestChapterTitle?.let { "（$it）" } ?: "") to { open(b); Unit }
        }) { pickGroup = null }
    }
}
