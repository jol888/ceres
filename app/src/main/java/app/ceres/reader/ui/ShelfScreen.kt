package app.ceres.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.Book
import kotlinx.coroutines.launch

@Composable
fun ShelfScreen(
    openReader: (Book) -> Unit,
    openDetail: (Book) -> Unit,
    openSearch: () -> Unit,
    openSources: () -> Unit,
    openSettings: () -> Unit,
    openExplore: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by remember { AppGraph.books.shelf() }.collectAsState(initial = null)
    val progress by AppGraph.cache.progress.collectAsState()
    var menuFor by remember { mutableStateOf<Book?>(null) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching { AppGraph.importer.import(uri) }
                .onSuccess { Toast.makeText(context, "已导入《${it.name}》", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
            importing = false
        }
    }

    val webDav by AppGraph.settings.webDav.collectAsState()
    var syncing by remember { mutableStateOf(false) }

    Screen(title = "书架", actions = {
        Action("搜索", openSearch)
        Action("发现", openExplore)
        Action("导入") { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }
        if (webDav.enabled && webDav.url.isNotBlank()) {
            Action(if (syncing) "同步中" else "同步") {
                if (syncing) return@Action
                syncing = true
                scope.launch {
                    runCatching { AppGraph.webDav.sync() }
                        .onSuccess { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "同步失败：${it.message}", Toast.LENGTH_LONG).show() }
                    syncing = false
                }
            }
        }
        Action("书源", openSources)
        Action("设置", openSettings)
    }) {
        val list = books
        when {
            importing -> CenterMessage("正在导入…")
            list == null -> CenterMessage("加载中…")
            list.isEmpty() -> CenterMessage(
                "书架是空的。\n\n在线阅读：先到「书源」导入书源，再「搜索」。\n本地阅读：点「导入」选择 TXT / EPUB。"
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.bookUrl }) { book ->
                    val p = progress[book.bookUrl]
                    val reading = book.durChapterTitle?.let { "读到：$it" } ?: "未读"
                    val cacheText = p?.let {
                        if (it.running) " · 缓存中 ${it.done}/${it.total}"
                        else " · 缓存完成 ${it.done}/${it.total}" + if (it.failed > 0) "（失败 ${it.failed}）" else ""
                    }.orEmpty()
                    ListRow(
                        title = book.name,
                        subtitle = listOf(book.author, book.originName).filter { it.isNotBlank() }.joinToString(" · ") +
                            "\n$reading" + (book.latestChapterTitle?.let { "\n最新：$it" } ?: "") + cacheText,
                        trailing = if (book.totalChapterNum > 0) "${book.durChapterIndex + 1}/${book.totalChapterNum}" else null,
                        onLongClick = { menuFor = book },
                        onClick = { openReader(book) },
                    )
                }
            }
        }
    }

    menuFor?.let { book ->
        val running = AppGraph.cache.isRunning(book.bookUrl)
        val options = buildList<Pair<String, () -> Unit>> {
            add("书籍详情 / 目录" to { openDetail(book) })
            if (!book.isLocal) {
                if (running) {
                    add("停止缓存" to { AppGraph.cache.cancel(book.bookUrl) })
                } else {
                    add("缓存后面 50 章" to { AppGraph.cache.download(book, book.durChapterIndex, 50) })
                    add("缓存全部章节" to { AppGraph.cache.download(book, 0, null) })
                }
                add("清除已缓存正文" to { AppGraph.books.clearCache(book) })
            }
            add("移出书架" to {
                scope.launch {
                    AppGraph.webDav.noteRemoval(book.bookUrl)
                    AppGraph.books.delete(book)
                }
            })
        }
        OptionsDialog(book.name, options) { menuFor = null }
    }
}
