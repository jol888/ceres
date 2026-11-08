package app.ceres.reader.transfer.acoustic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Differential OFDM over audio. One packet on air:
 *
 * `chirp | gap | reference symbol | header symbol (DBPSK) | payload symbols | tail silence`
 *
 * Every symbol is the 512-point IFFT of 64 unit carriers plus a 128-sample cyclic prefix. Data is carried in
 * the phase change of each carrier between consecutive symbols, so the speaker/mic response cancels out and
 * no channel estimation is needed. Each codeword byte sits on a single carrier, which keeps a faded carrier
 * down to a couple of byte errors that Reed–Solomon repairs.
 */
object AcousticModem {
    const val SAMPLE_RATE = 44_100
    const val FFT_SIZE = 512
    const val CYCLIC_PREFIX = 128
    const val SYMBOL = FFT_SIZE + CYCLIC_PREFIX
    const val CARRIERS = 64
    const val FIRST_BIN = 20
    const val CHIRP = 1024
    const val GAP = 256
    const val TAIL = 882
    const val MAX_CODEWORD = 255

    const val HEADER_PARITY = 4
    const val PAYLOAD_PARITY = 24
    private const val HEADER_MAGIC = 0xCE
    private const val HEADER_CHECK = 0x5A

    /** Bits carried per carrier per symbol. */
    enum class Profile(val id: Int, val bitsPerCarrier: Int) {
        FAST(0, 2),
        ROBUST(1, 1);

        companion object {
            fun of(id: Int) = entries.firstOrNull { it.id == id }
        }
    }

    internal val headerRs = ReedSolomon(HEADER_PARITY)
    internal val payloadRs = ReedSolomon(PAYLOAD_PARITY)
    internal val chirp: FloatArray = buildChirp()
    private val fft = Fft(FFT_SIZE)
    private val referencePhases = IntArray(CARRIERS).also { q ->
        var s = 0x2545F491
        for (i in q.indices) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            q[i] = s and 3
        }
    }

    /** Largest raw data size that fits one packet. */
    const val MAX_DATA = MAX_CODEWORD - PAYLOAD_PARITY

    fun payloadSymbols(codewordLen: Int, profile: Profile): Int {
        val bytesPerCarrier = (codewordLen + CARRIERS - 1) / CARRIERS
        return bytesPerCarrier * 8 / profile.bitsPerCarrier
    }

    fun packetSamples(codewordLen: Int, profile: Profile): Int =
        CHIRP + GAP + SYMBOL * (2 + payloadSymbols(codewordLen, profile)) + TAIL

    internal val maxPacketSamples = CHIRP + GAP + SYMBOL * (2 + payloadSymbols(MAX_CODEWORD, Profile.ROBUST))

    /** Renders [data] (≤ [MAX_DATA] bytes) as one packet of float samples in [-1, 1]. */
    fun modulate(data: ByteArray, profile: Profile, gain: Float = 0.9f): FloatArray {
        require(data.isNotEmpty() && data.size <= MAX_DATA)
        val codeword = scramble(payloadRs.encode(data), PAYLOAD_SEED)
        val header = scramble(
            headerRs.encode(byteArrayOf(HEADER_MAGIC.toByte(), profile.id.toByte(), codeword.size.toByte(), HEADER_CHECK.toByte())),
            HEADER_SEED,
        )
        val symbolCount = payloadSymbols(codeword.size, profile)
        val out = FloatArray(packetSamples(codeword.size, profile))
        chirp.copyInto(out)

        val phase = referencePhases.copyOf()
        val ofdm = FloatArray(SYMBOL * (2 + symbolCount))
        fun emit(at: Int) = renderSymbol(phase, ofdm, at)
        emit(0)
        for (c in 0 until CARRIERS) phase[c] = (phase[c] + 2 * bit(header, c / 8, c % 8)) and 3
        emit(SYMBOL)
        val stream = carrierStreams(codeword, symbolCount * profile.bitsPerCarrier)
        for (s in 0 until symbolCount) {
            for (c in 0 until CARRIERS) {
                phase[c] = when (profile) {
                    Profile.ROBUST -> (phase[c] + 2 * stream[c][s]) and 3
                    Profile.FAST -> (phase[c] + GRAY_TO_QUADRANT[stream[c][2 * s] shl 1 or stream[c][2 * s + 1]]) and 3
                }
            }
            emit(SYMBOL * (2 + s))
        }
        var peak = 0f
        for (v in ofdm) peak = max(peak, abs(v))
        val scale = if (peak > 0f) gain / peak else 0f
        ofdm.forEachIndexed { i, v -> out[CHIRP + GAP + i] = v * scale }
        for (i in 0 until CHIRP) out[i] *= gain
        return out
    }

    /** Spreads codeword bytes round-robin over carriers; each carrier stream holds whole bytes MSB first. */
    private fun carrierStreams(codeword: ByteArray, bitsPerCarrier: Int): Array<IntArray> {
        val streams = Array(CARRIERS) { IntArray(bitsPerCarrier) }
        var filler = 0x1F2E3D4C
        for (c in 0 until CARRIERS) for (b in 0 until bitsPerCarrier) {
            filler = filler xor (filler shl 13); filler = filler xor (filler ushr 17); filler = filler xor (filler shl 5)
            streams[c][b] = filler and 1
        }
        for (i in codeword.indices) {
            val c = i % CARRIERS
            val slot = i / CARRIERS
            for (b in 0 until 8) streams[c][slot * 8 + b] = bit(codeword, i, b)
        }
        return streams
    }

    private fun renderSymbol(quadrants: IntArray, out: FloatArray, at: Int) {
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (c in 0 until CARRIERS) {
            val k = FIRST_BIN + c
            val (r, i) = QUADRANT[quadrants[c]]
            re[k] = r; im[k] = i
            re[FFT_SIZE - k] = r; im[FFT_SIZE - k] = -i
        }
        fft.inverse(re, im)
        for (t in 0 until CYCLIC_PREFIX) out[at + t] = re[FFT_SIZE - CYCLIC_PREFIX + t].toFloat()
        for (t in 0 until FFT_SIZE) out[at + CYCLIC_PREFIX + t] = re[t].toFloat()
    }

    private fun buildChirp(): FloatArray {
        val f0 = 1_700.0
        val f1 = 7_200.0
        val duration = CHIRP.toDouble() / SAMPLE_RATE
        val taper = CHIRP / 10
        return FloatArray(CHIRP) { n ->
            val t = n.toDouble() / SAMPLE_RATE
            val w = when {
                n < taper -> 0.5 - 0.5 * cos(PI * n / taper)
                n >= CHIRP - taper -> 0.5 - 0.5 * cos(PI * (CHIRP - 1 - n) / taper)
                else -> 1.0
            }
            (w * sin(2 * PI * (f0 * t + (f1 - f0) * t * t / (2 * duration)))).toFloat()
        }
    }

    internal fun bit(bytes: ByteArray, index: Int, bit: Int) = (bytes[index].toInt() ushr (7 - bit)) and 1

    internal fun scramble(bytes: ByteArray, seed: Int): ByteArray {
        var s = seed
        return ByteArray(bytes.size) { i ->
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            (bytes[i].toInt() xor (s ushr 8)).toByte()
        }
    }

    internal fun parseHeader(bytes: ByteArray): Pair<Profile, Int>? {
        val h = headerRs.decode(scramble(bytes, HEADER_SEED)) ?: return null
        if (h[0].toInt() and 0xff != HEADER_MAGIC || h[3].toInt() and 0xff != HEADER_CHECK) return null
        val profile = Profile.of(h[1].toInt()) ?: return null
        val len = h[2].toInt() and 0xff
        if (len <= PAYLOAD_PARITY) return null
        return profile to len
    }

    internal const val HEADER_SEED = 0x6C8E9CF5
    internal const val PAYLOAD_SEED = 0x1B873593
    private val QUADRANT = arrayOf(1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0, 0.0 to -1.0)
    private val GRAY_TO_QUADRANT = intArrayOf(0, 1, 3, 2)
    internal val QUADRANT_TO_GRAY = intArrayOf(0, 1, 3, 2)
}
