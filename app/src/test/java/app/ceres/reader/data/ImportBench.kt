package app.ceres.reader.data

import app.ceres.reader.util.md5
import app.ceres.reader.util.parseBookSources
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImportBench {
    private inline fun <T> t(label: String, block: () -> T): T {
        val s = System.nanoTime()
        val r = block()
        println("[bench] %-28s %6.0f ms".format(label, (System.nanoTime() - s) / 1e6))
        return r
    }

    @Test
    fun sources() {
        assumeTrue(System.getenv("CERES_BENCH") == "1")
        val one = File("src/main/assets/sample_sources.json").readText().trim().removePrefix("[").removeSuffix("]").trim()
        val json = (0 until 2000).joinToString(",", "[", "]") { one.replace("https://zh.wikisource.org\",", "https://s$it.example\",") }
        println("[bench] sources json ${json.length / 1024} KB")
        repeat(3) {
            val list = t("parseBookSources") { parseBookSources(json) }
            t("toJson + hasReview") { list.map { it.toJson() to it.hasReview() } }
        }
    }

    @Test
    fun txt() {
        assumeTrue(System.getenv("CERES_BENCH") == "1")
        val para = "　　这是一段用来测试导入速度的正文内容，长度大约有六十个字左右，模拟普通网络小说的段落。\n"
        val text = buildString {
            repeat(3000) { c ->
                append("第${c + 1}章 测试章节\n")
                repeat(55) { append(para) }
            }
        }
        val bytes = text.toByteArray()
        println("[bench] txt ${bytes.size / 1024 / 1024} MB")
        repeat(2) {
            val parsed = t("parseTxt") { LocalImporter.parseTxt(bytes, "测试.txt") }
            val dir = Files.createTempDirectory("ceres").toFile()
            t("write ${parsed.chapters.size} files") {
                parsed.chapters.forEachIndexed { i, (_, paras) ->
                    File(dir, md5("local:$i") + ".txt").writeText(paras.joinToString("\n"))
                }
            }
            t("write 1 file") {
                File(dir, "content.txt").outputStream().buffered(1 shl 16).use { out ->
                    parsed.chapters.forEach { (_, paras) -> out.write(paras.joinToString("\n").toByteArray()) }
                }
            }
            dir.deleteRecursively()
        }
    }
}
