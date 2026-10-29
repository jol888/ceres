package app.ceres.reader.rule

import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeRuleTest {
    private val html = """
        <html><body>
          <div id="list">
            <ul>
              <li class="item"><a href="/b/1">书一</a><span class="author">作者：甲</span></li>
              <li class="item"><a href="/b/2">书二</a><span class="author">乙</span></li>
              <li class="item"><a href="/b/3">书三</a><span class="author">丙</span></li>
            </ul>
          </div>
          <div id="content"><p>第一段</p><p>第二段&amp;</p><script>x()</script></div>
        </body></html>
    """.trimIndent()

    private val json = """{"code":0,"data":{"list":[{"id":11,"title":"甲书","tags":["a","b"]},{"id":12,"title":"乙书","tags":[]}]}}"""

    private fun rule(content: Any, base: String = "https://example.com/s") =
        AnalyzeRule(Book(), BookSource(bookSourceUrl = "https://example.com")).setContent(content, base)

    @Test
    fun defaultSyntax() {
        val ar = rule(html)
        assertEquals(3, ar.getElements("class.item").size)
        assertEquals("书一\n书二\n书三", ar.getString("class.item@tag.a@text"))
        assertEquals("书一", ar.getString("class.item.0@tag.a@text"))
        assertEquals("书三", ar.getString("class.item.-1@tag.a@text"))
        assertEquals("书二\n书三", ar.getString("class.item!0@tag.a@text"))
        assertEquals("书一\n书三", ar.getString("class.item[0,2]@tag.a@text"))
        assertEquals("书二\n书三", ar.getString("class.item[1:2]@tag.a@text"))
        assertEquals("https://example.com/b/2", ar.getString("class.item.1@tag.a@href", isUrl = true))
        assertEquals("书一", ar.getString("id.list@tag.li.0@a@text"))
    }

    @Test
    fun cssAndCombinators() {
        val ar = rule(html)
        assertEquals("书一\n书二\n书三", ar.getString("@CSS:li.item > a@text"))
        assertEquals("/b/1", ar.getString("@CSS:li.item > a@href", isUrl = false).lines().first())
        assertEquals("书一\n甲", ar.getString("class.item.0@tag.a@text&&class.item.0@class.author@text##作者：")
            .let { it })
        assertEquals("书一", ar.getString("class.nothing@text||class.item.0@tag.a@text"))
        assertEquals("书一\n作者：甲\n书二\n乙", ar.getStringList("class.item[0,1]@tag.a@text%%class.item[0,1]@class.author@text").joinToString("\n"))
    }

    @Test
    fun htmlExtractorRemovesScripts() {
        val ar = rule(html)
        val content = ar.getString("id.content@html")
        assert(!content.contains("x()")) { content }
        assert(content.contains("第二段&")) { content }
    }

    @Test
    fun itemContext() {
        val ar = rule(html)
        val items = ar.getElements("class.item")
        ar.setContent(items[1])
        assertEquals("书二", ar.getString("tag.a@text"))
        assertEquals("乙", ar.getString("class.author@text"))
    }

    @Test
    fun xpath() {
        val ar = rule(html)
        assertEquals("书一\n书二\n书三", ar.getString("//li/a/text()"))
        assertEquals("/b/3", ar.getString("@XPath://li[3]/a/@href"))
    }

    @Test
    fun jsonPath() {
        val ar = rule(json)
        val list = ar.getElements("$.data.list[*]")
        assertEquals(2, list.size)
        ar.setContent(list[0])
        assertEquals("甲书", ar.getString("title"))
        assertEquals("甲书", ar.getString("$.title"))
        assertEquals("11", ar.getString("$.id"))
        assertEquals("a\nb", ar.getString("$.tags[*]"))
        assertEquals("https://example.com/book/11", ar.getString("/book/{{$.id}}", isUrl = true))
        assertEquals("书:甲书", ar.getString("书:{$.title}"))
    }

    @Test
    fun replaceRegex() {
        val ar = rule(html)
        assertEquals("甲", ar.getString("class.author.0@text##作者："))
        assertEquals("X乙", ar.getString("class.author.1@text##^##X"))
        assertEquals("书", ar.getString("class.item.0@tag.a@text##书.##书###"))
    }

    @Test
    fun javascript() {
        val ar = rule(json)
        assertEquals("甲书!", ar.getString("$.data.list[0].title@js:result + '!'"))
        assertEquals("12", ar.getString("<js>JSON.parse(result).data.list[1].id</js>"))
        assertEquals("2", ar.getString("@js:var o = JSON.parse(result); o.data.list.length"))
        val list = ar.getElements("@js:JSON.parse(result).data.list")
        assertEquals(2, list.size)
        ar.setContent(list[1])
        assertEquals("乙书", ar.getString("$.title"))
        assertEquals("aGVsbG8=", ar.getString("@js:java.base64Encode('hello')"))
        assertEquals("hello", ar.getString("@js:java.base64Decode('aGVsbG8=')"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", ar.getString("@js:java.md5Encode('hello')"))
    }

    @Test
    fun putGet() {
        val book = Book()
        val ar = AnalyzeRule(book, BookSource(bookSourceUrl = "https://example.com")).setContent(json, "https://example.com")
        ar.getString("@put:{bid:\"$.data.list[0].id\"}$.data.code")
        assertEquals("11", book.getVariable("bid"))
        assertEquals("id=11", ar.getString("id=@get:{bid}"))
        assertEquals("11", ar.getString("@js:java.get('bid')"))
        ar.getString("@js:java.put('k', 3)")
        assertEquals("3", book.getVariable("k"))
    }

    @Test
    fun regexAllInOne() {
        val ar = rule(html)
        val items = ar.getElements(":<a href=\"(.*?)\">(.*?)</a>")
        assertEquals(3, items.size)
        ar.setContent(items[0])
        assertEquals("书一", ar.getString("$2"))
        assertEquals("https://example.com/b/1", ar.getString("$1", isUrl = true))
        assertEquals("书一-/b/1", ar.getString("$2-$1"))
    }

    @Test
    fun jsLibAndSourceVariables() {
        val source = BookSource(bookSourceUrl = "https://lib.test", jsLib = "function twice(x){return x+x}")
        val ar = AnalyzeRule(Book(), source).setContent("abc", "https://lib.test")
        assertEquals("abcabc", ar.getString("@js:twice(result)"))
        source.setVariable("v1")
        assertEquals("v1", ar.getString("@js:source.getVariable()"))
        assertEquals("https://lib.test", ar.getString("@js:source.bookSourceUrl"))
    }

    @Test
    fun symmetricCrypto() {
        val ar = rule("x")
        val enc = ar.getString("@js:java.createSymmetricCrypto('AES/CBC/PKCS5Padding','1234567890abcdef','abcdef1234567890').encryptBase64('秘密')")
        val dec = ar.getString("@js:java.createSymmetricCrypto('AES/CBC/PKCS5Padding','1234567890abcdef','abcdef1234567890').decryptStr('$enc')")
        assertEquals("秘密", dec)
    }
}
