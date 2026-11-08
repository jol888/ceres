package app.ceres.reader.transfer.acoustic

/**
 * Systematic Reed–Solomon over GF(256) (polynomial 0x11d, first root α^0).
 * A codeword is `data + parity`, at most 255 bytes, and corrects up to `paritySize / 2` byte errors.
 */
class ReedSolomon(val paritySize: Int) {
    private val generator: IntArray

    init {
        require(paritySize in 2..254 && paritySize % 2 == 0)
        var g = intArrayOf(1)
        for (i in 0 until paritySize) g = polyMulHighFirst(g, intArrayOf(1, EXP[i]))
        generator = g
    }

    fun encode(data: ByteArray): ByteArray {
        require(data.size + paritySize <= 255) { "codeword too long" }
        val rem = IntArray(paritySize)
        for (b in data) {
            val coef = (b.toInt() and 0xff) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, paritySize - 1)
            rem[paritySize - 1] = 0
            if (coef != 0) for (i in 0 until paritySize) rem[i] = rem[i] xor mul(generator[i + 1], coef)
        }
        return data + ByteArray(paritySize) { rem[it].toByte() }
    }

    /** Returns the corrected data part, or null when the codeword has too many errors. */
    fun decode(codeword: ByteArray): ByteArray? {
        val n = codeword.size
        require(n in paritySize + 1..255)
        val c = IntArray(n) { codeword[it].toInt() and 0xff }
        var synd = syndromes(c)
        if (synd.all { it == 0 }) return dataOf(c)

        // Berlekamp–Massey; lambda is stored low degree first.
        var lambda = intArrayOf(1)
        var prev = intArrayOf(1)
        var l = 0
        var m = 1
        var b = 1
        for (r in 0 until paritySize) {
            var d = synd[r]
            for (i in 1..l) if (i < lambda.size) d = d xor mul(lambda[i], synd[r - i])
            if (d == 0) {
                m++
                continue
            }
            val scale = div(d, b)
            val next = IntArray(maxOf(lambda.size, prev.size + m))
            lambda.copyInto(next)
            for (i in prev.indices) next[i + m] = next[i + m] xor mul(scale, prev[i])
            if (2 * l <= r) {
                prev = lambda
                l = r + 1 - l
                b = d
                m = 1
            } else {
                m++
            }
            lambda = next
        }
        val degree = lambda.indexOfLast { it != 0 }
        if (degree != l || l == 0 || 2 * l > paritySize) return null

        // Chien search: position j has degree e = n-1-j and locator X = α^e.
        val positions = ArrayList<Int>(l)
        for (j in 0 until n) {
            val e = n - 1 - j
            if (evalLow(lambda, EXP[(255 - e) % 255]) == 0) positions += j
        }
        if (positions.size != l) return null

        // Forney: Y = X·Ω(X⁻¹) / Λ'(X⁻¹) with Ω = S·Λ mod x^(2t).
        val omega = IntArray(paritySize)
        for (i in 0 until paritySize) {
            var v = 0
            for (k in 0..minOf(i, lambda.size - 1)) v = v xor mul(synd[i - k], lambda[k])
            omega[i] = v
        }
        for (j in positions) {
            val e = n - 1 - j
            val x = EXP[e % 255]
            val xInv = EXP[(255 - e) % 255]
            var deriv = 0
            var i = 1
            while (i < lambda.size) {
                deriv = deriv xor mul(lambda[i], pow(xInv, i - 1))
                i += 2
            }
            if (deriv == 0) return null
            c[j] = c[j] xor mul(x, div(evalLow(omega, xInv), deriv))
        }
        synd = syndromes(c)
        return if (synd.all { it == 0 }) dataOf(c) else null
    }

    private fun dataOf(c: IntArray) = ByteArray(c.size - paritySize) { c[it].toByte() }

    private fun syndromes(c: IntArray) = IntArray(paritySize) { i ->
        val a = EXP[i]
        var v = 0
        for (x in c) v = mul(v, a) xor x
        v
    }

    companion object {
        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11d
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
        private fun div(a: Int, b: Int) = if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]
        private fun pow(a: Int, n: Int) = if (n == 0) 1 else if (a == 0) 0 else EXP[(LOG[a] * n) % 255]

        private fun evalLow(p: IntArray, x: Int): Int {
            var v = 0
            for (i in p.indices.reversed()) v = mul(v, x) xor p[i]
            return v
        }

        private fun polyMulHighFirst(p: IntArray, q: IntArray): IntArray {
            val r = IntArray(p.size + q.size - 1)
            for (i in p.indices) for (j in q.indices) r[i + j] = r[i + j] xor mul(p[i], q[j])
            return r
        }
    }
}
