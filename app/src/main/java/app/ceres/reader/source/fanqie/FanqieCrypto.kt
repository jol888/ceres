package app.ceres.reader.source.fanqie

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fanqie App 的四神签名原语。
 *
 * 只包含「算出一个请求头」需要的东西，网络与设备身份在 [FanqieClient]。
 * 所有 64 位运算用 Kotlin Long 的无符号位移完成，和真机 .so 的 uint64_t 行为一致。
 */
object FanqieCrypto {

    private val SIGN_KEY = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
    private val INNER_SALT = byteArrayOf(0xf2.toByte(), 0x81.toByte(), 0x61, 0x6f)
    private const val SIMON_Z = 0x3DC94C3A046D678BL

    private val ARGUS_PREFIX = byteArrayOf(
        0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(),
        0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(),
    )
    private val ARGUS_HEAD = byteArrayOf(
        0xa6.toByte(), 0x6e, 0xad.toByte(), 0x9f.toByte(), 0x77, 0x01,
        0xd0.toByte(), 0x0c, 0x18,
    )

    // ---------------------------------------------------------------------
    // 基础工具
    // ---------------------------------------------------------------------

    fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    fun bytesToHex(data: ByteArray): String = data.toHex()

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun pkcs7(data: ByteArray, block: Int = 16): ByteArray {
        val pad = block - data.size % block
        val out = data.copyOf(data.size + pad)
        java.util.Arrays.fill(out, data.size, out.size, pad.toByte())
        return out
    }

    private fun rol32(x: Int, n: Int): Int = Integer.rotateLeft(x, n and 31)

    private fun ror64(v: Long, n: Int): Long {
        val s = n and 63
        return if (s == 0) v else (v ushr s) or (v shl (64 - s))
    }

    private fun rol64(v: Long, n: Int): Long {
        val s = n and 63
        return if (s == 0) v else (v shl s) or (v ushr (64 - s))
    }

    private fun le64(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[off + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun le64Bytes(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((v ushr (8 * i)) and 0xff).toByte()
        return out
    }

    private fun writeLe64(data: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) data[off + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    // ---------------------------------------------------------------------
    // SM3
    // ---------------------------------------------------------------------

    fun sm3(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8
        val padLen = (56 - (msg.size + 1) % 64 + 64) % 64
        val data = ByteArray(msg.size + 1 + padLen + 8)
        System.arraycopy(msg, 0, data, 0, msg.size)
        data[msg.size] = 0x80.toByte()
        for (i in 0 until 8) {
            data[data.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
        }

        val iv = intArrayOf(
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
            0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
        )
        val w = IntArray(68)
        val w1 = IntArray(64)

        var off = 0
        while (off < data.size) {
            for (j in 0 until 16) {
                val p = off + j * 4
                w[j] = ((data[p].toInt() and 0xff) shl 24) or
                    ((data[p + 1].toInt() and 0xff) shl 16) or
                    ((data[p + 2].toInt() and 0xff) shl 8) or
                    (data[p + 3].toInt() and 0xff)
            }
            for (j in 16 until 68) {
                val x = w[j - 16] xor w[j - 9] xor rol32(w[j - 3], 15)
                w[j] = (x xor rol32(x, 15) xor rol32(x, 23)) xor
                    rol32(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = iv[0]; var b = iv[1]; var c = iv[2]; var d = iv[3]
            var e = iv[4]; var f = iv[5]; var g = iv[6]; var h = iv[7]
            for (j in 0 until 64) {
                val tj = if (j <= 15) 0x79cc4519 else 0x7a879d8a
                val a12 = rol32(a, 12)
                val ss1 = rol32(a12 + e + rol32(tj, j), 7)
                val ss2 = ss1 xor a12
                val ff = if (j <= 15) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j <= 15) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rol32(b, 9)
                b = a
                a = tt1
                h = g
                g = rol32(f, 19)
                f = e
                e = tt2 xor rol32(tt2, 9) xor rol32(tt2, 17)
            }
            iv[0] = iv[0] xor a; iv[1] = iv[1] xor b
            iv[2] = iv[2] xor c; iv[3] = iv[3] xor d
            iv[4] = iv[4] xor e; iv[5] = iv[5] xor f
            iv[6] = iv[6] xor g; iv[7] = iv[7] xor h
            off += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = ((iv[i] ushr 24) and 0xff).toByte()
            out[i * 4 + 1] = ((iv[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 2] = ((iv[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 3] = (iv[i] and 0xff).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------------
    // SIMON64/128
    // ---------------------------------------------------------------------

    private fun simonKeyExpansion(key: ByteArray): LongArray {
        require(key.size == 32)
        val k = LongArray(72)
        for (i in 0 until 4) k[i] = le64(key, i * 8)
        for (i in 4 until 72) {
            var tmp = ror64(k[i - 1], 3) xor k[i - 3]
            tmp = tmp xor ror64(tmp, 1)
            val bit = (SIMON_Z ushr ((i - 4) % 62)) and 1L
            k[i] = k[i - 4].inv() xor tmp xor bit xor 3L
        }
        return k
    }

    private fun simonEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(data.size % 16 == 0)
        val rk = simonKeyExpansion(key)
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            var x = le64(data, off)
            var y = le64(data, off + 8)
            for (i in 0 until 72) {
                val f = rol64(y, 1) and rol64(y, 8)
                val ny = x xor f xor rol64(y, 2) xor rk[i]
                x = y
                y = ny
            }
            out.write(le64Bytes(x))
            out.write(le64Bytes(y))
            off += 16
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------
    // X-Ladon / X-Helios
    // ---------------------------------------------------------------------

    private fun ladonTable(md5HexAscii: ByteArray): ByteArray {
        val table = ByteArray(288)
        System.arraycopy(md5HexAscii, 0, table, 0, 32)
        var b0 = le64(table, 0)
        var b8 = le64(table, 8)
        val queue = ArrayDeque<Long>()
        queue.addLast(le64(table, 16))
        queue.addLast(le64(table, 24))
        for (i in 0 until 34) {
            val x9 = b0
            var x8 = ror64(b8, 8) + x9
            x8 = x8 xor i.toLong()
            queue.addLast(x8)
            x8 = x8 xor ror64(x9, 61)
            writeLe64(table, (i + 1) * 8, x8)
            b0 = x8
            b8 = queue.removeFirst()
        }
        return table
    }

    fun ladon(khronos: Long, random4: ByteArray): String {
        require(random4.size == 4)
        val md5Hex = md5(random4 + "1967".toByteArray()).toHex().toByteArray(Charsets.US_ASCII)
        val table = ladonTable(md5Hex)
        val padded = pkcs7("$khronos-1611921764-3019".toByteArray(), 16)
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < padded.size) {
            var d0 = le64(padded, off)
            var d1 = le64(padded, off + 8)
            for (i in 0 until 34) {
                val h = le64(table, i * 8)
                d1 = h xor (d0 + ror64(d1, 8))
                d0 = d1 xor ror64(d0, 0x3d)
            }
            out.write(le64Bytes(d0))
            out.write(le64Bytes(d1))
            off += 16
        }
        return Base64.getEncoder().encodeToString(random4 + out.toByteArray())
    }

    // ---------------------------------------------------------------------
    // X-Argus
    // ---------------------------------------------------------------------

    private fun varint(value: Long): ByteArray {
        var v = value and 0xffffffffL
        val out = ByteArrayOutputStream()
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun pbField(idx: Int, wire: Int, payload: ByteArray): ByteArray =
        varint(((idx shl 3) or wire).toLong()) + payload

    private fun pbVarint(idx: Int, value: Long): ByteArray = pbField(idx, 0, varint(value))

    private fun pbBytes(idx: Int, data: ByteArray): ByteArray =
        pbField(idx, 2, varint(data.size.toLong()) + data)

    private fun innerKey(): ByteArray = sm3(SIGN_KEY + INNER_SALT + SIGN_KEY)

    private fun encryptEncPb(data: ByteArray, length: Int): ByteArray {
        val d = data.copyOf()
        val xor = d.copyOfRange(0, 8)
        for (i in 8 until length) {
            d[i] = (d[i].toInt() xor xor[i % 8].toInt()).toByte()
        }
        d.reverse()
        return d
    }

    fun argus(
        query: String,
        timestamp: Long,
        deviceId: String,
        versionName: String,
        deviceType: String,
        osVersion: String,
        randVal: Long? = null,
    ): String {
        val rv = randVal ?: (java.security.SecureRandom().nextInt().toLong() and 0x7fffffffL)
        val nested = pbBytes(1, deviceType.toByteArray()) +
            pbBytes(2, osVersion.toByteArray()) +
            pbBytes(3, "googleplay".toByteArray()) +
            pbVarint(4, 0x48000000L)

        val pb = pbVarint(1, (0x20200929L shl 1) and 0xffffffffL) +
            pbVarint(2, 2) +
            pbVarint(3, rv) +
            pbBytes(4, "1967".toByteArray()) +
            pbBytes(5, deviceId.toByteArray()) +
            pbBytes(6, "1611921764".toByteArray()) +
            pbBytes(7, versionName.toByteArray()) +
            pbBytes(8, "v04.04.05-ov-android".toByteArray()) +
            pbVarint(9, 134744640L) +
            pbBytes(10, ByteArray(8)) +
            pbVarint(11, 0) +
            pbVarint(12, (timestamp shl 1) and 0xffffffffL) +
            pbBytes(13, sm3(ByteArray(16)).copyOf(6)) +
            pbBytes(14, sm3(query.toByteArray()).copyOf(6)) +
            pbVarint(20, 738) +
            pbBytes(23, nested)

        val encPb = simonEncrypt(pkcs7(pb, 16), innerKey())
        val inner = encryptEncPb(ARGUS_PREFIX + encPb, encPb.size + 8)
        val buf = ARGUS_HEAD + inner + "ao".toByteArray()
        val aesKey = md5(SIGN_KEY.copyOfRange(0, 16))
        val aesIv = md5(SIGN_KEY.copyOfRange(16, 32))
        val encrypted = aesCbcEncrypt(buf, aesKey, aesIv)
        return Base64.getEncoder().encodeToString(byteArrayOf(0xf2.toByte(), 0x81.toByte()) + encrypted)
    }
}
