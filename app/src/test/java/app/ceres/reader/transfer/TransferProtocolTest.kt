package app.ceres.reader.transfer

import app.ceres.reader.data.BackupWebDav
import app.ceres.reader.data.CeresBackup
import app.ceres.reader.data.SourceSubscription
import app.ceres.reader.transfer.bluetooth.BleLink
import app.ceres.reader.transfer.bluetooth.Fragments
import app.ceres.reader.util.GSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TransferProtocolTest {

    private class MemoryPipe(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) : MessagePipe {
        override suspend fun send(message: ByteArray) = outbox.send(message)
        override suspend fun receive(): ByteArray = inbox.receive()
        override fun close() = Unit
    }

    private fun pipes(): Pair<MessagePipe, MessagePipe> {
        val a = Channel<ByteArray>(Channel.UNLIMITED)
        val b = Channel<ByteArray>(Channel.UNLIMITED)
        return MemoryPipe(a, b) to MemoryPipe(b, a)
    }

    private class Ui(private val accept: Boolean, private val gate: CompletableDeferred<Unit>? = null) : TransferProtocol.Callbacks {
        var code: String? = null
        var peer: String? = null
        override suspend fun confirm(code: String, peerName: String): Boolean {
            this.code = code
            peer = peerName
            gate?.await()
            return accept
        }
        override fun note(text: String) = Unit
        override fun status(text: String) = Unit
    }

    @Test
    fun deliversPayloadAfterBothConfirm() = runBlocking {
        val (s, r) = pipes()
        val payload = Random(1).nextBytes(50_000)
        val sendUi = Ui(true)
        val recvUi = Ui(true)
        var got: ByteArray? = null
        val sender = async { TransferProtocol(TransferRole.SEND, "phone").send(s, payload, sendUi) }
        val receiver = async {
            TransferProtocol(TransferRole.RECEIVE, "reader").receive(r, recvUi) { got = it; "已恢复 3 个书源" }
        }
        assertEquals("已恢复 3 个书源", sender.await())
        assertEquals("已恢复 3 个书源", receiver.await())
        assertArrayEquals(payload, got)
        assertEquals(sendUi.code, recvUi.code)
        assertEquals(6, sendUi.code!!.length)
        assertEquals("reader", sendUi.peer)
        assertEquals("phone", recvUi.peer)
    }

    @Test
    fun peerRejectionStopsBothSides() = runBlocking {
        val (s, r) = pipes()
        val gate = CompletableDeferred<Unit>()
        val sender = async { runCatching { TransferProtocol(TransferRole.SEND, "a").send(s, byteArrayOf(1), Ui(true, gate)) } }
        val receiver = async { runCatching { TransferProtocol(TransferRole.RECEIVE, "b").receive(r, Ui(false)) { "x" } } }
        val recvError = receiver.await().exceptionOrNull()
        // The sender's dialog is still open when the reject arrives; it must not wait for its own user.
        val sendError = sender.await().exceptionOrNull()
        gate.complete(Unit)
        assertEquals("已取消", recvError?.message)
        assertEquals("对方取消了配对", sendError?.message)
    }

    @Test
    fun sameRolesAreRefused() = runBlocking {
        val (a, b) = pipes()
        val x = async { runCatching { TransferProtocol(TransferRole.SEND, "a").send(a, byteArrayOf(1), Ui(true)) } }
        val y = async { runCatching { TransferProtocol(TransferRole.SEND, "b").send(b, byteArrayOf(1), Ui(true)) } }
        assertEquals("对方也是发送端", x.await().exceptionOrNull()?.message)
        assertEquals("对方也是发送端", y.await().exceptionOrNull()?.message)
    }

    @Test
    fun importFailureIsReportedToSender() = runBlocking {
        val (s, r) = pipes()
        val sender = async { runCatching { TransferProtocol(TransferRole.SEND, "a").send(s, byteArrayOf(1), Ui(true)) } }
        val receiver = async {
            runCatching { TransferProtocol(TransferRole.RECEIVE, "b").receive(r, Ui(true)) { throw IllegalArgumentException("坏数据") } }
        }
        assertEquals("坏数据", receiver.await().exceptionOrNull()?.message)
        assertEquals("对方导入失败：坏数据", sender.await().exceptionOrNull()?.message)
    }

    @Test
    fun manInTheMiddleSeesDifferentCodes() {
        val alice = Pairing.create()
        val bob = Pairing.create()
        val mallory = Pairing.create()
        val aliceSide = alice.agree(mallory.publicKey)
        val bobSide = bob.agree(mallory.publicKey)
        assertNotEquals(aliceSide.code, bobSide.code)
        val direct = alice.agree(bob.publicKey)
        assertEquals(direct.code, bob.agree(alice.publicKey).code)
        val sealed = direct.seal("hello".toByteArray())
        assertEquals("hello", bob.agree(alice.publicKey).open(sealed).decodeToString())
        assertTrue(runCatching { bobSide.open(sealed) }.isFailure)
    }

    @Test
    fun fragmentsRoundTrip() {
        val joiner = Fragments.Joiner()
        for (size in listOf(0, 1, 19, 20, 21, 513, 5_000)) {
            val msg = Random(size).nextBytes(size)
            val parts = Fragments.split(msg, 20)
            parts.dropLast(1).forEach { assertNull(joiner.push(it)) }
            assertArrayEquals(msg, joiner.push(parts.last()))
            assertTrue(parts.all { it.size <= 20 })
        }
    }

    @Test
    fun advertisedNameIsTruncatedOnCharacterBoundaries() {
        assertEquals("文石 Note", BleLink.utf8Prefix("文石 Note", 23).decodeToString())
        val cut = BleLink.utf8Prefix("掌阅iReader Smart X3 Pro", 23)
        assertTrue(cut.size <= 23)
        assertEquals("掌阅iReader Smart X3 ", cut.decodeToString())
        assertEquals("", BleLink.utf8Prefix("😀", 3).decodeToString())
    }

    @Test
    fun payloadDropsWebDavOnRequest() {
        val backup = CeresBackup(
            exportedAt = 1,
            sourceUrls = listOf(SourceSubscription("https://example.com/s.json", 2)),
            webDav = BackupWebDav("https://dav.example.com/", "me", "secret"),
        )
        val full = GSON.fromJson(TransferPayload.decode(TransferPayload.encode(backup, true)), CeresBackup::class.java)
        assertEquals("secret", full.webDav?.password)
        assertEquals(backup.sourceUrls, full.sourceUrls)
        val bare = GSON.fromJson(TransferPayload.decode(TransferPayload.encode(backup, false)), CeresBackup::class.java)
        assertNull(bare.webDav)
        assertTrue(runCatching { TransferPayload.decode(byteArrayOf(1, 2, 3)) }.isFailure)
    }
}
