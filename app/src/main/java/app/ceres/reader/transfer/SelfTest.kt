package app.ceres.reader.transfer

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.ceres.reader.transfer.acoustic.AcousticAudio
import app.ceres.reader.transfer.acoustic.AcousticFraming
import app.ceres.reader.transfer.acoustic.AcousticModem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** On-device checks for both transfer channels; each line starts with ✓ / △ / ✗. */
object SelfTest {
    fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun bluetooth(context: Context): List<String> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return listOf("✗ 蓝牙：本机不支持低功耗蓝牙，只能用声波")
        }
        val lines = mutableListOf<String>()
        if (!granted(context, bluetoothPermissions())) lines += "△ 蓝牙：尚未授权，点「开始」时会申请"
        if (!adapter.isEnabled) {
            lines += "△ 蓝牙：未打开，无法检查是否支持广播"
        } else {
            lines += if (runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(false)) {
                "✓ 蓝牙：支持扫描和广播"
            } else {
                "△ 蓝牙：只能扫描、不能广播。对方看不到本机，需在本机列表里点对方（对方须支持广播）"
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val lm = context.getSystemService(LocationManager::class.java)
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                lines += "△ 定位服务已关闭：Android 11 及以下扫描蓝牙需要打开"
            }
        }
        return lines
    }

    /** Hardware checks, then a loopback: our speaker plays a short burst while our microphone decodes it. */
    suspend fun sound(context: Context, status: (String) -> Unit): List<String> {
        val am = context.getSystemService(AudioManager::class.java)
        val hasSpeaker = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: false
        val hasMic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
            am?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } != false
        val lines = mutableListOf(
            if (hasSpeaker) "✓ 喇叭：有，可作为声波发送端" else "✗ 喇叭：未检测到内置喇叭，不能作为声波发送端",
            if (hasMic) "✓ 麦克风：有，可作为声波接收端" else "✗ 麦克风：未检测到，不能作为声波接收端",
        )
        if (hasSpeaker && AcousticAudio.volumeFraction(context) < 0.5f) lines += "△ 媒体音量低于一半，发送时请调大"
        if (!hasSpeaker || !hasMic) return lines + "— 跳过声波回环测试"
        if (!granted(context, listOf(Manifest.permission.RECORD_AUDIO))) return lines + "△ 麦克风未授权，跳过声波回环测试"

        for (profile in AcousticModem.Profile.entries) {
            val label = if (profile == AcousticModem.Profile.FAST) "快速" else "稳健"
            status("声波回环测试（$label）：本机喇叭播放，本机麦克风接收…")
            val r = try {
                loopback(context, profile)
            } catch (e: TransferException) {
                lines += "✗ 声波回环（$label）：${e.message}"
                break
            }
            lines += if (r.ok) {
                "✓ 声波回环（$label）：${r.total} 个包全部收到，用时 %.1f 秒".format(r.seconds)
            } else {
                "✗ 声波回环（$label）：收到 ${r.received}/${r.total} 个包，听到 ${r.heard} 个、损坏 ${r.corrupted} 个"
            }
            if (r.ok) break
        }
        if (lines.none { it.startsWith("✓ 声波回环") }) {
            lines += "  回环失败不一定代表两台设备之间不行（本机喇叭和麦克风位置可能不利），可以直接试一次互传"
        }
        return lines
    }

    private class Loopback(val ok: Boolean, val received: Int, val total: Int, val heard: Int, val corrupted: Int, val seconds: Double)

    private suspend fun loopback(context: Context, profile: AcousticModem.Profile): Loopback = coroutineScope {
        val packets = AcousticFraming.packetize(Random.nextBytes(AcousticFraming.CHUNK * 4))
        val round = AcousticAudio.roundSeconds(packets, profile)
        val last = AtomicReference<AcousticAudio.ListenProgress?>(null)
        val started = System.nanoTime()
        val listener = async {
            withTimeoutOrNull(((round * 2 + 3) * 1000).toLong()) { AcousticAudio.listen(context) { last.set(it) } }
        }
        delay(300)
        val player = launch { AcousticAudio.broadcast(packets, profile, rounds = 2) {} }
        val got = listener.await()
        player.cancel()
        val seconds = (System.nanoTime() - started) / 1e9
        val p = last.get()
        Loopback(
            ok = got != null,
            received = if (got != null) packets.size else p?.received ?: 0,
            total = packets.size,
            heard = p?.heard ?: 0,
            corrupted = p?.corrupted ?: 0,
            seconds = seconds,
        )
    }

    private fun granted(context: Context, permissions: List<String>) =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
