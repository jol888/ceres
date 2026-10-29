package app.ceres.reader.rule

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Minimal stand-in for hutool's SymmetricCrypto as used by Legado sources. */
class SymmetricCrypto(private val transformation: String, key: Any?, iv: Any?) {
    private val keyBytes: ByteArray = toBytes(key)
    private var ivBytes: ByteArray? = iv?.let { toBytes(it) }?.takeIf { it.isNotEmpty() }

    fun setIv(iv: Any?): SymmetricCrypto {
        ivBytes = iv?.let { toBytes(it) }?.takeIf { it.isNotEmpty() }
        return this
    }

    private fun cipher(mode: Int): Cipher {
        val algorithm = transformation.substringBefore('/')
        val cipher = Cipher.getInstance(transformation)
        val spec = SecretKeySpec(keyBytes, algorithm)
        val iv = ivBytes
        if (iv != null && !transformation.contains("ECB", true)) {
            cipher.init(mode, spec, IvParameterSpec(iv))
        } else {
            cipher.init(mode, spec)
        }
        return cipher
    }

    fun decrypt(data: Any?): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(cipherBytes(data))
    fun decryptStr(data: Any?): String = String(decrypt(data), Charsets.UTF_8)
    fun encrypt(data: Any?): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(toBytes(data))
    fun encryptBase64(data: Any?): String = Base64.getEncoder().encodeToString(encrypt(data))
    fun encryptHex(data: Any?): String = encrypt(data).toHex()

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")

        fun toBytes(value: Any?): ByteArray = when (value) {
            null -> ByteArray(0)
            is ByteArray -> value
            is List<*> -> ByteArray(value.size) { (value[it] as Number).toByte() }
            else -> JsEngine.toStr(value).toByteArray(Charsets.UTF_8)
        }

        /** Ciphertext given as a string is hex if it looks like hex, otherwise Base64. */
        fun cipherBytes(value: Any?): ByteArray {
            if (value is ByteArray) return value
            val s = JsEngine.toStr(value).trim()
            return if (s.length % 2 == 0 && HEX.matches(s)) s.hexToBytes()
            else Base64.getMimeDecoder().decode(s.replace('-', '+').replace('_', '/'))
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val s = trim()
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
