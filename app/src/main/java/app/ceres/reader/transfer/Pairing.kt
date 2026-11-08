package app.ceres.reader.transfer

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ephemeral P-256 ECDH, like Bluetooth "numeric comparison": both screens show a 6-digit code derived from
 * the shared secret and both public keys, so a man in the middle ends up with mismatching codes.
 */
class Pairing private constructor(private val keyPair: KeyPair) {
    val publicKey: ByteArray get() = keyPair.public.encoded

    class Session(private val key: ByteArray, val code: String) {
        private val random = SecureRandom()

        fun seal(plain: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plain)
        }

        fun open(sealed: ByteArray): ByteArray {
            require(sealed.size > NONCE) { "密文过短" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, NONCE))
            return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
        }
    }

    fun agree(peerPublicKey: ByteArray): Session {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(peer, true)
            generateSecret()
        }
        val (a, b) = listOf(publicKey, peerPublicKey).sortedWith { x, y -> compare(x, y) }
        val salt = MessageDigest.getInstance("SHA-256").digest(a + b)
        val prk = hmac(salt, shared)
        val key = hmac(prk, "ceres-transfer key\u0001".toByteArray()).copyOf(16)
        val c = hmac(prk, "ceres-transfer code\u0001".toByteArray())
        val n = ((c[0].toLong() and 0xff) shl 24 or ((c[1].toLong() and 0xff) shl 16) or
            ((c[2].toLong() and 0xff) shl 8) or (c[3].toLong() and 0xff)) % 1_000_000
        return Session(key, "%06d".format(n))
    }

    companion object {
        private const val NONCE = 12

        fun create(): Pairing = Pairing(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
        )

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private fun compare(x: ByteArray, y: ByteArray): Int {
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return x.size - y.size
        }
    }
}
