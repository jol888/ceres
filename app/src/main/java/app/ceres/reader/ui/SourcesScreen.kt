package app.ceres.reader.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.SourceItem
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(onBack: () -> Unit, openDebug: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by AppGraph.sources.items.collectAsState(initial = null)
    var showImport by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<SourceItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runImport(block: suspend () -> Int) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { Toast.makeText(context, "导入 $it 个书源", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport {
            AppGraph.sources.importStream { context.contentResolver.openInputStream(uri) }
        }
    }

    Screen(title = "书源", onBack = onBack, actions = {
        Action("导入") { showImport = true }
        Action("全开") { scope.launch { AppGraph.sources.setAllEnabled(true) } }
        Action("全关") { scope.launch { AppGraph.sources.setAllEnabled(false) } }
    }) {
        val list = sources
        when {
            busy -> CenterMessage("处理中…")
            list == null -> CenterMessage("加载中…")
            list.isEmpty() -> CenterMessage("还没有书源。\n点右上角「导入」，支持阅读（Legado）格式的书源 JSON：网络地址、剪贴板或文件；也可先导入内置示例试用。")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "共 ${list.size} 个，启用 ${list.count { it.enabled }} 个。点击切换启用，长按更多操作。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp, 8.dp),
                    )
                    RowDivider()
                }
                items(list, key = { it.url }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickableCompat(
                                { scope.launch { AppGraph.sources.setEnabled(s.url, !s.enabled) } },
                                { menuFor = s },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (s.enabled) "■" else "□", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(s.group?.takeIf { it.isNotBlank() }, s.url).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        if (s.hasReview) Text("段评", style = MaterialTheme.typography.bodySmall)
                    }
                    RowDivider()
                }
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入书源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("书源网络地址（http…）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InkButton("从网络地址导入", Modifier.fillMaxWidth(), enabled = urlInput.startsWith("http")) {
                        showImport = false
                        runImport { AppGraph.sources.importFromUrl(urlInput) }
                    }
                    InkButton("从剪贴板导入", Modifier.fillMaxWidth()) {
                        showImport = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.trim().startsWith("http")) runImport { AppGraph.sources.importFromUrl(text.trim()) }
                        else runImport { AppGraph.sources.importJson(text) }
                    }
                    InkButton("从文件导入", Modifier.fillMaxWidth()) {
                        showImport = false
                        filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                    InkButton("导入内置示例（维基文库）", Modifier.fillMaxWidth()) {
                        showImport = false
                        runImport { AppGraph.sources.importStream { context.assets.open("sample_sources.json") } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showImport = false }) { Text("关闭") } },
            shape = RoundedCornerShape(4.dp),
            containerColor = Paper,
        )
    }

    menuFor?.let { s ->
        OptionsDialog(s.name, listOf(
            "调试（搜索→目录→正文→段评）" to { openDebug(s.url) },
            (if (s.enabled) "禁用" else "启用") to { scope.launch { AppGraph.sources.setEnabled(s.url, !s.enabled) }; Unit },
            "删除" to { scope.launch { AppGraph.sources.delete(s.url) }; Unit },
        )) { menuFor = null }
    }
}
