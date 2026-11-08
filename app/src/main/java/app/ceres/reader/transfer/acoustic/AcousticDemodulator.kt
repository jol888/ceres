package app.ceres.reader.transfer.acoustic

import app.ceres.reader.transfer.acoustic.AcousticModem.CARRIERS
import app.ceres.reader.transfer.acoustic.AcousticModem.CHIRP
import app.ceres.reader.transfer.acoustic.AcousticModem.CYCLIC_PREFIX
import app.ceres.reader.transfer.acoustic.AcousticModem.FFT_SIZE
import app.ceres.reader.transfer.acoustic.AcousticModem.FIRST_BIN
import app.ceres.reader.transfer.acoustic.AcousticModem.GAP
import app.ceres.reader.transfer.acoustic.AcousticModem.Profile
import app.ceres.reader.transfer.acoustic.AcousticModem.SYMBOL
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming receiver: feed microphone samples, get back the data of every packet that decodes cleanly.
 * Not thread-safe; drive it from a single coroutine.
 */
class AcousticDemodulator(private val threshold: Double = 0.3) {
    /** Packets whose chirp was heard. */
    var heard = 0
        private set

    /** Heard packets that failed the header or Reed–Solomon check. */
    var corrupted = 0
        private set

    private var buf = FloatArray(AcousticModem.SAMPLE_RATE * 2)
    private var len = 0
    private var base = 0L
    private var scan = 0L
    private var candidate = -1L

    private val corrFft = Fft(CORR_SIZE)
    private val chirpRe = DoubleArray(CORR_SIZE)
    private val chirpIm = DoubleArray(CORR_SIZE)
    private val chirpEnergy: Double
    private val symFft = Fft(FFT_SIZE)

    init {
        var e = 0.0
        for (i in 0 until CHIRP) {
            chirpRe[i] = AcousticModem.chirp[i].toDouble()
            e += chirpRe[i] * chirpRe[i]
        }
        chirpEnergy = e
        corrFft.forward(chirpRe, chirpIm)
    }

    fun feed(samples: FloatArray, count: Int = samples.size): List<ByteArray> {
        append(samples, count)
        val out = ArrayList<ByteArray>()
        while (true) {
            if (candidate >= 0) {
                if (end() < candidate + REFINE + AcousticModem.maxPacketSamples) break
                val start = refine(candidate)
                heard++
                val decoded = decodeAt(start)
                if (decoded == null) {
                    corrupted++
                    scan = start + CHIRP
                } else {
                    out += decoded.first
                    // Resume inside the tail silence: the sender's clock may run slightly fast.
                    scan = start + decoded.second - AcousticModem.TAIL / 2
                }
                candidate = -1
                continue
            }
            if (end() < scan + CORR_SIZE) break
            candidate = search(scan)
            if (candidate < 0) scan += CORR_STEP
        }
        trim()
        return out
    }

    private fun end() = base + len

    private fun at(abs: Long): Float = buf[(abs - base).toInt()]

    private fun append(samples: FloatArray, count: Int) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(samples, 0, buf, len, count)
        len += count
    }

    private fun trim() {
        val keepFrom = (if (candidate >= 0) candidate else scan) - CHIRP
        val drop = (keepFrom - base).toInt()
        if (drop > AcousticModem.SAMPLE_RATE) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            base += drop
        }
    }

    /** Overlap-save matched filter for one block; returns the first position above threshold, or -1. */
    private fun search(from: Long): Long {
        val re = DoubleArray(CORR_SIZE)
        val im = DoubleArray(CORR_SIZE)
        for (i in 0 until CORR_SIZE) re[i] = at(from + i).toDouble()
        val energy = DoubleArray(CORR_SIZE + 1)
        for (i in 0 until CORR_SIZE) energy[i + 1] = energy[i] + re[i] * re[i]
        corrFft.forward(re, im)
        for (k in 0 until CORR_SIZE) {
            val r = re[k] * chirpRe[k] + im[k] * chirpIm[k]
            val i = im[k] * chirpRe[k] - re[k] * chirpIm[k]
            re[k] = r; im[k] = i
        }
        corrFft.inverse(re, im)
        for (i in 0 until CORR_STEP) {
            val e = energy[i + CHIRP] - energy[i]
            if (e < 1e-9) continue
            val ncc = re[i] / CORR_SIZE / sqrt(e * chirpEnergy)
            if (ncc > threshold) return from + i
        }
        return -1
    }

    /** The coarse hit is the first sample over threshold; the true start is the correlation peak just after. */
    private fun refine(coarse: Long): Long {
        var best = coarse
        var bestScore = -1.0
        for (p in coarse until coarse + REFINE) {
            var dot = 0.0
            var e = 0.0
            for (j in 0 until CHIRP) {
                val v = at(p + j).toDouble()
                dot += v * AcousticModem.chirp[j]
                e += v * v
            }
            if (e < 1e-9) continue
            val score = dot / sqrt(e * chirpEnergy)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    /** Returns the packet data and its length in samples, or null if it doesn't decode. */
    private fun decodeAt(start: Long): Pair<ByteArray, Int>? {
        val first = start + CHIRP + GAP
        val reference = carriersAt(first)
        val headerSym = carriersAt(first + SYMBOL)
        val header = ByteArray(8)
        for (c in 0 until CARRIERS) {
            val (dr, _) = diff(headerSym, reference, c)
            if (dr < 0) header[c / 8] = (header[c / 8].toInt() or (0x80 ushr (c % 8))).toByte()
        }
        val (profile, codewordLen) = AcousticModem.parseHeader(header) ?: return null
        val symbols = AcousticModem.payloadSymbols(codewordLen, profile)
        val bitsPerCarrier = symbols * profile.bitsPerCarrier
        val streams = Array(CARRIERS) { IntArray(bitsPerCarrier) }
        var prev = headerSym
        for (s in 0 until symbols) {
            val cur = carriersAt(first + SYMBOL * (2 + s))
            for (c in 0 until CARRIERS) {
                val (dr, di) = diff(cur, prev, c)
                when (profile) {
                    Profile.ROBUST -> streams[c][s] = if (dr < 0) 1 else 0
                    Profile.FAST -> {
                        val quadrant = if (abs(dr) >= abs(di)) (if (dr >= 0) 0 else 2) else (if (di >= 0) 1 else 3)
                        val gray = AcousticModem.QUADRANT_TO_GRAY[quadrant]
                        streams[c][2 * s] = gray ushr 1
                        streams[c][2 * s + 1] = gray and 1
                    }
                }
            }
            prev = cur
        }
        val codeword = ByteArray(codewordLen) { i ->
            val c = i % CARRIERS
            val slot = i / CARRIERS
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or streams[c][slot * 8 + b]
            v.toByte()
        }
        val data = AcousticModem.payloadRs.decode(AcousticModem.scramble(codeword, AcousticModem.PAYLOAD_SEED))
            ?: return null
        return data to AcousticModem.packetSamples(codewordLen, profile)
    }

    /** FFT of one symbol, window backed off into the cyclic prefix; returns interleaved re/im per carrier. */
    private fun carriersAt(symbolStart: Long): DoubleArray {
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val from = symbolStart + CYCLIC_PREFIX - BACKOFF
        for (t in 0 until FFT_SIZE) re[t] = at(from + t).toDouble()
        symFft.forward(re, im)
        val out = DoubleArray(CARRIERS * 2)
        for (c in 0 until CARRIERS) {
            out[2 * c] = re[FIRST_BIN + c]
            out[2 * c + 1] = im[FIRST_BIN + c]
        }
        return out
    }

    /** cur · conj(prev) for carrier [c]. */
    private fun diff(cur: DoubleArray, prev: DoubleArray, c: Int): Pair<Double, Double> {
        val ar = cur[2 * c]; val ai = cur[2 * c + 1]
        val br = prev[2 * c]; val bi = prev[2 * c + 1]
        return (ar * br + ai * bi) to (ai * br - ar * bi)
    }

    private companion object {
        const val CORR_SIZE = 4096
        const val CORR_STEP = CORR_SIZE - CHIRP
        const val REFINE = 512
        const val BACKOFF = CYCLIC_PREFIX / 4
    }
}
