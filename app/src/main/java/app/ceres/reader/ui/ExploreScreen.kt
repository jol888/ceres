package app.ceres.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.model.SearchBook
import app.ceres.reader.source.ExploreKind
import app.ceres.reader.source.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExploreViewModel : ViewModel() {
    var sources by mutableStateOf<List<BookSource>?>(null)
        private set
    var source by mutableStateOf<BookSource?>(null)
        private set
    var kinds by mutableStateOf<List<ExploreKind>?>(null)
        private set
    var kind by mutableStateOf<ExploreKind?>(null)
        private set
    val books = mutableStateListOf<SearchBook>()
    var loading by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var page = 0
    private var job: Job? = null
    private val kindCache = HashMap<String, List<ExploreKind>>()

    init {
        viewModelScope.launch {
            sources = withContext(Dispatchers.Default) {
                AppGraph.sources.enabled().filter { it.enabledExplore && !it.exploreUrl.isNullOrBlank() }
            }
        }
    }

    fun pick(s: BookSource) {
        job?.cancel()
        source = s
        error = null
        kinds = kindCache[s.bookSourceUrl]
        if (kinds != null) return
        loading = true
        job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { WebBook.exploreKinds(s) } }
                .onSuccess { kinds = it; kindCache[s.bookSourceUrl] = it }
                .onFailure { error = "分类加载失败：${it.message}" }
            loading = false
        }
    }

    fun open(k: ExploreKind) {
        job?.cancel()
        kind = k
        books.clear()
        page = 0
        end = false
        error = null
        loading = false
        loadMore()
    }

    fun loadMore() {
        val s = source ?: return
        val url = kind?.url ?: return
        if (loading || end) return
        loading = true
        error = null
        job = viewModelScope.launch {
            val next = page + 1
            runCatching { withContext(Dispatchers.IO) { WebBook.explore(s, url, next) } }
                .onSuccess { list ->
                    val seen = books.mapTo(HashSet()) { it.bookUrl }
                    val fresh = list.filter { seen.add(it.bookUrl) }
                    if (fresh.isEmpty()) end = true else { books.addAll(fresh); page = next }
                }
                .onFailure {
                    if (next > 1 && it.message?.contains("HTTP 404") == true) end = true
                    else error = "加载失败：${it.message}"
                }
            loading = false
        }
    }

    /** Steps up one level; false when already at the source list. */
    fun back(): Boolean {
        job?.cancel()
        loading = false
        error = null
        when {
            kind != null -> kind = null
            source != null -> source = null
            else -> return false
        }
        return true
    }

    suspend fun openBook(book: SearchBook): String {
        if (AppGraph.books.get(book.bookUrl) == null) AppGraph.books.save(book.toBook())
        return book.bookUrl
    }
}

@Composable
fun ExploreScreen(onBack: () -> Unit, openDetail: (String) -> Unit) {
    val vm: ExploreViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val s = vm.source
    val k = vm.kind
    BackHandler(enabled = s != null) { vm.back() }

    Screen(
        title = when {
            k != null -> "${s?.bookSourceName} · ${k.title}"
            s != null -> s.bookSourceName
            else -> "发现"
        },
        onBack = { if (!vm.back()) onBack() },
    ) {
        when {
            k != null -> LazyColumn(Modifier.fillMaxSize()) {
                items(vm.books, key = { it.bookUrl }) { b ->
                    ListRow(
                        title = b.name,
                        subtitle = listOfNotNull(
                            b.author.takeIf { it.isNotBlank() },
                            b.kind?.takeIf { it.isNotBlank() },
                            b.latestChapterTitle?.let { "最新：$it" },
                        ).joinToString(" · ") + (b.intro?.let { "\n" + it.take(80) } ?: ""),
                    ) { scope.launch { openDetail(vm.openBook(b)) } }
                }
                item {
                    val msg = when {
                        vm.loading -> "加载中…"
                        vm.error != null -> vm.error
                        vm.end && vm.books.isEmpty() -> "这个分类没有内容"
                        vm.end -> "没有更多了"
                        else -> null
                    }
                    if (msg != null) {
                        Text(msg, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!vm.loading && !vm.end) {
                        InkButton(if (vm.error != null) "重试" else "下一页", Modifier.fillMaxWidth().padding(16.dp), onClick = vm::loadMore)
                    }
                }
            }
            s != null -> {
                val kinds = vm.kinds
                when {
                    vm.error != null -> CenterMessage(vm.error!!)
                    kinds == null -> CenterMessage("加载分类…")
                    kinds.isEmpty() -> CenterMessage("这个书源没有发现分类")
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        kindSections(kinds).forEach { (header, items) ->
                            item {
                                if (header != null) {
                                    Text(header, Modifier.padding(4.dp, 14.dp, 4.dp, 6.dp), fontWeight = FontWeight.Bold)
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 6.dp),
                                ) {
                                    items.forEach { item -> InkButton(item.title) { vm.open(item) } }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val list = vm.sources
                when {
                    list == null -> CenterMessage("加载中…")
                    list.isEmpty() -> CenterMessage("没有带「发现」的已启用书源。\n书源需要配置 exploreUrl。")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.bookSourceUrl }) { src ->
                            ListRow(src.bookSourceName, subtitle = src.bookSourceGroup) { vm.pick(src) }
                        }
                    }
                }
            }
        }
    }
}

/** Groups kinds under the preceding header entry (one without a URL). */
private fun kindSections(kinds: List<ExploreKind>): List<Pair<String?, List<ExploreKind>>> {
    val out = ArrayList<Pair<String?, MutableList<ExploreKind>>>()
    for (k in kinds) {
        if (k.url == null) out.add(k.title to ArrayList())
        else {
            if (out.isEmpty()) out.add(null to ArrayList())
            out.last().second.add(k)
        }
    }
    return out.filter { it.second.isNotEmpty() }
}
