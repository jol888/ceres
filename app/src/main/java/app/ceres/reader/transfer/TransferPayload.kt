package app.ceres.reader.transfer

import app.ceres.reader.data.CeresBackup
import app.ceres.reader.util.GSON
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** What travels between devices: a [CeresBackup] as deflated JSON. */
object TransferPayload {
    private const val MAX_INFLATED = 16 * 1024 * 1024

    fun encode(backup: CeresBackup, includeWebDav: Boolean): ByteArray {
        val json = GSON.toJson(if (includeWebDav) backup else backup.copy(webDav = null))
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(json.toByteArray())
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Returns the backup JSON. */
    fun decode(bytes: ByteArray): String {
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("数据不完整")
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) throw IllegalArgumentException("数据过大")
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }
}
