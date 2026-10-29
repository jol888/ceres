package app.ceres.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.LOCAL_ORIGIN
import app.ceres.reader.util.htmlToParagraphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream

class LocalImporter(private val context: Context, private val books: BookRepository, private val db: AppDatabase) {

    data class Parsed(val title: String, val author: String, val chapters: List<Pair<String, List<String>>>)

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取文件")
        val parsed = if (name.endsWith(".epub", true)) parseEpub(bytes, name) else parseTxt(bytes, name)
        require(parsed.chapters.isNotEmpty()) { "没有解析到内容" }
        val book = Book(
            bookUrl = "local://${UUID.randomUUID()}",
            origin = LOCAL_ORIGIN,
            originName = "本地",
            name = parsed.title,
            author = parsed.author,
            inShelf = true,
            totalChapterNum = parsed.chapters.size,
            latestChapterTitle = parsed.chapters.last().first,
        )
        val chapters = books.writeLocalContent(book, parsed.chapters)
        db.bookDao().upsert(book)
        db.chapterDao().replace(book.bookUrl, chapters)
        book
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "未命名"
    }

    companion object {
        private val CHAPTER = Regex(
            "((第[0-9零〇一二两三四五六七八九十百千万]+[章节回卷集部篇])|序章|序言|楔子|引子|番外|尾声|后记|Chapter\\s*\\d+).{0,40}",
        )
        private const val MAX_HEADING = 60

        fun decodeText(bytes: ByteArray): String {
            val utf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                utf8.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
            return text.removePrefix("\uFEFF")
        }

        private fun blank(c: Char) = c.isWhitespace() || c == '\u3000' || c == '\u00A0'

        /** One pass over the lines: trimmed non-empty lines, split into sections at chapter headings. */
        fun parseTxt(bytes: ByteArray, fileName: String): Parsed {
            var text = decodeText(bytes)
            if (text.indexOf('\n') < 0) text = text.replace('\r', '\n')
            val title = fileName.substringBeforeLast('.').substringBefore("作者").trim('《', '》', ' ')
            val sections = ArrayList<Pair<String?, ArrayList<String>>>()
            var cur = ArrayList<String>()
            sections.add(null to cur)
            var headings = 0
            var i = 0
            val n = text.length
            while (i < n) {
                var j = text.indexOf('\n', i)
                if (j < 0) j = n
                var s = i
                var e = j
                while (s < e && blank(text[s])) s++
                while (e > s && blank(text[e - 1])) e--
                if (e > s) {
                    val line = text.substring(s, e)
                    if (e - s <= MAX_HEADING && CHAPTER.matches(line)) {
                        headings++
                        cur = ArrayList()
                        sections.add(line to cur)
                    } else {
                        cur.add(line)
                    }
                }
                i = j + 1
            }
            val chapters = ArrayList<Pair<String, List<String>>>()
            if (headings >= 2) {
                sections.forEach { (heading, body) ->
                    if (heading == null) { if (body.isNotEmpty()) chapters.add("前言" to body) }
                    else chapters.add(heading to body)
                }
            } else {
                var buf = ArrayList<String>()
                var size = 0
                for ((heading, body) in sections) {
                    for (p in listOfNotNull(heading) + body) {
                        buf.add(p); size += p.length
                        if (size > 6000) {
                            chapters.add("第 ${chapters.size + 1} 部分" to buf); buf = ArrayList(); size = 0
                        }
                    }
                }
                if (buf.isNotEmpty()) chapters.add("第 ${chapters.size + 1} 部分" to buf)
            }
            return Parsed(title.ifBlank { "未命名" }, "", chapters.filter { it.second.isNotEmpty() || chapters.size == 1 })
        }

        private val EPUB_TEXT = Regex("(?i)\\.(x?html?|opf|ncx|xml)$")

        fun parseEpub(bytes: ByteArray, fileName: String): Parsed {
            val files = HashMap<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && EPUB_TEXT.containsMatchIn(e.name)) files[e.name] = zip.readBytes()
                }
            }
            val container = files["META-INF/container.xml"]?.let { String(it) } ?: error("不是有效的 EPUB")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path")
                ?: error("EPUB 缺少 OPF")
            val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val opf = Jsoup.parse(String(files[opfPath] ?: error("EPUB 缺少 OPF")), "", Parser.xmlParser())
            val title = opf.selectFirst("metadata > dc|title, title")?.text()?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast('.')
            val author = opf.selectFirst("metadata > dc|creator, creator")?.text().orEmpty()
            val manifest = opf.select("manifest > item").associate { it.attr("id") to it }
            val tocTitles = HashMap<String, String>()
            // EPUB3 nav document
            manifest.values.firstOrNull { it.attr("properties").contains("nav") }?.let { nav ->
                val path = resolve(opfDir, nav.attr("href"))
                val navDir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                files[path]?.let { Jsoup.parse(String(it)) }?.select("nav a[href]")?.forEach { a ->
                    tocTitles.putIfAbsent(resolve(navDir, a.attr("href").substringBefore('#')), a.text().trim())
                }
            }
            // EPUB2 NCX
            val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
            manifest[ncxId]?.let { ncx ->
                val path = resolve(opfDir, ncx.attr("href"))
                val ncxDir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                files[path]?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }?.select("navPoint")?.forEach { np ->
                    val src = np.selectFirst("content")?.attr("src")?.substringBefore('#') ?: return@forEach
                    val label = np.selectFirst("navLabel text")?.text()?.trim().orEmpty()
                    if (label.isNotEmpty()) tocTitles.putIfAbsent(resolve(ncxDir, src), label)
                }
            }
            val chapters = ArrayList<Pair<String, List<String>>>()
            opf.select("spine > itemref").forEach { ref ->
                val item = manifest[ref.attr("idref")] ?: return@forEach
                val path = resolve(opfDir, item.attr("href"))
                val html = files[path]?.let { decodeText(it) } ?: return@forEach
                val doc = Jsoup.parse(html)
                val paras = htmlToParagraphs(doc.body()?.html().orEmpty())
                if (paras.isEmpty()) return@forEach
                val heading = doc.selectFirst("h1, h2, h3")?.text()?.trim()
                val chTitle = tocTitles[path] ?: heading ?: doc.title().takeIf { it.isNotBlank() }
                    ?: "第 ${chapters.size + 1} 节"
                val body = if (paras.first() == chTitle) paras.drop(1) else paras
                chapters.add(chTitle to body)
            }
            return Parsed(title, author, chapters)
        }

        private fun resolve(dir: String, href: String): String {
            val decoded = java.net.URLDecoder.decode(href, "UTF-8")
            val parts = ArrayList<String>()
            (dir + decoded).split('/').forEach { p ->
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/")
        }
    }
}
