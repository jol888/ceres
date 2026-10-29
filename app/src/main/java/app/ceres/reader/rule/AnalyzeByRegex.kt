package app.ceres.reader.rule

/** Legado "AllInOne" list rules: `:regex1&&regex2`, items are lists of capture groups. */
object AnalyzeByRegex {
    fun getElements(content: String, regs: List<String>, index: Int = 0): List<List<String>> {
        if (regs.isEmpty()) return emptyList()
        val matches = Regex(regs[index]).findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        if (index + 1 == regs.size) {
            return matches.map { m -> m.groups.map { it?.value.orEmpty() } }
        }
        val joined = matches.joinToString("") { it.value }
        return getElements(joined, regs, index + 1)
    }
}
