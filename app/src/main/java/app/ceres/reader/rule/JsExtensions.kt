package app.ceres.reader.rule

import app.ceres.reader.data.model.BookSource
import app.ceres.reader.source.fanqie.FanqieUrls
import app.ceres.reader.util.md5
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Functions available to source JS as `java.*`. Names follow Legado's JsExtensions so that
 * existing sources work; browser-only features (webView, captcha) degrade gracefully.
 */
open class JsExtensions(open val source: BookSource?) {

    // ---- network ----

    fun ajax(url: Any?): String? {
        val u = when (url) {
            is List<*> -> url.firstOrNull()?.toString()
            is Array<*> -> url.firstOrNull()?.toString()
            else -> url?.let { JsEngine.toStr(it) }
        } ?: return null
        return runCatching { AnalyzeUrl(u, source = source).getStrResponse().body() }
            .getOrElse {
                RuleLog.log("ajax($u) 出错: ${it.message}")
                it.localizedMessage
            }
    }

    fun ajaxAll(urls: Array<String>): Array<StrResponse?> = urls.map { u ->
        runCatching { AnalyzeUrl(u, source = source).getStrResponse() }.getOrNull()
    }.toTypedArray()

    fun connect(url: String): StrResponse = AnalyzeUrl(url, source = source).getStrResponse()

    fun connect(url: String, header: String?): StrResponse {
        val headers = app.ceres.reader.util.parseStringMap(header)
        return AnalyzeUrl(url, source = source, headerOverride = headers).getStrResponse()
    }

    fun get(url: String, headers: Map<*, *>?): StrResponse =
        AnalyzeUrl(url, source = source, headerOverride = headers.toStringMap()).getStrResponse()

    fun head(url: String, headers: Map<*, *>?): StrResponse =
        AnalyzeUrl(url, source = source, headerOverride = headers.toStringMap(), forceMethod = "HEAD")
            .getStrResponse()

    fun post(url: String, body: String?, headers: Map<*, *>?): StrResponse =
        AnalyzeUrl(
            url, source = source, headerOverride = headers.toStringMap(),
            forceMethod = "POST", forceBody = body,
        ).getStrResponse()

    fun webView(html: String?, url: String?, js: String?): String? =
        webViewGetSource(html, url, js, null)

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String?): String? {
        val host = Host.current
        if (host == null) {
            RuleLog.log("无 WebView 环境，退化为普通请求")
            return if (!url.isNullOrBlank()) ajax(url) else html
        }
        val headers = url?.let { runCatching { AnalyzeUrl(it, source = source).headerMap }.getOrNull() }
        return host.webView(html, url, js, headers ?: emptyMap(), sourceRegex)
    }

    fun startBrowser(url: String, title: String?) {
        Host.current?.openUrl(url) ?: RuleLog.log("无浏览器环境: $url")
    }

    fun startBrowserAwait(url: String, title: String?): StrResponse {
        val host = Host.current ?: return connect(url).also { RuleLog.log("无浏览器环境，退化为普通请求") }
        return host.browse(url, title, AnalyzeUrl(url, source = source).headerMap)
    }

    fun startBrowserAwait(url: String, title: String?, refetchAfterSuccess: Boolean?): StrResponse =
        startBrowserAwait(url, title)

    fun getVerificationCode(imageUrl: String): String {
        val host = Host.current ?: return "".also { RuleLog.log("无界面，无法输入验证码: $imageUrl") }
        val image = runCatching { AnalyzeUrl(imageUrl, source = source).getByteArray() }
            .onFailure { RuleLog.log("验证码图片加载失败: ${it.message}") }.getOrNull()
        return host.verificationCode(imageUrl, image)
    }

    fun openUrl(url: String) = startBrowser(url, null)
    fun openUrl(url: String, mimeType: String?) = startBrowser(url, null)

    // ---- source / book ----

    fun getTag(): String? = source?.bookSourceName

    /** 番茄合成地址：搜索结果的 book_id → 详情地址。 */
    fun fanqieBookUrl(bookId: String?): String = FanqieUrls.book(bookId.orEmpty().trim())

    /** 番茄合成地址：详情/目录里的书籍地址 + itemId → 章节地址。 */
    fun fanqieChapterUrl(bookUrl: String?, itemId: String?): String {
        val id = FanqieUrls.bookIdOf(bookUrl)
            ?: throw IllegalArgumentException("无法从 $bookUrl 解析番茄书籍 id")
        return FanqieUrls.chapter(id, itemId.orEmpty().trim())
    }

    /** Legado refreshes the open book when a rule calls these; Ceres re-reads on every open. */
    fun refreshBookUrl() = Unit
    fun refreshTocUrl() = Unit
    fun refreshBookInfo() = Unit
    fun refreshContent() = Unit

    fun toNumChapter(s: String?): String? {
        s ?: return null
        val m = TITLE_NUM.find(s) ?: return s
        val n = chineseToInt(m.groupValues[2])
        return if (n < 0) s else s.replaceRange(m.range, "${m.groupValues[1]}$n${m.groupValues[3]}")
    }

    fun toURL(url: String): JsURL = JsURL(url, null)
    fun toURL(url: String, baseUrl: String?): JsURL = JsURL(url, baseUrl)

    // ---- files (confined to Host.filesDir) ----

    fun getFile(path: String): File {
        val root = Host.filesDir.canonicalFile
        val file = File(root, path.trimStart('/', File.separatorChar)).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) throw SecurityException("非法路径: $path")
        return file
    }

    fun readFile(path: String): ByteArray? = getFile(path).takeIf { it.isFile }?.readBytes()

    fun readTxtFile(path: String): String {
        val f = getFile(path)
        return if (f.isFile) Http.decode(f.readBytes(), null, null) else ""
    }

    fun readTxtFile(path: String, charsetName: String): String {
        val f = getFile(path)
        return if (f.isFile) String(f.readBytes(), Charset.forName(charsetName)) else ""
    }

    fun deleteFile(path: String): Boolean = getFile(path).deleteRecursively()

    /** Downloads [url] into the sandbox and returns its relative path. */
    fun downloadFile(url: String): String {
        val au = AnalyzeUrl(url, source = source)
        val ext = au.type ?: au.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .takeIf { it.length in 1..5 } ?: "bin"
        val rel = "${md5Encode16(url)}.$ext"
        val file = getFile(rel)
        file.parentFile?.mkdirs()
        file.writeBytes(au.getByteArray())
        return rel
    }

    fun downloadFile(content: String, url: String): String {
        val ext = AnalyzeUrl(url, source = source).type ?: return ""
        val rel = "${md5Encode16(url)}.$ext"
        getFile(rel).apply { parentFile?.mkdirs() }.writeBytes(content.hexToBytes())
        return rel
    }

    /** Extracts the zip at [zipPath] into a sibling folder and returns that folder's relative path. */
    fun unzipFile(zipPath: String): String {
        if (zipPath.isBlank()) return ""
        val zip = getFile(zipPath)
        val rel = zipPath.substringBeforeLast('.')
        val dir = getFile(rel).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(dir, e.name).canonicalFile
                if (!out.path.startsWith(dir.path + File.separator)) continue
                if (e.isDirectory) out.mkdirs() else out.apply { parentFile?.mkdirs() }.outputStream().use { zis.copyTo(it) }
            }
        }
        zip.delete()
        return rel
    }

    fun getTxtInFolder(path: String): String {
        val dir = getFile(path)
        val text = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?.joinToString("\n") { Http.decode(it.readBytes(), null, null) }.orEmpty()
        dir.deleteRecursively()
        return text
    }

    /** Downloads a text file once and serves it from the sandbox for [saveTime] seconds (0 = forever). */
    fun cacheFile(url: String): String = cacheFile(url, 0)

    fun cacheFile(url: String, saveTime: Int): String {
        val key = "cacheFile_${md5Encode16(url)}"
        CacheStore.get(key)?.let { path -> if (getFile(path).isFile) return readTxtFile(path) }
        val path = downloadFile(url)
        CacheStore.put(key, path, saveTime)
        return readTxtFile(path)
    }

    fun getZipStringContent(url: String, path: String): String =
        getZipByteArrayContent(url, path)?.let { Http.decode(it, null, null) }.orEmpty()

    fun getZipStringContent(url: String, path: String, charsetName: String): String =
        getZipByteArrayContent(url, path)?.let { String(it, Charset.forName(charsetName)) }.orEmpty()

    /** [url] is a link or a hex string of the zip; returns the bytes of entry [path]. */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.startsWith("http", true) || url.startsWith("data:", true)) {
            AnalyzeUrl(url, source = source).getByteArray()
        } else {
            url.hexToBytes()
        }
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: return null
                if (e.name == path) return zis.readBytes()
            }
        }
    }

    fun importScript(path: String): String {
        val key = "importScript_${md5(path)}"
        CacheStore.get(key)?.let { return it }
        val code = if (path.startsWith("http")) ajax(path).orEmpty() else ""
        if (code.isNotBlank()) CacheStore.put(key, code)
        return code
    }

    fun getCookie(tag: String): String = CookieStore.getCookie(tag)
    fun getCookie(tag: String, key: String?): String =
        if (key.isNullOrEmpty()) CookieStore.getCookie(tag) else CookieStore.getKey(tag, key)

    fun getWebViewUA(): String = Http.DEFAULT_UA

    // ---- encoding ----

    fun base64Decode(str: String?): String = String(base64DecodeToByteArray(str), Charsets.UTF_8)
    fun base64Decode(str: String?, flags: Int): String = String(base64DecodeToByteArray(str, flags), Charsets.UTF_8)
    fun base64Decode(str: String?, charset: String): String =
        String(base64DecodeToByteArray(str), Charset.forName(charset))

    fun base64DecodeToByteArray(str: String?): ByteArray = base64DecodeToByteArray(str, 0)
    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray {
        if (str.isNullOrBlank()) return ByteArray(0)
        val s = str.trim().replace('-', '+').replace('_', '/')
        return Base64.getMimeDecoder().decode(s)
    }

    fun base64Encode(str: String?): String = base64Encode(str, 2)
    fun base64Encode(str: String?, flags: Int): String {
        val bytes = str.orEmpty().toByteArray()
        return if (flags and 8 != 0) Base64.getUrlEncoder().encodeToString(bytes)
        else Base64.getEncoder().encodeToString(bytes)
    }

    fun base64EncodeToByteArray(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun hexDecodeToByteArray(hex: String): ByteArray = hex.hexToBytes()
    fun hexDecodeToString(hex: String): String = String(hex.hexToBytes(), Charsets.UTF_8)
    fun hexEncodeToString(utf8: String): String = utf8.toByteArray().toHex()

    fun md5Encode(str: String): String = md5(str)
    fun md5Encode16(str: String): String = md5(str).substring(8, 24)

    fun encodeURI(str: String): String = encodeURI(str, "UTF-8")
    fun encodeURI(str: String, enc: String): String =
        runCatching { URLEncoder.encode(str, enc) }.getOrDefault("")

    fun utf8ToGbk(str: String): String = String(str.toByteArray(Charset.forName("GBK")), Charset.forName("GBK"))

    fun strToBytes(str: String): ByteArray = str.toByteArray()
    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(Charset.forName(charset))
    fun bytesToStr(bytes: ByteArray): String = String(bytes)
    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, Charset.forName(charset))

    fun randomUUID(): String = UUID.randomUUID().toString()

    fun androidId(): String = Kv.get("device_id") ?: UUID.randomUUID().toString().replace("-", "")
        .substring(0, 16).also { Kv.put("device_id", it) }

    fun htmlFormat(str: String): String = app.ceres.reader.util.htmlFormat(str)

    fun t2s(text: String): String = text
    fun s2t(text: String): String = text

    fun timeFormat(time: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))

    fun timeFormatUTC(time: Long, format: String, sh: Int): String {
        val tz = TimeZone.getTimeZone("UTC").apply { rawOffset = sh * 3600 * 1000 }
        return SimpleDateFormat(format, Locale.getDefault()).apply { timeZone = tz }.format(Date(time))
    }

    // ---- crypto ----

    fun createSymmetricCrypto(transformation: String, key: Any?): SymmetricCrypto =
        SymmetricCrypto(transformation, key, null)

    fun createSymmetricCrypto(transformation: String, key: Any?, iv: Any?): SymmetricCrypto =
        SymmetricCrypto(transformation, key, iv)

    fun aesDecodeToString(str: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).decryptStr(str)

    fun aesBase64DecodeToString(str: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).decryptStr(Base64.getMimeDecoder().decode(str))

    fun aesEncodeToString(str: String, key: String, transformation: String, iv: String): String =
        String(SymmetricCrypto(transformation, key, iv).encrypt(str), Charsets.ISO_8859_1)

    fun aesEncodeToBase64String(str: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).encryptBase64(str)

    fun aesDecodeToByteArray(str: String, key: String, transformation: String, iv: String): ByteArray =
        SymmetricCrypto(transformation, key, iv).decrypt(str)

    fun aesBase64DecodeToByteArray(str: String, key: String, transformation: String, iv: String): ByteArray =
        SymmetricCrypto(transformation, key, iv).decrypt(Base64.getMimeDecoder().decode(str))

    fun aesEncodeToByteArray(str: String, key: String, transformation: String, iv: String): ByteArray =
        SymmetricCrypto(transformation, key, iv).encrypt(str)

    fun aesEncodeToBase64ByteArray(str: String, key: String, transformation: String, iv: String): ByteArray =
        SymmetricCrypto(transformation, key, iv).encryptBase64(str).toByteArray()

    /** Key and IV are Base64. */
    fun aesDecodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("AES/$mode/$padding", b64(key), b64(iv)).decryptStr(data)

    fun aesEncodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("AES/$mode/$padding", key, iv).encryptBase64(data)

    fun desDecodeToString(data: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun desBase64DecodeToString(data: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).decryptStr(data)

    fun desEncodeToString(data: String, key: String, transformation: String, iv: String): String =
        String(SymmetricCrypto(transformation, key, iv).encrypt(data))

    fun desEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String =
        SymmetricCrypto(transformation, key, iv).encryptBase64(data)

    fun tripleDESDecodeStr(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("DESede/$mode/$padding", key, iv).decryptStr(data)

    /** Key is Base64, IV is plain text. */
    fun tripleDESDecodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("DESede/$mode/$padding", b64(key), iv.toByteArray()).decryptStr(data)

    fun tripleDESEncodeBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("DESede/$mode/$padding", key, iv).encryptBase64(data)

    fun tripleDESEncodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String =
        SymmetricCrypto("DESede/$mode/$padding", b64(key), iv.toByteArray()).encryptBase64(data)

    private fun b64(s: String): ByteArray = Base64.getMimeDecoder().decode(s)

    fun digestHex(data: String, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(data.toByteArray()).toHex()

    fun digestBase64Str(data: String, algorithm: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(data.toByteArray()))

    fun HMacHex(data: String, algorithm: String, key: String): String = hmac(data, algorithm, key).toHex()

    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.getEncoder().encodeToString(hmac(data, algorithm, key))

    private fun hmac(data: String, algorithm: String, key: String): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(), algorithm))
        return mac.doFinal(data.toByteArray())
    }

    // ---- misc ----

    fun log(msg: Any?): Any? {
        RuleLog.log(JsEngine.toStr(msg))
        return msg
    }

    fun logType(any: Any?) = RuleLog.log(any?.javaClass?.name ?: "null")

    fun toast(msg: Any?) {
        val text = "${getTag().orEmpty()}: ${JsEngine.toStr(msg)}"
        RuleLog.log("toast: $text")
        Host.current?.toast(text)
    }

    fun longToast(msg: Any?) = toast(msg)

    private companion object {
        val TITLE_NUM = Regex("(第)(.+?)(章)")
    }
}

/** Same shape as Legado's JsURL: `java.toURL(u).searchParams.get("id")`. */
class JsURL(url: String, baseUrl: String? = null) {
    val searchParams: Map<String, String>?
    val host: String
    val origin: String
    val pathname: String

    init {
        val u = if (!baseUrl.isNullOrEmpty()) URL(URL(baseUrl), url) else URL(url)
        host = u.host
        origin = if (u.port > 0) "${u.protocol}://$host:${u.port}" else "${u.protocol}://$host"
        pathname = u.path
        searchParams = u.query?.let { q ->
            q.split('&').mapNotNull { p ->
                val kv = p.split('=', limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
        }
    }
}

private val CN_DIGITS = mapOf(
    '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '两' to 2, '贰' to 2, '三' to 3, '叁' to 3,
    '四' to 4, '肆' to 4, '五' to 5, '伍' to 5, '六' to 6, '陆' to 6, '七' to 7, '柒' to 7,
    '八' to 8, '捌' to 8, '九' to 9, '玖' to 9,
)
private val CN_UNITS = mapOf('十' to 10, '拾' to 10, '百' to 100, '佰' to 100, '千' to 1000, '仟' to 1000)

/** "一百二十三" / "１２３" / "一二三" → 123; -1 if [s] is not a number. */
internal fun chineseToInt(s: String): Int {
    val t = buildString {
        for (c in s) if (!c.isWhitespace()) append(if (c in '０'..'９') '0' + (c - '０') else c)
    }
    if (t.isEmpty()) return -1
    t.toIntOrNull()?.let { return it }
    if (t.all { it in CN_DIGITS }) return t.fold(0) { acc, c -> acc * 10 + CN_DIGITS.getValue(c) }
    var total = 0L
    var section = 0
    var digit = 0
    for (c in t) {
        when {
            c in CN_DIGITS -> digit = CN_DIGITS.getValue(c)
            c in CN_UNITS -> {
                section += (if (digit == 0) 1 else digit) * CN_UNITS.getValue(c)
                digit = 0
            }
            c == '万' || c == '亿' -> {
                total = (total + section + digit) * if (c == '万') 10_000 else 100_000_000
                section = 0; digit = 0
            }
            else -> return -1
        }
    }
    return (total + section + digit).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
}

internal fun Map<*, *>?.toStringMap(): Map<String, String>? =
    this?.entries?.associate { (k, v) -> k.toString() to JsEngine.toStr(v) }