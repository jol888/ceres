package app.ceres.reader.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ceres.reader.AppGraph
import app.ceres.reader.transfer.MessagePipe
import app.ceres.reader.transfer.SelfTest
import app.ceres.reader.transfer.TransferPayload
import app.ceres.reader.transfer.TransferProtocol
import app.ceres.reader.transfer.TransferRole
import app.ceres.reader.transfer.acoustic.AcousticAudio
import app.ceres.reader.transfer.acoustic.AcousticFraming
import app.ceres.reader.transfer.acoustic.AcousticModem
import app.ceres.reader.transfer.bluetooth.BleLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.log10
import kotlin.random.Random

enum class TransferChannel { BLUETOOTH, SOUND }

data class TransferOptions(
    val role: TransferRole = TransferRole.RECEIVE,
    val channel: TransferChannel = TransferChannel.BLUETOOTH,
    val includeWebDav: Boolean = true,
    val robust: Boolean = false,
)

sealed interface TransferPhase {
    data object Idle : TransferPhase
    data class Discovering(val peers: List<BleLink.Peer>, val advertising: Boolean, val error: String? = null) : TransferPhase
    data class Working(val text: String) : TransferPhase
    data class Verify(val code: String, val peer: String, val confirmed: Boolean = false, val note: String? = null) : TransferPhase
    data class Playing(val round: Int, val rounds: Int, val packets: Int, val kb: Double, val seconds: Double, val lowVolume: Boolean) : TransferPhase
    data class Listening(val progress: AcousticAudio.ListenProgress?) : TransferPhase
    data class Done(val message: String) : TransferPhase
    data class Failed(val message: String) : TransferPhase
    data class Report(val lines: List<String>) : TransferPhase
}

class TransferViewModel(private val app: Context) : ViewModel() {
    val options = MutableStateFlow(TransferOptions())
    val phase = MutableStateFlow<TransferPhase>(TransferPhase.Idle)
    val running = MutableStateFlow(false)
    val name: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    val tag = Random.nextInt(0x10000)

    private var job: Job? = null
    private val picks = Channel<BleLink.Peer>(Channel.CONFLATED)
    private var confirmation: CompletableDeferred<Boolean>? = null

    fun update(change: (TransferOptions) -> TransferOptions) {
        if (!running.value) options.update(change)
    }

    fun start() {
        val o = options.value
        picks.tryReceive()
        launchTask {
            when (o.channel) {
                TransferChannel.BLUETOOTH -> bluetooth(o)
                TransferChannel.SOUND -> if (o.role == TransferRole.SEND) soundSend(o) else soundReceive()
            }
        }
    }

    fun selfTest() = launchTask {
        phase.value = TransferPhase.Working("正在检查蓝牙…")
        val lines = SelfTest.bluetooth(app).toMutableList()
        lines += SelfTest.sound(app) { phase.value = TransferPhase.Working(it) }
        phase.value = TransferPhase.Report(lines)
    }

    private fun launchTask(block: suspend () -> Unit) {
        job?.cancel()
        running.value = true
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase.value = TransferPhase.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running.value = false
        phase.value = TransferPhase.Idle
    }

    fun pick(peer: BleLink.Peer) {
        picks.trySend(peer)
    }

    fun confirm(accepted: Boolean) {
        confirmation?.complete(accepted)
    }

    override fun onCleared() {
        job?.cancel()
    }

    private suspend fun payload(o: TransferOptions): ByteArray =
        TransferPayload.encode(AppGraph.backup.build(System.currentTimeMillis()), o.includeWebDav)

    private suspend fun restore(payload: ByteArray): String = AppGraph.backup.restore(TransferPayload.decode(payload))

    private suspend fun bluetooth(o: TransferOptions) {
        val outgoing = if (o.role == TransferRole.SEND) payload(o) else null
        val link = BleLink(app, o.role, name, tag)
        var pipe: MessagePipe? = null
        try {
            link.start()
            pipe = awaitPipe(link, o.role)
            link.stopDiscovery()
            val protocol = TransferProtocol(o.role, name)
            val ui = object : TransferProtocol.Callbacks {
                override suspend fun confirm(code: String, peerName: String): Boolean {
                    val answer = CompletableDeferred<Boolean>()
                    confirmation = answer
                    phase.value = TransferPhase.Verify(code, peerName)
                    return answer.await().also { ok ->
                        confirmation = null
                        if (ok) phase.update { (it as? TransferPhase.Verify)?.copy(confirmed = true) ?: it }
                    }
                }

                override fun note(text: String) =
                    phase.update { (it as? TransferPhase.Verify)?.copy(note = text) ?: it }

                override fun status(text: String) {
                    phase.value = TransferPhase.Working(text)
                }
            }
            phase.value = TransferPhase.Done(
                if (outgoing != null) "发送完成。对方：" + protocol.send(pipe, outgoing, ui)
                else protocol.receive(pipe, ui) { restore(it) },
            )
        } finally {
            confirmation = null
            pipe?.close()
            link.close()
        }
    }

    /** Shows nearby peers until the user taps one or a peer connects to us. */
    private suspend fun awaitPipe(link: BleLink, role: TransferRole): MessagePipe {
        var error: String? = null
        while (true) {
            val pipe = coroutineScope {
                val watcher = launch {
                    combine(link.peers, link.advertising) { peers, advertising ->
                        TransferPhase.Discovering(peers.filter { it.role != role }, advertising, error)
                    }.collect { phase.value = it }
                }
                select<MessagePipe?> {
                    link.incoming.onReceive { it }
                    picks.onReceive { peer ->
                        watcher.cancel()
                        phase.value = TransferPhase.Working("正在连接 ${peer.name}…")
                        runCatching { link.connect(peer) }
                            .onFailure { if (it is CancellationException) throw it; error = it.message }
                            .getOrNull()
                    }
                }.also { watcher.cancel() }
            }
            if (pipe != null) return pipe
        }
    }

    private suspend fun soundSend(o: TransferOptions) {
        val data = payload(o)
        val packets = AcousticFraming.packetize(data)
        val profile = if (o.robust) AcousticModem.Profile.ROBUST else AcousticModem.Profile.FAST
        val seconds = AcousticAudio.roundSeconds(packets, profile)
        val rounds = (MAX_PLAY_SECONDS / seconds).toInt().coerceIn(3, 30)
        val lowVolume = AcousticAudio.volumeFraction(app) < 0.5f
        AcousticAudio.broadcast(packets, profile, rounds) { round ->
            phase.value = TransferPhase.Playing(round, rounds, packets.size, data.size / 1024.0, seconds, lowVolume)
        }
        phase.value = TransferPhase.Done("已播放 $rounds 轮。对方没收全的话可以再播一次。")
    }

    private suspend fun soundReceive() {
        phase.value = TransferPhase.Listening(null)
        val data = AcousticAudio.listen(app) { phase.value = TransferPhase.Listening(it) }
        phase.value = TransferPhase.Working("正在导入…")
        val message = restore(data)
        phase.value = TransferPhase.Done(message)
        runCatching { AcousticAudio.playSuccess() }
    }

    private companion object {
        const val MAX_PLAY_SECONDS = 180.0
    }
}

private fun requiredPermissions(o: TransferOptions): List<String> = when (o.channel) {
    TransferChannel.SOUND -> if (o.role == TransferRole.RECEIVE) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
    TransferChannel.BLUETOOTH -> SelfTest.bluetoothPermissions()
}

/** BLE scans return nothing on Android ≤ 11 while location services are off. */
private fun locationOff(context: Context): Boolean {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return false
    val lm = context.getSystemService(LocationManager::class.java) ?: return false
    return !LocationManagerCompat.isLocationEnabled(lm)
}

@Composable
fun TransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TransferViewModel = viewModel { TransferViewModel(context.applicationContext) }
    val o by vm.options.collectAsState()
    val phase by vm.phase.collectAsState()
    val running by vm.running.collectAsState()

    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val on = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        if (on) vm.start() else Toast.makeText(context, "需要打开蓝牙", Toast.LENGTH_SHORT).show()
    }

    fun launch() {
        if (o.channel == TransferChannel.BLUETOOTH) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                adapter == null -> Toast.makeText(context, "本机没有蓝牙", Toast.LENGTH_SHORT).show()
                !adapter.isEnabled -> runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { Toast.makeText(context, "请先在系统设置中打开蓝牙", Toast.LENGTH_SHORT).show() }
                else -> vm.start()
            }
        } else {
            vm.start()
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) launch()
        else Toast.makeText(context, if (o.channel == TransferChannel.SOUND) "需要麦克风权限" else "需要蓝牙权限", Toast.LENGTH_SHORT).show()
    }

    // Runs either way; a denied microphone just skips the loopback.
    val selfTestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.selfTest()
    }

    Screen(title = "设备互传", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("本机：${vm.name} · #%04X".format(vm.tag), style = MaterialTheme.typography.bodySmall)
            Choice(
                listOf(TransferRole.RECEIVE to "接收", TransferRole.SEND to "发送"),
                o.role,
                enabled = !running,
            ) { r -> vm.update { it.copy(role = r) } }
            Choice(
                listOf(TransferChannel.BLUETOOTH to "蓝牙", TransferChannel.SOUND to "声波"),
                o.channel,
                enabled = !running,
            ) { c -> vm.update { it.copy(channel = c, includeWebDav = c == TransferChannel.BLUETOOTH) } }
            if (o.role == TransferRole.SEND) {
                SwitchRow(
                    "包含 WebDAV 账号",
                    o.includeWebDav,
                    if (o.channel == TransferChannel.SOUND) "声波没有加密，附近设备都能听到，建议关闭" else "蓝牙传输已加密，双方核对配对码后才发送",
                ) { v -> vm.update { it.copy(includeWebDav = v) } }
                if (o.channel == TransferChannel.SOUND) {
                    SwitchRow("稳健模式", o.robust, "速度约慢一半，适合环境嘈杂或喇叭较差的设备") { v -> vm.update { it.copy(robust = v) } }
                }
            }
            Text(
                when {
                    o.channel == TransferChannel.BLUETOOTH ->
                        "两台设备都打开本页，一台选「接收」，一台选「发送」，都点开始。在列表里点对方设备，" +
                            "核对两边屏幕上的 6 位数字一致后确认。传送内容为全部书源、书源地址" +
                            (if (o.role == TransferRole.SEND && !o.includeWebDav) "" else "和 WebDAV 账号") + "。"
                    o.role == TransferRole.SEND ->
                        "两台设备紧挨着放，本机喇叭靠近对方麦克风，音量调大。数据会循环播放，直到对方收全并响起提示音后点停止。"
                    else ->
                        "需要麦克风。先在本机点开始，再让对方开始播放；收全数据包后自动导入并播放提示音。"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            fun missing(perms: List<String>) = perms.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                InkButton(if (running) "停止" else "开始", Modifier.weight(2f).padding(end = 4.dp)) {
                    if (running) {
                        vm.stop()
                    } else {
                        val need = missing(requiredPermissions(o))
                        if (need.isEmpty()) launch() else permissions.launch(need.toTypedArray())
                    }
                }
                InkButton("自检", Modifier.weight(1f).padding(start = 4.dp), enabled = !running) {
                    val hasMic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                    val need = if (hasMic) missing(listOf(Manifest.permission.RECORD_AUDIO)) else emptyList()
                    if (need.isEmpty()) vm.selfTest() else selfTestPermissions.launch(need.toTypedArray())
                }
            }
            RowDivider()
            PhaseView(phase, o, vm, locationOff = o.channel == TransferChannel.BLUETOOTH && locationOff(context))
        }
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        options.forEachIndexed { i, (value, label) ->
            InkButton(
                (if (value == selected) "■ " else "□ ") + label,
                Modifier
                    .weight(1f)
                    .padding(start = if (i == 0) 0.dp else 4.dp, end = if (i == options.lastIndex) 0.dp else 4.dp),
                enabled = enabled || value == selected,
            ) { if (enabled) onSelect(value) }
        }
    }
}

@Composable
private fun PhaseView(phase: TransferPhase, o: TransferOptions, vm: TransferViewModel, locationOff: Boolean) {
    val body = Modifier.padding(top = 12.dp)
    when (phase) {
        TransferPhase.Idle -> Unit
        is TransferPhase.Discovering -> {
            val other = if (o.role == TransferRole.SEND) "接收端" else "发送端"
            Text("正在寻找附近的$other…", style = MaterialTheme.typography.bodyLarge, modifier = body)
            phase.error?.let { Text("上次连接失败：$it", style = MaterialTheme.typography.bodySmall) }
            if (!phase.advertising) {
                Text(
                    "本机不支持蓝牙广播，对方看不到本机，请在本机列表里点击对方。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (phase.peers.isEmpty() && locationOff) {
                Text("Android 11 及以下需要打开系统定位服务才能扫描蓝牙设备。", style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.padding(top = 8.dp)) {
                phase.peers.forEach { peer ->
                    ListRow(
                        title = peer.name,
                        subtitle = "#%04X · 信号 %d dBm".format(peer.tag, peer.rssi),
                        trailing = "连接",
                    ) { vm.pick(peer) }
                }
            }
        }
        is TransferPhase.Working -> Text(phase.text, style = MaterialTheme.typography.bodyLarge, modifier = body)
        is TransferPhase.Verify -> {
            Text("与「${phase.peer}」配对", style = MaterialTheme.typography.bodyLarge, modifier = body)
            Text(
                phase.code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
            Text("确认两台设备显示的数字完全一致。", style = MaterialTheme.typography.bodySmall)
            phase.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            if (!phase.confirmed) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    InkButton("不一致", Modifier.weight(1f).padding(end = 4.dp)) { vm.confirm(false) }
                    InkButton("一致", Modifier.weight(1f).padding(start = 4.dp)) { vm.confirm(true) }
                }
            }
        }
        is TransferPhase.Playing -> {
            Text("正在播放 第 ${phase.round}/${phase.rounds} 轮", style = MaterialTheme.typography.bodyLarge, modifier = body)
            Text(
                "%d 个数据包 · %.1f KB · 每轮约 %.0f 秒".format(phase.packets, phase.kb, phase.seconds),
                style = MaterialTheme.typography.bodySmall,
            )
            if (phase.lowVolume) Text("媒体音量偏低，调大更容易成功。", style = MaterialTheme.typography.bodySmall)
        }
        is TransferPhase.Listening -> {
            val p = phase.progress
            Text(
                when {
                    p == null || p.total == 0 -> "正在聆听…"
                    else -> "已收到 ${p.received}/${p.total} 个数据包"
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = body,
            )
            if (p != null) {
                Text("输入音量 ${levelBar(p.level)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("听到 ${p.heard} 个，损坏 ${p.corrupted} 个", style = MaterialTheme.typography.bodySmall)
                if (p.heard > 4 && p.corrupted * 2 > p.heard) {
                    Text("损坏较多：让两台设备再靠近些，或让发送端打开稳健模式。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is TransferPhase.Done -> Text("✓ ${phase.message}", style = MaterialTheme.typography.bodyLarge, modifier = body)
        is TransferPhase.Failed -> Text("✗ ${phase.message}", style = MaterialTheme.typography.bodyLarge, modifier = body)
        is TransferPhase.Report -> {
            Text("自检结果", style = MaterialTheme.typography.bodyLarge, modifier = body)
            phase.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** Peak level as ten blocks over -60…0 dBFS. */
private fun levelBar(peak: Float): String {
    val db = if (peak <= 0f) -60.0 else 20 * log10(peak.toDouble())
    val n = ((db + 60) / 6).toInt().coerceIn(0, 10)
    return "▮".repeat(n) + "▯".repeat(10 - n)
}
