package app.ceres.reader.rule

import app.ceres.reader.data.model.BookSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JsExtensionsTest {
    private val source = BookSource(bookSourceUrl = "https://example.com", bookSourceName = "测试")
    private val savedDir = Host.filesDir

    private fun js(code: String): String = JsEngine.toStr(AnalyzeRule(null, source).evalJS(code))

    @Before
    fun setUp() {
        Kv.store = MemoryKv()
        Host.filesDir = Files.createTempDirectory("ceres-js").toFile()
    }

    @After
    fun tearDown() {
        Host.filesDir.deleteRecursively()
        Host.filesDir = savedDir
    }

    @Test
    fun toNumChapter() {
        assertEquals("第105章 归来", js("java.toNumChapter('第一百零五章 归来')"))
        assertEquals("第12000章", js("java.toNumChapter('第一万二千章')"))
        assertEquals("第10章", js("java.toNumChapter('第十章')"))
        assertEquals("第123章", js("java.toNumChapter('第一二三章')"))
        assertEquals("第X章", js("java.toNumChapter('第X章')"))
        assertEquals("序章", js("java.toNumChapter('序章')"))
    }

    @Test
    fun toUrl() {
        val code = "var u = java.toURL('/p/q?id=3&n=%E4%B8%AD', 'https://a.com:8080/x'); " +
            "[u.host, u.origin, u.pathname, u.searchParams.get('n')].join('|')"
        assertEquals("a.com|https://a.com:8080|/p/q|中", js(code))
    }

    @Test
    fun desAndTripleDesRoundTrip() {
        val enc = js("java.desEncodeToBase64String('你好', '12345678', 'DES/ECB/PKCS5Padding', '')")
        assertEquals("你好", js("java.desDecodeToString('$enc', '12345678', 'DES/ECB/PKCS5Padding', '')"))
        val key = "123456789012345678901234"
        val enc3 = js("java.tripleDESEncodeBase64Str('abc', '$key', 'CBC', 'PKCS5Padding', '12345678')")
        assertEquals("abc", js("java.tripleDESDecodeStr('$enc3', '$key', 'CBC', 'PKCS5Padding', '12345678')"))
    }

    @Test
    fun zipContentFromHex() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("dir/a.txt")); z.write("章节内容".toByteArray()); z.closeEntry()
        }
        val hex = bos.toByteArray().toHex()
        assertEquals("章节内容", js("java.getZipStringContent('$hex', 'dir/a.txt')"))
        assertEquals("", js("java.getZipStringContent('$hex', 'missing.txt')"))
    }

    @Test
    fun filesStayInSandbox() {
        val path = js("java.downloadFile('${"内容".toByteArray().toHex()}', 'https://e.com/a,{\"type\":\"txt\"}')")
        assertEquals("内容", js("java.readTxtFile('$path')"))
        assertEquals("true", js("java.deleteFile('$path')"))
        assertEquals("", js("java.readTxtFile('$path')"))
        val escaped = runCatching { js("java.readTxtFile('../../etc/passwd')") }
        assertEquals(true, escaped.exceptionOrNull()?.message?.contains("非法路径"))
    }

    @Test
    fun loginJsStoresHeaderUsedByRequests() {
        source.loginUrl = "function login() { var u = source.getLoginInfoMap().get('u'); " +
            "source.putLoginHeader(JSON.stringify({token: u + '!'})); }"
        source.putLoginInfo("""{"u":"me"}""")
        source.login()
        assertEquals("me!", source.getLoginHeaderMap()["token"])
        assertEquals("me!", AnalyzeUrl("/x", source = source).headerMap["token"])
    }

    @Test
    fun loginUrlForms() {
        source.loginUrl = "<js>function login(){}</js>"
        assertEquals("function login(){}", source.getLoginJs())
        assertNull(source.getLoginWebUrl())
        source.loginUrl = "{\n \"url\": \"/login\"\n}"
        assertNull(source.getLoginJs())
        assertEquals("https://example.com/login", source.getLoginWebUrl())
        source.loginUrl = "https://example.com/sign"
        assertEquals("https://example.com/sign", source.getLoginWebUrl())
    }
}
