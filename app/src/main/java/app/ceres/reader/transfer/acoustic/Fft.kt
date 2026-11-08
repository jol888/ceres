package app.ceres.reader.transfer.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val size: Int) {
    private val bits = Integer.numberOfTrailingZeros(size)
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)

    /** Unscaled inverse; divide by [size] for a true inverse. */
    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, 1.0)

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr
                    im[l] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}
