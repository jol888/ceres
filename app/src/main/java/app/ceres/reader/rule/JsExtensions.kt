package app.ceres.reader.rule

import app.ceres.reader.data.model.BookSource
import app.ceres.reader.util.md5
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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

    fun webView(html: String?, url: String?, js: String?): String? {
        RuleLog.log("不支持 webView，退化为普通请求")
        return if (!url.isNullOrBlank()) ajax(url) else html
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String?): String? =
        webView(html, url, js)

    fun startBrowser(url: String, title: String?) {
        RuleLog.log("不支持 startBrowser: $url")
    }

    fun startBrowserAwait(url: String, title: String?): StrResponse {
        RuleLog.log("不支持 startBrowserAwait，退化为普通请求")
        return connect(url)
    }

    fun getVerificationCode(imageUrl: String): String {
        RuleLog.log("不支持验证码: $imageUrl")
        return ""
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

    fun toast(msg: Any?) = RuleLog.log("toast: ${JsEngine.toStr(msg)}")
    fun longToast(msg: Any?) = toast(msg)
}

internal fun Map<*, *>?.toStringMap(): Map<String, String>? =
    this?.entries?.associate { (k, v) -> k.toString() to JsEngine.toStr(v) }