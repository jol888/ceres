package app.ceres.reader.transfer.acoustic

import app.ceres.reader.transfer.acoustic.AcousticModem.Profile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class AcousticModemTest {

    @Test
    fun reedSolomonCorrectsUpToHalfTheParity() {
        val rnd = Random(1)
        val rs = ReedSolomon(24)
        repeat(200) {
            val data = rnd.nextBytes(rnd.nextInt(1, 231))
            val code = rs.encode(data)
            val broken = code.copyOf()
            val errors = rnd.nextInt(0, 13)
            (code.indices).shuffled(rnd).take(errors).forEach { broken[it] = (broken[it].toInt() xor rnd.nextInt(1, 256)).toByte() }
            assertArrayEquals(data, rs.decode(broken))
        }
    }

    @Test
    fun reedSolomonRejectsTooManyErrors() {
        val rnd = Random(2)
        val rs = ReedSolomon(4)
        var rejected = 0
        repeat(200) {
            val data = rnd.nextBytes(4)
            val broken = rs.encode(data)
            (0 until 8).shuffled(rnd).take(4).forEach { broken[it] = (broken[it].toInt() xor rnd.nextInt(1, 256)).toByte() }
            if (rs.decode(broken) == null) rejected++
        }
        // A 4-byte-parity code can't detect every 4-error pattern, but most must be refused.
        assert(rejected > 150) { "rejected only $rejected" }
    }

    @Test
    fun cleanLoopback() {
        for (profile in Profile.entries) {
            val payload = Random(3).nextBytes(700)
            assertArrayEquals(payload, transmit(payload, profile, Channel()))
        }
    }

    @Test
    fun survivesRealisticChannel() {
        for (profile in Profile.entries) {
            val payload = Random(4).nextBytes(1500)
            val channel = Channel(gain = 0.2, snrDb = 18.0, clockPpm = 120.0, echoes = listOf(37 to 0.4, 400 to 0.15), lead = 12_345)
            assertArrayEquals("profile $profile", payload, transmit(payload, profile, channel))
        }
    }

    @Test
    fun robustProfileSurvivesNoisierChannel() {
        val payload = Random(5).nextBytes(500)
        val channel = Channel(gain = 0.05, snrDb = 9.0, clockPpm = -150.0, echoes = listOf(20 to 0.5))
        assertArrayEquals(payload, transmit(payload, Profile.ROBUST, channel, rounds = 3))
    }

    @Test
    fun ignoresNoiseAndSilence() {
        val rnd = java.util.Random(6)
        val demod = AcousticDemodulator()
        val noise = FloatArray(AcousticModem.SAMPLE_RATE * 5) { (rnd.nextGaussian() * 0.1).toFloat() }
        assertEquals(0, demod.feed(noise).size)
        assertEquals(0, demod.feed(FloatArray(AcousticModem.SAMPLE_RATE)).size)
    }

    @Test
    fun assemblerRestartsOnNewSessionAndChecksCrc() {
        val a = AcousticFraming.packetize(Random(7).nextBytes(300))
        val b = AcousticFraming.packetize(Random(8).nextBytes(300))
        val asm = AcousticFraming.Assembler()
        assertNull(asm.accept(a[0]))
        assertNull(asm.accept(b[0]))
        val corrupt = b[1].copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(asm.accept(corrupt))
        assertNull(asm.accept(b[1]))
        assertNull(asm.accept(b[2]))
        assertNotNull(asm.accept(b[3]))
    }

    private data class Channel(
        val gain: Double = 1.0,
        val snrDb: Double = 60.0,
        val clockPpm: Double = 0.0,
        val echoes: List<Pair<Int, Double>> = emptyList(),
        val lead: Int = 3_000,
    )

    private fun transmit(payload: ByteArray, profile: Profile, ch: Channel, rounds: Int = 2): ByteArray? {
        val packets = AcousticFraming.packetize(payload)
        val air = ArrayList<Float>()
        repeat(ch.lead) { air += 0f }
        repeat(rounds) { packets.forEach { p -> AcousticModem.modulate(p, profile).forEach { air += it } } }
        repeat(AcousticModem.SAMPLE_RATE) { air += 0f }
        val rx = applyChannel(air.toFloatArray(), ch)

        val demod = AcousticDemodulator()
        val asm = AcousticFraming.Assembler()
        var i = 0
        while (i < rx.size) {
            val n = minOf(1_000, rx.size - i)
            for (p in demod.feed(rx.copyOfRange(i, i + n))) asm.accept(p)?.let { return it }
            i += n
        }
        return null
    }

    private fun applyChannel(x: FloatArray, ch: Channel): FloatArray {
        // Speaker/mic band: crude 2-tap high-pass then a 4-tap moving-average low-pass.
        val filtered = DoubleArray(x.size)
        for (n in x.indices) {
            var v = x[n].toDouble()
            for ((d, a) in ch.echoes) if (n >= d) v += a * x[n - d]
            filtered[n] = v
        }
        val hp = DoubleArray(x.size) { n -> filtered[n] - 0.6 * (if (n > 0) filtered[n - 1] else 0.0) }
        val lp = DoubleArray(x.size) { n -> (0 until 4).sumOf { k -> if (n >= k) hp[n - k] else 0.0 } / 4 }
        val ratio = 1.0 + ch.clockPpm / 1e6
        val outLen = (x.size / ratio).toInt() - 2
        val rnd = java.util.Random(42)
        var signalPower = 0.0
        val resampled = DoubleArray(outLen) { n ->
            val t = n * ratio
            val k = t.toInt()
            val f = t - k
            val v = (lp[k] * (1 - f) + lp[k + 1] * f) * ch.gain
            signalPower += v * v
            v
        }
        signalPower /= outLen
        val noise = Math.sqrt(signalPower / Math.pow(10.0, ch.snrDb / 10))
        return FloatArray(outLen) { (resampled[it] + rnd.nextGaussian() * noise).toFloat() }
    }
}
