package app.ceres.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ceres.reader.AppGraph

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val s by AppGraph.settings.reader.collectAsState()
    val update = AppGraph.settings::update
    Screen(title = "设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("墨水屏", style = MaterialTheme.typography.titleMedium)
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
            SwitchRow("显示段评", s.showReviews, "书源支持时，在段尾显示评论数") { v -> update { it.copy(showReviews = v) } }
            Stepper(
                "自动预加载章节数", "${s.preloadCount}",
                { update { it.copy(preloadCount = (it.preloadCount - 1).coerceAtLeast(0)) } },
                { update { it.copy(preloadCount = (it.preloadCount + 1).coerceAtMost(20)) } },
            )
            RowDivider()
            Text(
                "Ceres 0.1 · 书源兼容阅读（Legado）格式，内容来自用户导入的书源，版权归原作者所有。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
