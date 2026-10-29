package app.ceres.reader.rule

/**
 * Splits a rule on the first top-level combinator found (`&&`, `||` or `%%`), ignoring
 * occurrences nested in brackets or quotes (e.g. JSONPath filters, XPath predicates).
 */
object RuleSplitter {
    private val OPS = listOf("&&", "||", "%%")

    data class Split(val parts: List<String>, val op: String?)

    fun split(rule: String, ops: List<String> = OPS): Split {
        var op: String? = null
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            if (quote != null) {
                if (c == '\\') {
                    i += 2; continue
                }
                if (c == quote) quote = null
            } else {
                when (c) {
                    '\'', '"' -> quote = c
                    '[', '(', '{' -> depth++
                    ']', ')', '}' -> if (depth > 0) depth--
                    else -> if (depth == 0 && i + 1 < rule.length) {
                        val two = rule.substring(i, i + 2)
                        if ((op == null && two in ops) || two == op) {
                            op = two
                            parts.add(rule.substring(start, i))
                            i += 2
                            start = i
                            continue
                        }
                    }
                }
            }
            i++
        }
        parts.add(rule.substring(start))
        return Split(parts.map { it.trim() }.filter { it.isNotEmpty() }, op)
    }

    /** Combines per-part results according to the combinator semantics. */
    fun <T> combine(op: String?, results: List<List<T>>): List<T> = when (op) {
        "||" -> results.firstOrNull { it.isNotEmpty() } ?: emptyList()
        "%%" -> buildList {
            val max = results.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (r in results) if (i < r.size) add(r[i])
        }
        else -> results.flatten()
    }

    /** Evaluates `||` lazily so later alternatives are skipped once one yields results. */
    inline fun <T> evaluate(rule: String, single: (String) -> List<T>): List<T> {
        val split = split(rule)
        if (split.parts.size <= 1) return single(split.parts.firstOrNull() ?: rule)
        if (split.op == "||") {
            for (p in split.parts) {
                val r = single(p)
                if (r.isNotEmpty()) return r
            }
            return emptyList()
        }
        return combine(split.op, split.parts.map(single))
    }
}
