package app.ceres.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.rule.RuleLog
import app.ceres.reader.source.ReviewLoader
import app.ceres.reader.source.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class SourceDebugViewModel(private val sourceUrl: String) : ViewModel() {
    val logs = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    private var job: Job? = null

    private fun log(msg: String) = logs.update { it + msg }

    fun run(key: String) {
        job?.cancel()
        logs.value = emptyList()
        job = viewModelScope.launch {
            val source = AppGraph.sources.get(sourceUrl) ?: return@launch log("书源不存在")
            running.value = true
            RuleLog.sink = { msg -> logs.update { it + "  $msg" } }
            try {
                runInterruptible(Dispatchers.IO) { debug(source, key) }
            } catch (e: Throwable) {
                log("✗ 出错：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                RuleLog.sink = null
                running.value = false
            }
        }
    }

    private fun debug(source: BookSource, key: String) {
        log("▶ 搜索「$key」")
        val results = WebBook.search(source, key)
        log("✓ 搜索结果 ${results.size} 条")
        results.take(5).forEach { log("  · ${it.name} / ${it.author} → ${it.bookUrl}") }
        val first = results.firstOrNull() ?: return log("✗ 无结果，停止")
        val book = first.toBook()
        log("▶ 详情 ${book.bookUrl}")
        val body = WebBook.getBookInfo(source, book)
        log("✓ 书名：${book.name}  作者：${book.author}")
        log("  目录地址：${book.tocUrl}")
        log("  简介：${book.intro?.take(80)}")
        log("▶ 目录")
        val chapters = WebBook.getChapterList(source, book, body)
        log("✓ 章节 ${chapters.size} 个：${chapters.take(3).joinToString { it.title }} … ${chapters.last().title}")
        val c = chapters.firstOrNull { !it.isVolume } ?: return
        log("▶ 正文 ${c.title} ${c.url}")
        val paras = WebBook.getContent(source, book, c, chapters.getOrNull(c.index + 1)?.url)
        log("✓ 段落 ${paras.size} 个")
        paras.take(3).forEach { log("  「${it.take(60)}」") }
        if (source.hasReview()) {
            log("▶ 段评统计")
            val summary = ReviewLoader.summary(source, book, c)
            log("✓ 有评论的段落：${summary.counts.entries.take(10).joinToString { "第${it.key}段×${it.value}" }}")
            summary.counts.keys.minOrNull()?.let { idx ->
                log("▶ 段评详情 第${idx}段")
                val page = ReviewLoader.detail(source, book, c, idx, summary.keys[idx] ?: idx.toString(), 1, null)
                log("✓ 评论 ${page.items.size} 条，下一页：${page.nextUrl ?: "无"}")
                page.items.take(3).forEach { log("  ${it.name}：${it.content?.take(50)}") }
            }
        } else {
            log("（书源未配置段评 ruleReview）")
        }
        log("■ 完成")
    }

    override fun onCleared() {
        RuleLog.sink = null
    }
}

@Composable
fun SourceDebugScreen(sourceUrl: String, onBack: () -> Unit) {
    val vm: SourceDebugViewModel = viewModel(key = sourceUrl) { SourceDebugViewModel(sourceUrl) }
    val logs by vm.logs.collectAsState()
    val running by vm.running.collectAsState()
    var key by rememberSaveable { mutableStateOf("我的") }

    Screen(title = "书源调试", onBack = onBack) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.weight(1f))
            InkButton(if (running) "运行中" else "开始", enabled = !running) { vm.run(key) }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(logs) { line ->
                    Text(line, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
            }
        }
    }
}
