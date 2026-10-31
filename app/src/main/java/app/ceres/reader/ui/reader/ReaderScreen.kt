package app.ceres.reader.ui.reader

import android.graphics.RectF
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.MainActivity
import app.ceres.reader.source.ReviewItem
import app.ceres.reader.ui.Action
import app.ceres.reader.ui.FlashBlack
import app.ceres.reader.ui.InkButton
import app.ceres.reader.ui.LocalPalette
import app.ceres.reader.ui.RowDivider
import app.ceres.reader.ui.Stepper
import app.ceres.reader.ui.SwitchRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Panel { None, Toc, Style }

private fun nowClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
fun ReaderScreen(bookUrl: String, startIndex: Int?, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(key = "reader:$bookUrl:$startIndex") { ReaderViewModel(bookUrl, startIndex) }
    val ui by vm.ui.collectAsState()
    val review by vm.review.collectAsState()
    val settings by AppGraph.settings.reader.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.None) }
    var flash by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(nowClock()) }
    val activity = LocalActivity.current as? MainActivity
    val view = LocalView.current

    LaunchedEffect(settings.showClock) {
        if (!settings.showClock) return@LaunchedEffect
        while (true) {
            clock = nowClock()
            val delayMs = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(delayMs.coerceAtLeast(1_000L))
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        val window = activity?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(menu) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (menu) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val overlayOpen = menu || panel != Panel.None || review != null
    DisposableEffect(settings.volumeKeyTurn, overlayOpen) {
        activity?.keyHandler = handler@{ e ->
            val forward = when (e.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (settings.volumeKeyTurn) true else return@handler false
                KeyEvent.KEYCODE_VOLUME_UP -> if (settings.volumeKeyTurn) false else return@handler false
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> false
                else -> return@handler false
            }
            if (overlayOpen) return@handler false
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (forward) vm.next() else vm.prev()
            }
            true
        }
        onDispose { activity?.keyHandler = null }
    }

    LaunchedEffect(ui.turnTick) {
        val n = settings.refreshEvery
        if (n > 0 && ui.turnTick > 0 && ui.turnTick % n == 0) {
            flash = true
            delay(200)
            flash = false
        }
    }

    BackHandler {
        when {
            review != null -> vm.closeReview()
            panel != Panel.None -> panel = Panel.None
            menu -> menu = false
            else -> onBack()
        }
    }

    val palette = LocalPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.paper)
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        LaunchedEffect(w, h, density.density, density.fontScale) {
            vm.setSpec(PageSpec(w, h, density.density, density.fontScale))
        }
        val page = ui.pages.getOrNull(ui.pageIndex)
        val currentPage by rememberUpdatedState(page)
        val menuOpen by rememberUpdatedState(menu)
        val leftNext by rememberUpdatedState(settings.tapLeftNext)
        val touchSlop = with(density) { 12.dp.toPx() }
        val swipeMin = with(density) { 60.dp.toPx() }

        val clockLabel = if (settings.showClock) clock else null
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        if (menuOpen) {
                            menu = false; return@detectTapGestures
                        }
                        val hit = currentPage?.badges?.firstOrNull {
                            RectF(it.rect).apply { inset(-touchSlop, -touchSlop) }.contains(o.x, o.y)
                        }
                        when {
                            hit != null -> vm.openReview(hit.paraIndex)
                            o.x < size.width / 3f -> if (leftNext) vm.next() else vm.prev()
                            o.x > size.width * 2f / 3f -> vm.next()
                            else -> menu = true
                        }
                    }
                }
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -swipeMin) vm.next() else if (total > swipeMin) vm.prev()
                        },
                    ) { _, dx -> total += dx }
                }
        ) {
            val m = ui.metrics
            if (page != null && m != null) {
                drawIntoCanvas {
                    Paginator.draw(
                        it.nativeCanvas, page, m,
                        header = ui.chapterTitle,
                        footerLeft = ui.bookName,
                        footerRight = "${ui.pageIndex + 1}/${ui.pages.size}　${ui.chapterIndex + 1}/${ui.chapterCount}章",
                        width = w,
                        clock = clockLabel,
                    )
                }
            }
        }

        when {
            ui.error != null -> Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(LocalPalette.current.paper),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ui.chapterTitle, fontWeight = FontWeight.Bold)
                Text("加载失败：${ui.error}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("重试") { vm.retry() }
                    InkButton("目录") { panel = Panel.Toc }
                    InkButton("返回") { onBack() }
                }
            }
            ui.loading && page == null -> Text(
                "加载中…\n${ui.chapterTitle}",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (menu && panel == Panel.None) {
            ReaderMenu(
                ui = ui,
                onBack = onBack,
                onClose = { menu = false },
                onToc = { panel = Panel.Toc },
                onStyle = { panel = Panel.Style },
                vm = vm,
            )
        }
        when (panel) {
            Panel.Toc -> TocPanel(vm, ui.chapterIndex, onClose = { panel = Panel.None }) { i ->
                panel = Panel.None
                menu = false
                vm.jump(i)
            }
            Panel.Style -> StylePanel(onClose = { panel = Panel.None })
            Panel.None -> Unit
        }
        review?.let { ReviewSheet(it, onClose = vm::closeReview, onMore = { vm.loadReviews() }) }

        if (flash) Box(
            Modifier
                .fillMaxSize()
                .background(FlashBlack)
        )
    }
}

@Composable
private fun ReaderMenu(
    ui: ReaderUi,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onToc: () -> Unit,
    onStyle: () -> Unit,
    vm: ReaderViewModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalPalette.current.paper)
        ) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Action("‹ 返回", onBack)
                Text(
                    ui.bookName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!ui.inShelf) Action("加书架") { vm.addToShelf() }
                Action("重新获取") { vm.reloadChapter(); onClose() }
            }
            HorizontalDivider(color = LocalPalette.current.ink)
        }
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClick = onClose)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalPalette.current.paper)
        ) {
            HorizontalDivider(color = LocalPalette.current.ink)
            Text(
                "${ui.chapterTitle}　（${ui.chapterIndex + 1}/${ui.chapterCount}）",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                InkButton("上一章", enabled = ui.chapterIndex > 0) { vm.prevChapter() }
                InkButton("目录", onClick = onToc)
                InkButton("排版", onClick = onStyle)
                InkButton("下一章", enabled = ui.chapterIndex < ui.chapterCount - 1) { vm.nextChapter() }
            }
        }
    }
}

@Composable
private fun TocPanel(vm: ReaderViewModel, current: Int, onClose: () -> Unit, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    Column(
        Modifier
            .fillMaxSize()
            .background(LocalPalette.current.paper)
            .safeDrawingPadding()
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Action("‹ 关闭", onClose)
            Text("目录（${vm.chapters.size} 章）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Action("刷新") { vm.refreshToc() }
        }
        HorizontalDivider(color = LocalPalette.current.ink)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(vm.chapters, key = { _, c -> c.index }) { i, c ->
                Text(
                    (if (i == current) "▶ " else "") + c.title,
                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (c.isVolume) LocalPalette.current.gray else LocalPalette.current.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(i) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                RowDivider()
            }
        }
    }
}

@Composable
private fun StylePanel(onClose: () -> Unit) {
    val s by AppGraph.settings.reader.collectAsState()
    val update = AppGraph.settings::update
    Column(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClick = onClose)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalPalette.current.paper)
                .safeDrawingPadding()
        ) {
            HorizontalDivider(color = LocalPalette.current.ink)
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Stepper("字号", "${s.fontSizeSp}",
                    { update { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(12)) } },
                    { update { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(40)) } })
                Stepper("行距", "%.1f".format(s.lineSpacing),
                    { update { it.copy(lineSpacing = (Math.round(it.lineSpacing * 10 - 1) / 10f).coerceAtLeast(1.0f)) } },
                    { update { it.copy(lineSpacing = (Math.round(it.lineSpacing * 10 + 1) / 10f).coerceAtMost(3.0f)) } })
                Stepper("段距", "${s.paragraphSpacingDp}",
                    { update { it.copy(paragraphSpacingDp = (it.paragraphSpacingDp - 2).coerceAtLeast(0)) } },
                    { update { it.copy(paragraphSpacingDp = (it.paragraphSpacingDp + 2).coerceAtMost(48)) } })
                Stepper("左右边距", "${s.paddingHorizontalDp}",
                    { update { it.copy(paddingHorizontalDp = (it.paddingHorizontalDp - 4).coerceAtLeast(0)) } },
                    { update { it.copy(paddingHorizontalDp = (it.paddingHorizontalDp + 4).coerceAtMost(64)) } })
                Stepper("上下边距", "${s.paddingVerticalDp}",
                    { update { it.copy(paddingVerticalDp = (it.paddingVerticalDp - 4).coerceAtLeast(0)) } },
                    { update { it.copy(paddingVerticalDp = (it.paddingVerticalDp + 4).coerceAtMost(64)) } })
                Stepper("全刷间隔", if (s.refreshEvery == 0) "关" else "${s.refreshEvery}页",
                    { update { it.copy(refreshEvery = (it.refreshEvery - 1).coerceAtLeast(0)) } },
                    { update { it.copy(refreshEvery = (it.refreshEvery + 1).coerceAtMost(50)) } })
                SwitchRow("粗体正文", s.boldText) { v -> update { it.copy(boldText = v) } }
                SwitchRow("显示段评", s.showReviews) { v -> update { it.copy(showReviews = v) } }
                SwitchRow("显示时间", s.showClock, "页眉右侧") { v -> update { it.copy(showClock = v) } }
                SwitchRow("反色模式", s.invertColors, "黑底白字") { v -> update { it.copy(invertColors = v) } }
                InkButton("完成", Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }
}

@Composable
private fun ReviewSheet(state: ReviewSheetState, onClose: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .weight(0.3f)
                .fillMaxWidth()
                .clickable(onClick = onClose)
        )
        Column(
            Modifier
                .weight(0.7f)
                .fillMaxWidth()
                .background(LocalPalette.current.paper)
                .safeDrawingPadding()
        ) {
            HorizontalDivider(color = LocalPalette.current.ink, thickness = 2.dp)
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.paraIndex == 0) "整章 · ${state.count} 条段评"
                    else "第 ${state.paraIndex} 段 · ${state.count} 条段评",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Action("关闭", onClose)
            }
            if (state.paraIndex != 0) {
                Text(
                    "「${state.paraText}」",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            HorizontalDivider(color = LocalPalette.current.ink, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                itemsIndexed(state.items) { _, item -> ReviewRow(item) }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.loading -> Text("加载中…")
                            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("加载失败：${state.error}")
                                InkButton("重试", onClick = onMore)
                            }
                            state.nextUrl != null -> InkButton("加载更多", onClick = onMore)
                            state.items.isEmpty() -> Text("暂无评论")
                            else -> Text("没有更多了", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(item: ReviewItem) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name ?: "匿名", fontWeight = FontWeight.Bold)
            if (item.badges.isNotEmpty()) {
                Text(
                    "  " + item.badges.filter { !it.startsWith("http") && !it.startsWith("data:") }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item.content?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
        val meta = listOfNotNull(
            item.time,
            item.likeCount?.takeIf { it > 0 }?.let { "赞 $it" },
            item.replyCount?.takeIf { it > 0 }?.let { "回复 $it" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        item.replies.forEach { r ->
            Text(
                "↳ ${r.name ?: "匿名"}" + (r.replyToName?.let { " 回复 $it" } ?: "") + "：${r.content.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
    RowDivider()
}
