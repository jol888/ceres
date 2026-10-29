package app.ceres.reader.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.ceres.reader.AppGraph
import app.ceres.reader.util.ImportLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Confirms a `legado://import/bookSource` link before fetching it; sources run scripts, so never import silently. */
@Composable
fun ImportLinkDialog(link: ImportLink, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    ConfirmDialog(
        title = "导入书源",
        text = "从以下地址导入书源？\n\n${link.src}\n\n书源会在本机运行脚本，请只导入你信任的来源。",
        confirm = "导入",
        onConfirm = {
            AppGraph.appScope.launch {
                val msg = runCatching { AppGraph.sources.importFromUrl(link.src) }
                    .fold({ "导入 $it 个书源" }, { "导入失败：${it.message}" })
                withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            }
        },
        onDismiss = onDismiss,
    )
}
