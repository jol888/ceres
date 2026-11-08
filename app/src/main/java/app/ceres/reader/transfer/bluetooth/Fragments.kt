package app.ceres.reader.transfer.bluetooth

import java.io.ByteArrayOutputStream

/** Splits messages into GATT-sized fragments: `flag(1) | data`, flag 1 marks the last fragment. */
internal object Fragments {
    private const val MAX_MESSAGE = 8 * 1024 * 1024

    fun split(message: ByteArray, fragmentSize: Int): List<ByteArray> {
        val chunk = fragmentSize - 1
        require(chunk > 0)
        val count = maxOf(1, (message.size + chunk - 1) / chunk)
        return List(count) { i ->
            val from = i * chunk
            val to = minOf(message.size, from + chunk)
            byteArrayOf(if (i == count - 1) 1 else 0) + message.copyOfRange(from, to)
        }
    }

    class Joiner {
        private val buf = ByteArrayOutputStream()

        /** Returns the whole message when [fragment] is its last piece. */
        fun push(fragment: ByteArray): ByteArray? {
            if (fragment.isEmpty()) return null
            buf.write(fragment, 1, fragment.size - 1)
            if (buf.size() > MAX_MESSAGE) throw IllegalStateException("消息过大")
            if (fragment[0].toInt() != 1) return null
            return buf.toByteArray().also { buf.reset() }
        }
    }
}
