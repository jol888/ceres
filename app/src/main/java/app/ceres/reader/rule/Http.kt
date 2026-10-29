package app.ceres.reader.rule

import okhttp3.OkHttpClient
import okhttp3.Response
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val metaCharset = Regex("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)")

    fun decode(bytes: ByteArray, response: Response?, forced: String?): String {
        val charset = forced?.takeIf { it.isNotBlank() }?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: response?.body?.contentType()?.charset()
            ?: sniffCharset(bytes)
            ?: Charsets.UTF_8
        var text = String(bytes, charset)
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        return text
    }

    private fun sniffCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = metaCharset.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }
}

/** Response object returned to rules / JS (`java.connect(url).body()`). */
class StrResponse(
    private val url: String,
    private val body: String?,
    private val code: Int = 200,
    private val headers: Map<String, String> = emptyMap(),
) {
    fun url(): String = url
    fun body(): String? = body
    fun code(): Int = code
    fun statusCode(): Int = code
    fun isSuccessful(): Boolean = code in 200..299
    fun headers(): Map<String, String> = headers
    fun header(name: String): String? = headers[name.lowercase()]
    fun cookies(): Map<String, String> = CookieStore.cookieToMap(headers["set-cookie"].orEmpty())
    override fun toString(): String = body.orEmpty()
}
