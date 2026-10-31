package app.ceres.reader.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.ceres.reader.AppGraph
import app.ceres.reader.BuildConfig
import app.ceres.reader.data.AppRelease
import app.ceres.reader.data.AppUpdate
import app.ceres.reader.data.UpdateCheck
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val s by AppGraph.settings.reader.collectAsState()
    val dav by AppGraph.settings.webDav.collectAsState()
    val update = AppGraph.settings::update
    val updateDav = AppGraph.settings::updateWebDav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<AppRelease?>(null) }

    Screen(title = "设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "反色模式",
                s.invertColors,
                "黑底白字，适合夜间或反色墨水屏",
            ) { v -> update { it.copy(invertColors = v) } }
            SwitchRow(
                "阅读时显示时间",
                s.showClock,
                "页眉右侧显示当前时分",
            ) { v -> update { it.copy(showClock = v) } }
            RowDivider()

            Text("排版", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Stepper(
                "字号", "${s.fontSizeSp}",
                { update { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(12)) } },
                { update { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(40)) } },
            )
            Stepper(
                "行距", "%.1f".format(s.lineSpacing),
                { update { it.copy(lineSpacing = (Math.round(it.lineSpacing * 10 - 1) / 10f).coerceAtLeast(1.0f)) } },
                { update { it.copy(lineSpacing = (Math.round(it.lineSpacing * 10 + 1) / 10f).coerceAtMost(3.0f)) } },
            )
            Stepper(
                "段距", "${s.paragraphSpacingDp}",
                { update { it.copy(paragraphSpacingDp = (it.paragraphSpacingDp - 2).coerceAtLeast(0)) } },
                { update { it.copy(paragraphSpacingDp = (it.paragraphSpacingDp + 2).coerceAtMost(48)) } },
            )
            Stepper(
                "左右边距", "${s.paddingHorizontalDp}",
                { update { it.copy(paddingHorizontalDp = (it.paddingHorizontalDp - 4).coerceAtLeast(0)) } },
                { update { it.copy(paddingHorizontalDp = (it.paddingHorizontalDp + 4).coerceAtMost(64)) } },
            )
            Stepper(
                "上下边距", "${s.paddingVerticalDp}",
                { update { it.copy(paddingVerticalDp = (it.paddingVerticalDp - 4).coerceAtLeast(0)) } },
                { update { it.copy(paddingVerticalDp = (it.paddingVerticalDp + 4).coerceAtMost(64)) } },
            )
            SwitchRow("粗体正文", s.boldText) { v -> update { it.copy(boldText = v) } }
            RowDivider()

            Text("墨水屏", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Stepper(
                "全刷间隔（翻页次数，0 关闭）", if (s.refreshEvery == 0) "关" else "${s.refreshEvery}",
                { update { it.copy(refreshEvery = (it.refreshEvery - 1).coerceAtLeast(0)) } },
                { update { it.copy(refreshEvery = (it.refreshEvery + 1).coerceAtMost(50)) } },
            )
            Text(
                "每翻 N 页闪一次黑屏触发全局刷新，清除残影。",
                style = MaterialTheme.typography.bodySmall,
            )
            RowDivider()

            Text("翻页", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            SwitchRow("音量键翻页", s.volumeKeyTurn, "音量下 = 下一页，音量上 = 上一页") {
                v -> update { it.copy(volumeKeyTurn = v) }
            }
            SwitchRow("单手模式", s.tapLeftNext, "点击左侧区域也翻到下一页") { v -> update { it.copy(tapLeftNext = v) } }
            SwitchRow("阅读时保持亮屏", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            RowDivider()

            Text("内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            SwitchRow("显示段评", s.showReviews, "书源支持时，在段尾显示评论数；章节汇总显示在第一段末尾") {
                v -> update { it.copy(showReviews = v) }
            }
            Stepper(
                "自动预加载章节数", "${s.preloadCount}",
                { update { it.copy(preloadCount = (it.preloadCount - 1).coerceAtLeast(0)) } },
                { update { it.copy(preloadCount = (it.preloadCount + 1).coerceAtMost(20)) } },
            )
            RowDivider()

            Text("WebDAV 云同步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text(
                "同步书架与阅读进度到 WebDAV（坚果云等）。文件路径：ceres/bookshelf.json",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow("启用 WebDAV", dav.enabled) { v -> updateDav { it.copy(enabled = v) } }
            OutlinedTextField(
                value = dav.url,
                onValueChange = { v -> updateDav { it.copy(url = v.trim()) } },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/ceres/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = dav.username,
                onValueChange = { v -> updateDav { it.copy(username = v) } },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = dav.password,
                onValueChange = { v -> updateDav { it.copy(password = v) } },
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        if (showPassword) "隐藏" else "显示",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clickable { showPassword = !showPassword }
                            .padding(8.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            SwitchRow(
                "自动同步",
                dav.autoSync,
                "阅读进度变化或移出书架后自动双向同步",
            ) { v -> updateDav { it.copy(autoSync = v) } }
            if (dav.lastSyncAt > 0) {
                Text(
                    "上次同步：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(dav.lastSyncAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                InkButton("测试连接", Modifier.weight(1f).padding(end = 4.dp), enabled = !syncing && dav.url.isNotBlank()) {
                    syncing = true
                    scope.launch {
                        runCatching { AppGraph.webDav.test() }
                            .onSuccess { Toast.makeText(context, "连接成功\n$it", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "连接失败：${it.message}", Toast.LENGTH_LONG).show() }
                        syncing = false
                    }
                }
                InkButton("同步", Modifier.weight(1f).padding(horizontal = 4.dp), enabled = !syncing && dav.enabled && dav.url.isNotBlank()) {
                    syncing = true
                    scope.launch {
                        runCatching { AppGraph.webDav.sync() }
                            .onSuccess { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "同步失败：${it.message}", Toast.LENGTH_LONG).show() }
                        syncing = false
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                InkButton("仅上传", Modifier.weight(1f).padding(end = 4.dp), enabled = !syncing && dav.enabled && dav.url.isNotBlank()) {
                    syncing = true
                    scope.launch {
                        runCatching { AppGraph.webDav.uploadOnly() }
                            .onSuccess { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "上传失败：${it.message}", Toast.LENGTH_LONG).show() }
                        syncing = false
                    }
                }
                InkButton("仅下载", Modifier.weight(1f).padding(start = 4.dp), enabled = !syncing && dav.enabled && dav.url.isNotBlank()) {
                    syncing = true
                    scope.launch {
                        runCatching { AppGraph.webDav.downloadOnly() }
                            .onSuccess { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show() }
                        syncing = false
                    }
                }
            }
            Text(
                "双向同步按阅读时间合并进度；移出书架会写入墓碑以免其它设备拉回。仅上传会用本地书架覆盖云端书单。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            RowDivider()

            Text("关于", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Ceres ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                "书源兼容阅读（Legado）格式，内容来自用户导入的书源，版权归原作者所有。",
                style = MaterialTheme.typography.bodySmall,
            )
            InkButton(
                if (checking) "检查中…" else "检查更新",
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                enabled = !checking,
            ) {
                checking = true
                scope.launch {
                    when (val result = AppUpdate.check()) {
                        is UpdateCheck.UpToDate -> Toast.makeText(
                            context,
                            "已是最新：${result.latest.versionName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        is UpdateCheck.Available -> updateDialog = result.latest
                        is UpdateCheck.Failed -> Toast.makeText(
                            context,
                            "检查失败：${result.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    checking = false
                }
            }
            Text(
                "从 GitHub Releases（${BuildConfig.UPDATE_REPO}）比对最新版；推送到 master / 打 tag 会自动构建 APK。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    updateDialog?.let { release ->
        ConfirmDialog(
            title = "发现新版本 ${release.versionName}",
            text = buildString {
                append("当前 ${BuildConfig.VERSION_NAME}")
                release.versionCode?.let { append(" → versionCode $it") }
                append('\n')
                val note = release.notes.trim().lines().filter { it.isNotBlank() }.take(8).joinToString("\n")
                if (note.isNotBlank()) append(note) else append("前往 GitHub Release 下载安装包。")
            },
            confirm = if (release.apkUrl != null) "下载 APK" else "打开页面",
            onConfirm = {
                val uri = Uri.parse(release.apkUrl ?: release.htmlUrl)
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
                updateDialog = null
            },
            onDismiss = { updateDialog = null },
        )
    }
}
