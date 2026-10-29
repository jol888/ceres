package app.ceres.reader.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import app.ceres.reader.rule.CookieStore
import app.ceres.reader.rule.Http
import app.ceres.reader.rule.JsHost
import app.ceres.reader.rule.StrResponse
import app.ceres.reader.util.toJsonString
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AndroidJsHost(context: Context) : JsHost {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun toast(msg: String) {
        main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun webView(
        html: String?, url: String?, js: String?, headers: Map<String, String>, sourceRegex: String?,
    ): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "不能在主线程使用 WebView 取页" }
        if (html == null && url.isNullOrBlank()) return null
        WebCookies.push(url)
        val future = CompletableFuture<String?>()
        val script = js?.takeIf { it.isNotBlank() } ?: OUTER_HTML
        val regex = sourceRegex?.takeIf { it.isNotBlank() }?.toRegex()
        var view: WebView? = null
        main.post {
            runCatching {
                val wv = WebView(app).also { view = it }
                configure(wv, headers)
                wv.webViewClient = object : WebViewClient() {
                    private var started = false

                    override fun onLoadResource(v: WebView, resUrl: String) {
                        if (regex != null && regex.matches(resUrl)) future.complete(resUrl)
                    }

                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        if (regex != null || started) return
                        started = true
                        main.postDelayed({ poll(v, script, future, 0) }, 100)
                    }
                }
                val extra = headers.filterKeys { !it.equals("User-Agent", true) }
                if (html != null) wv.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                else wv.loadUrl(url!!, extra)
            }.onFailure { future.completeExceptionally(it) }
        }
        try {
            return future.get(60, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("WebView 加载超时: $url")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            future.cancel(false)
            main.post { view?.run { stopLoading(); destroy() } }
            WebCookies.pull(url)
        }
    }

    /** Page scripts may fill the DOM late, so retry until the script yields something. */
    private fun poll(view: WebView, script: String, future: CompletableFuture<String?>, attempt: Int) {
        if (future.isDone) return
        view.evaluateJavascript(script) { raw ->
            val value = decodeJsResult(raw)
            when {
                !value.isNullOrEmpty() -> future.complete(value)
                attempt >= 30 -> future.completeExceptionally(IOException("WebView 脚本没有返回结果"))
                else -> main.postDelayed({ poll(view, script, future, attempt + 1) }, 200L * minOf(attempt + 1, 5))
            }
        }
    }

    override fun browse(url: String, title: String?, headers: Map<String, String>): StrResponse {
        WebCookies.push(url)
        val res = UiRequests.await<StrResponse>(10 * 60_000L) { id ->
            app.startActivity(BrowserActivity.intent(app, url, title, headers, id))
        }
        WebCookies.pull(url)
        res?.url()?.takeIf { it != url }?.let { WebCookies.pull(it) }
        return res ?: StrResponse(url, null)
    }

    override fun verificationCode(imageUrl: String, image: ByteArray?): String =
        UiRequests.await<String>(5 * 60_000L) { id ->
            app.startActivity(CaptchaActivity.intent(app, imageUrl, image, id))
        }.orEmpty()

    override fun openUrl(url: String) {
        main.post {
            runCatching {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(app, "无法打开 $url", Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        const val OUTER_HTML = "document.documentElement.outerHTML"

        @SuppressLint("SetJavaScriptEnabled")
        fun configure(wv: WebView, headers: Map<String, String>) {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Cookies such as Cloudflare clearance are bound to the UA, so match OkHttp's.
                userAgentString = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                    ?: Http.DEFAULT_UA
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }

        /** `evaluateJavascript` hands back a JSON literal. */
        fun decodeJsResult(raw: String?): String? {
            if (raw.isNullOrEmpty() || raw == "null" || raw == "undefined") return null
            return runCatching {
                val el = JsonParser.parseString(raw)
                when {
                    el.isJsonNull -> null
                    el.isJsonPrimitive -> el.asString
                    else -> el.toString()
                }
            }.getOrDefault(raw)
        }

        fun headersJson(headers: Map<String, String>): String = toJsonString(headers)
    }
}

/** Copies cookies between Ceres' store and the WebView. Call off the main thread (the store is Room-backed). */
internal object WebCookies {
    fun push(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val cm = CookieManager.getInstance()
        CookieStore.cookieToMap(CookieStore.getCookie(url)).forEach { (k, v) -> cm.setCookie(url, "$k=$v") }
    }

    fun pull(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val cm = CookieManager.getInstance()
        cm.getCookie(url)?.takeIf { it.isNotBlank() }?.let { CookieStore.replaceCookie(url, it) }
        cm.flush()
    }
}
