package app.ceres.reader.transfer.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import app.ceres.reader.transfer.MessagePipe
import app.ceres.reader.transfer.TransferException
import app.ceres.reader.transfer.TransferRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds nearby Ceres devices over BLE and opens a [MessagePipe] to one of them.
 *
 * Both sides advertise *and* scan, so whichever device supports peripheral mode can be the GATT server and
 * either user may tap the other. The transfer direction comes from [TransferRole], not from who connected.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val role: TransferRole,
    private val name: String,
    val tag: Int,
) {
    data class Peer(val device: BluetoothDevice, val name: String, val role: TransferRole, val tag: Int, val rssi: Int, val seenAt: Long)

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** False once the stack refused to advertise; the other device then has to connect to us. */
    private val _advertising = MutableStateFlow(true)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()

    /** Pipes opened by remote centrals that subscribed to our service. */
    val incoming = Channel<MessagePipe>(Channel.UNLIMITED)

    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val serverPipes = ConcurrentHashMap<String, ServerPipe>()
    private var clientGatt: BluetoothGatt? = null
    private var scanning = false
    private var advertisingStarted = false

    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val adapter = adapter ?: throw TransferException("本机没有蓝牙")
        if (!adapter.isEnabled) throw TransferException("蓝牙未打开")
        openServer()
        startScan()
    }

    fun stopDiscovery() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        if (advertisingStarted) runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertisingStarted = false
    }

    fun close() {
        stopDiscovery()
        serverPipes.values.forEach { it.fail("连接已关闭") }
        serverPipes.clear()
        runCatching { server?.close() }
        server = null
        clientGatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        clientGatt = null
        incoming.close()
    }

    // region discovery

    private fun advertise() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported) {
            _advertising.value = false
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build()
        val response = AdvertiseData.Builder().addManufacturerData(MANUFACTURER, advertisement()).build()
        advertiser.startAdvertising(settings, data, response, advertiseCallback)
        advertisingStarted = true
    }

    private fun advertisement(): ByteArray {
        val nameBytes = utf8Prefix(name, MAX_NAME_BYTES)
        return byteArrayOf(MAGIC, role.ordinal.toByte(), (tag ushr 8).toByte(), tag.toByte()) + nameBytes
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            advertisingStarted = false
            _advertising.value = false
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: throw TransferException("无法启动蓝牙扫描")
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)
    }

    private fun onResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER) ?: return
        if (data.size < 4 || data[0] != MAGIC) return
        val peerRole = TransferRole.entries.getOrNull(data[1].toInt()) ?: return
        val peerTag = (data[2].toInt() and 0xff shl 8) or (data[3].toInt() and 0xff)
        val peer = Peer(
            device = result.device,
            name = data.copyOfRange(4, data.size).decodeToString().ifBlank { "Ceres 设备" },
            role = peerRole,
            tag = peerTag,
            rssi = result.rssi,
            seenAt = SystemClock.elapsedRealtime(),
        )
        _peers.update { list ->
            val now = peer.seenAt
            (list.filter { it.device.address != peer.device.address && now - it.seenAt < PEER_TTL_MS } + peer)
                .sortedByDescending { it.rssi }
        }
    }

    // endregion

    // region GATT server (we are the peripheral)

    private fun openServer() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val callback = ServerCallback()
        val gattServer = manager.openGattServer(context, callback) ?: throw TransferException("无法启动蓝牙服务")
        server = gattServer
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        txChar = tx
        // Advertising starts once the service is registered (onServiceAdded).
        if (!gattServer.addService(service)) throw TransferException("无法注册蓝牙服务")
    }

    private inner class ServerCallback : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == SERVICE) advertise() else _advertising.value = false
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) serverPipes.remove(device.address)?.fail("对方已断开连接")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            serverPipes[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = characteristic.uuid == RX && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            }
            if (ok) serverPipes[device.address]?.onFragment(value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != CCCD || value == null) return
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                if (serverPipes.containsKey(device.address)) return
                val pipe = ServerPipe(device, mtus[device.address] ?: DEFAULT_MTU)
                serverPipes[device.address] = pipe
                incoming.trySend(pipe)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (serverPipes.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            serverPipes[device.address]?.onSent(status)
        }
    }

    private val mtus = ConcurrentHashMap<String, Int>()

    private inner class ServerPipe(private val device: BluetoothDevice, @Volatile var mtu: Int) : MessagePipe {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val joiner = Fragments.Joiner()
        private val sendLock = Mutex()
        @Volatile private var pending: CompletableDeferred<Int>? = null

        fun onFragment(fragment: ByteArray) {
            runCatching { joiner.push(fragment) }
                .onSuccess { msg -> if (msg != null) inbox.trySend(msg) }
                .onFailure { fail(it.message ?: "数据错误") }
        }

        fun onSent(status: Int) {
            pending?.complete(status)
        }

        fun fail(reason: String) {
            inbox.close(TransferException(reason))
            pending?.completeExceptionally(TransferException(reason))
        }

        override suspend fun send(message: ByteArray) = sendLock.withLock {
            val gattServer = server ?: throw TransferException("连接已关闭")
            val tx = txChar ?: throw TransferException("连接已关闭")
            for (fragment in Fragments.split(message, mtu - 3)) {
                val done = CompletableDeferred<Int>()
                pending = done
                if (!notify(gattServer, tx, fragment)) throw TransferException("蓝牙发送失败")
                val status = withTimeout(OP_TIMEOUT_MS) { done.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw TransferException("蓝牙发送失败（$status）")
            }
        }

        @Suppress("DEPRECATION")
        private fun notify(gattServer: BluetoothGattServer, tx: BluetoothGattCharacteristic, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, tx, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                tx.value = value
                gattServer.notifyCharacteristicChanged(device, tx, false)
            }

        override suspend fun receive(): ByteArray = inbox.receive()

        override fun close() {
            serverPipes.remove(device.address)
            runCatching { server?.cancelConnection(device) }
            inbox.close()
        }
    }

    // endregion

    // region GATT client (we are the central)

    /** Connects to [peer]'s GATT server and subscribes; throws [TransferException] on failure. */
    suspend fun connect(peer: Peer): MessagePipe {
        var last: Exception? = null
        repeat(CONNECT_ATTEMPTS) {
            val pipe = ClientPipe()
            try {
                return withTimeout(CONNECT_TIMEOUT_MS) { pipe.open(peer.device) }
            } catch (e: Exception) {
                pipe.close()
                last = e
            }
        }
        throw TransferException("连接失败：${last?.message ?: "超时"}")
    }

    private inner class ClientPipe : BluetoothGattCallback(), MessagePipe {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val joiner = Fragments.Joiner()
        private val ready = CompletableDeferred<Unit>()
        private val sendLock = Mutex()
        @Volatile private var pending: CompletableDeferred<Int>? = null
        @Volatile private var mtu = DEFAULT_MTU
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null

        @Suppress("DEPRECATION")
        suspend fun open(device: BluetoothDevice): MessagePipe {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
                ?: throw TransferException("无法连接")
            clientGatt = gatt
            ready.await()
            return this
        }

        private fun fail(reason: String) {
            val e = TransferException(reason)
            ready.completeExceptionally(e)
            pending?.completeExceptionally(e)
            inbox.close(e)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    if (!gatt.requestMtu(MAX_MTU)) gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> {
                    fail(if (ready.isCompleted) "对方已断开连接" else "连接失败（$status）")
                    runCatching { gatt.close() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE)
            val rx = service?.getCharacteristic(RX)
            val tx = service?.getCharacteristic(TX)
            val cccd = tx?.getDescriptor(CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || cccd == null) {
                fail("对方没有开启 Ceres 互传")
                return
            }
            this.rx = rx
            gatt.setCharacteristicNotification(tx, true)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            if (!ok) fail("订阅失败")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit) else fail("订阅失败（$status）")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX) onFragment(value)
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX) onFragment(characteristic.value ?: return)
        }

        private fun onFragment(fragment: ByteArray) {
            runCatching { joiner.push(fragment) }
                .onSuccess { msg -> if (msg != null) inbox.trySend(msg) }
                .onFailure { fail(it.message ?: "数据错误") }
        }

        override suspend fun send(message: ByteArray) = sendLock.withLock {
            val gatt = gatt ?: throw TransferException("连接已关闭")
            val rx = rx ?: throw TransferException("连接已关闭")
            for (fragment in Fragments.split(message, mtu - 3)) {
                val done = CompletableDeferred<Int>()
                pending = done
                if (!write(gatt, rx, fragment)) throw TransferException("蓝牙发送失败")
                val status = withTimeout(OP_TIMEOUT_MS) { done.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw TransferException("蓝牙发送失败（$status）")
            }
        }

        @Suppress("DEPRECATION")
        private fun write(gatt: BluetoothGatt, rx: BluetoothGattCharacteristic, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rx, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = value
                gatt.writeCharacteristic(rx)
            }

        override suspend fun receive(): ByteArray = inbox.receive()

        override fun close() {
            inbox.close()
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            if (clientGatt === gatt) clientGatt = null
            gatt = null
        }
    }

    // endregion

    companion object {
        private val SERVICE: UUID = UUID.fromString("6c0e7a52-3f1b-4d8e-9a4c-0b5e2f1d7c39")
        private val RX: UUID = UUID.fromString("6c0e7a52-3f1b-4d8e-9a4c-0b5e2f1d7c3a")
        private val TX: UUID = UUID.fromString("6c0e7a52-3f1b-4d8e-9a4c-0b5e2f1d7c3b")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Bluetooth SIG "reserved for testing" company id. */
        private const val MANUFACTURER = 0xFFFF
        private const val MAGIC: Byte = 0xCE.toByte()

        /** Scan response: 31 bytes − AD header(2) − company id(2) − magic/role/tag(4). */
        private const val MAX_NAME_BYTES = 23
        private const val DEFAULT_MTU = 23
        private const val MAX_MTU = 517
        private const val PEER_TTL_MS = 10_000L
        private const val OP_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CONNECT_ATTEMPTS = 2

        internal fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
            var end = 0
            var size = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val n = String(Character.toChars(cp)).toByteArray().size
                if (size + n > maxBytes) break
                size += n
                end += Character.charCount(cp)
            }
            return text.substring(0, end).toByteArray()
        }
    }
}
