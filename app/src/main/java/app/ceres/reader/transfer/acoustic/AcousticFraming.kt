package app.ceres.reader.transfer.acoustic

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Splits a payload into independently decodable packets for carousel playback (the sender loops until
 * stopped; the receiver keeps whatever it hears). Packet layout:
 * `session(4) | index(2) | count(2) | chunk | crc32(4)` where session is the CRC32 of the whole payload.
 */
object AcousticFraming {
    /** Fills a 128-byte codeword: 64 carriers × 2 bytes. */
    const val CHUNK = 92
    private const val OVERHEAD = 12

    fun packetize(payload: ByteArray, chunk: Int = CHUNK): List<ByteArray> {
        require(chunk + OVERHEAD <= AcousticModem.MAX_DATA)
        val session = crc(payload, 0, payload.size)
        val count = maxOf(1, (payload.size + chunk - 1) / chunk)
        require(count <= 0xffff) { "payload too large" }
        return List(count) { i ->
            val from = i * chunk
            val n = minOf(chunk, payload.size - from)
            val b = ByteBuffer.allocate(8 + n + 4)
            b.putInt(session).putShort(i.toShort()).putShort(count.toShort()).put(payload, from, n)
            b.putInt(crc(b.array(), 0, 8 + n))
            b.array()
        }
    }

    private fun crc(bytes: ByteArray, off: Int, len: Int): Int =
        CRC32().apply { update(bytes, off, len) }.value.toInt()

    /** Collects packets until a whole payload is present. */
    class Assembler {
        private var session = 0
        private var chunks: Array<ByteArray?> = emptyArray()

        val received get() = chunks.count { it != null }
        val total get() = chunks.size

        /** Returns the payload once complete. Packets from a different payload restart collection. */
        fun accept(packet: ByteArray): ByteArray? {
            if (packet.size < OVERHEAD + 1) return null
            val b = ByteBuffer.wrap(packet)
            val s = b.int
            val index = b.short.toInt() and 0xffff
            val count = b.short.toInt() and 0xffff
            val bodyLen = packet.size - OVERHEAD
            if (crc(packet, 0, 8 + bodyLen) != ByteBuffer.wrap(packet, 8 + bodyLen, 4).int) return null
            if (count == 0 || index >= count) return null
            if (s != session || count != chunks.size) {
                session = s
                chunks = arrayOfNulls(count)
            }
            chunks[index] = packet.copyOfRange(8, 8 + bodyLen)
            if (chunks.any { it == null }) return null
            val out = java.io.ByteArrayOutputStream()
            chunks.forEach { out.write(it!!) }
            val payload = out.toByteArray()
            return payload.takeIf { crc(it, 0, it.size) == session }
        }
    }
}
