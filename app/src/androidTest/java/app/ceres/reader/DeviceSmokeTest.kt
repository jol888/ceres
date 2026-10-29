package app.ceres.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.rule.AnalyzeRule
import app.ceres.reader.rule.Host
import app.ceres.reader.rule.JsEngine
import app.ceres.reader.source.WebBook
import app.ceres.reader.util.parseBookSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Things the JVM tests can't cover: Rhino on ART, the real WebView, and the network. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val source = BookSource(bookSourceUrl = "https://example.com", bookSourceName = "device")

    private fun js(code: String) = JsEngine.toStr(AnalyzeRule(null, source).evalJS(code))

    @Test
    fun rhinoOnArt() {
        assertEquals("6|a-b|3", js("let f = (a, b) => a * b; const s = `${'a'}-${'b'}`; [f(2, 3), s, [1, 2, 3].length].join('|')"))
        assertEquals("e10adc3949ba59abbe56e057f20f883e", js("java.md5Encode('123456')"))
        assertEquals("第12章", js("java.toNumChapter('第十二章')"))
        assertEquals("1", js("var m = new Packages.java.util.HashMap(); m.put('k', 1); String(m.get('k'))"))
        val enc = js("java.desEncodeToBase64String('你好', '12345678', 'DES/ECB/PKCS5Padding', '')")
        assertEquals("你好", js("java.desDecodeToString('$enc', '12345678', 'DES/ECB/PKCS5Padding', '')"))
    }

    @Test
    fun offscreenWebViewRunsPageScripts() {
        val host = Host.current!!
        val html = "<div id='a'></div><script>setTimeout(function(){document.getElementById('a').textContent='ok'}, 300)</script>"
        val text = host.webView(html, "https://example.com/", "document.getElementById('a').textContent", emptyMap(), null)
        assertEquals("ok", text)
        val page = host.webView(null, "https://example.com/", null, emptyMap(), null).orEmpty()
        assertTrue(page.take(200), page.contains("Example Domain"))
    }

    @Test
    fun sampleSourceLive() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = parseBookSources(ctx.assets.open("sample_sources.json").bufferedReader().readText()).first()
        val t0 = System.currentTimeMillis()
        val hit = WebBook.search(src, "西游记").first { it.name == "西遊記" }
        val book = hit.toBook()
        val body = WebBook.getBookInfo(src, book)
        val chapters = WebBook.getChapterList(src, book, body)
        val paragraphs = WebBook.getContent(src, book, chapters[0], chapters[1].url)
        android.util.Log.i("CeresSmoke", "live: ${chapters.size} chapters, ${paragraphs.size} paragraphs, ${System.currentTimeMillis() - t0} ms")
        assertTrue(chapters.size >= 100)
        assertTrue(paragraphs.joinToString("").contains("花果山"))
    }
}
