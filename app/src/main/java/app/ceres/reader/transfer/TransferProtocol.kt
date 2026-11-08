package app.ceres.reader.transfer

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Reliable, ordered message link between two devices. */
interface MessagePipe {
    suspend fun send(message: ByteArray)
    suspend fun receive(): ByteArray
    fun close()
}

enum class TransferRole { SEND, RECEIVE }

class TransferException(message: String) : Exception(message)

/**
 * `HELLO` both ways → both users compare the code → `CONFIRM`/`REJECT` both ways → sender `DATA` (sealed) →
 * receiver `DONE` with the restore result.
 */
class TransferProtocol(
    private val role: TransferRole,
    private val name: String,
    private val pairing: Pairing = Pairing.create(),
) {
    interface Callbacks {
        /** Shows the code and suspends until the local user accepts (true) or rejects (false). */
        suspend fun confirm(code: String, peerName: String): Boolean

        /** Progress while the code is on screen. */
        fun note(text: String)
        fun status(text: String)
    }

    /** Sender: returns the receiver's result message. */
    suspend fun send(pipe: MessagePipe, payload: ByteArray, ui: Callbacks): String {
        check(role == TransferRole.SEND)
        val session = handshake(pipe, ui)
        ui.status("正在发送 ${"%.1f".format(payload.size / 1024.0)} KB…")
        pipe.send(message(DATA) { write(session.seal(payload)) })
        val done = pipe.receive()
        if (done.firstOrNull() != DONE) throw TransferException("对方返回了无法识别的消息")
        val ok = done[1].toInt() != 0
        val text = done.copyOfRange(2, done.size).decodeToString()
        if (!ok) throw TransferException("对方导入失败：$text")
        return text
    }

    /** Receiver: [apply] imports the payload and returns a message shown on both devices. */
    suspend fun receive(pipe: MessagePipe, ui: Callbacks, apply: suspend (ByteArray) -> String): String {
        check(role == TransferRole.RECEIVE)
        val session = handshake(pipe, ui)
        ui.status("等待数据…")
        val data = pipe.receive()
        if (data.firstOrNull() != DATA) throw TransferException("对方返回了无法识别的消息")
        val payload = runCatching { session.open(data.copyOfRange(1, data.size)) }
            .getOrElse { throw TransferException("数据校验失败") }
        ui.status("正在导入…")
        val result = runCatching { apply(payload) }
        pipe.send(
            message(DONE) {
                writeByte(if (result.isSuccess) 1 else 0)
                write((result.getOrNull() ?: result.exceptionOrNull()?.message ?: "未知错误").toByteArray())
            },
        )
        return result.getOrThrow()
    }

    private suspend fun handshake(pipe: MessagePipe, ui: Callbacks): Pairing.Session {
        ui.status("正在交换密钥…")
        pipe.send(
            message(HELLO) {
                writeByte(VERSION)
                writeByte(role.ordinal)
                writeUTF(name.take(40))
                writeShort(pairing.publicKey.size)
                write(pairing.publicKey)
            },
        )
        val hello = pipe.receive()
        if (hello.firstOrNull() != HELLO) throw TransferException("对方不是 Ceres 设备")
        val input = DataInputStream(hello.inputStream(1, hello.size - 1))
        if (input.readByte().toInt() != VERSION) throw TransferException("对方 Ceres 版本不兼容，请两边都更新")
        val peerRole = TransferRole.entries.getOrNull(input.readByte().toInt())
        if (peerRole == role) {
            throw TransferException(if (role == TransferRole.SEND) "对方也是发送端" else "对方也是接收端")
        }
        val peerName = input.readUTF()
        val peerKey = ByteArray(input.readUnsignedShort()).also(input::readFully)
        val session = runCatching { pairing.agree(peerKey) }.getOrElse { throw TransferException("密钥交换失败") }

        coroutineScope {
            val peer = async { pipe.receive() }
            val mine = async { ui.confirm(session.code, peerName) }
            var peerDecision: ByteArray? = null
            val accepted = select {
                mine.onAwait { it }
                peer.onAwait { msg ->
                    peerDecision = msg
                    if (msg.firstOrNull() != CONFIRM) {
                        mine.cancel()
                        throw TransferException("对方取消了配对")
                    }
                    ui.note("对方已确认，等待本机确认…")
                    mine.await()
                }
            }
            pipe.send(byteArrayOf(if (accepted) CONFIRM else REJECT))
            if (!accepted) {
                peer.cancel()
                throw TransferException("已取消")
            }
            if (peerDecision == null) ui.note("等待对方确认配对码…")
            if ((peerDecision ?: peer.await()).firstOrNull() != CONFIRM) throw TransferException("对方取消了配对")
        }
        return session
    }

    private inline fun message(type: Byte, body: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(type.toInt())
            body()
            flush()
        }
        return out.toByteArray()
    }

    private companion object {
        const val VERSION = 1
        const val HELLO: Byte = 1
        const val CONFIRM: Byte = 2
        const val REJECT: Byte = 3
        const val DATA: Byte = 4
        const val DONE: Byte = 5
    }
}
