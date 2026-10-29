package app.ceres.reader.web

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.ceres.reader.AppGraph
import app.ceres.reader.rule.StrResponse
import app.ceres.reader.ui.Action
import app.ceres.reader.ui.CeresTheme
import app.ceres.reader.ui.Ink
import app.ceres.reader.ui.Screen
import app.ceres.reader.util.parseStringMap
import kotlinx.coroutines.launch

/**
 * In-app browser for `java.startBrowser(Await)` and web login. Reports the final URL and page HTML
 * to the waiting rule when the user taps 完成 or leaves.
 */
class BrowserActivity : ComponentActivity() {
    private var web: WebView? = null
    private var requestId: String? = null
    private var startUrl = ""
    private var reported = false
    private var pageTitle by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        requestId = intent.getStringExtra(UiRequests.EXTRA_ID)
        val fixedTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        pageTitle = fixedTitle ?: startUrl
        val headers = parseStringMap(intent.getStringExtra(EXTRA_HEADERS))
        val wv = WebView(this).also { web = it }
        AndroidJsHost.configure(wv, headers)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (fixedTitle == null) pageTitle = view.title?.takeIf { it.isNotBlank() } ?: url
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress
            }
        }
        wv.loadUrl(startUrl, headers.filterKeys { !it.equals("User-Agent", true) })
        onBackPressedDispatcher.addCallback(this) { if (wv.canGoBack()) wv.goBack() else done() }
        setContent {
            CeresTheme {
                Screen(title = pageTitle, onBack = ::done, actions = { Action("完成", ::done) }) {
                    if (loadProgress in 1..99) {
                        LinearProgressIndicator(progress = { loadProgress / 100f }, Modifier.fillMaxWidth(), color = Ink)
                    }
                    AndroidView({ wv }, Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun done() {
        val wv = web ?: return finish()
        val current = wv.url ?: startUrl
        if (requestId == null) {
            AppGraph.appScope.launch { WebCookies.pull(startUrl); WebCookies.pull(current) }
            finish()
            return
        }
        wv.evaluateJavascript(AndroidJsHost.OUTER_HTML) { raw ->
            report(StrResponse(current, AndroidJsHost.decodeJsResult(raw)))
            finish()
        }
    }

    private fun report(res: StrResponse?) {
        if (reported) return
        reported = true
        UiRequests.complete(requestId, res)
    }

    override fun onDestroy() {
        if (isFinishing) report(null)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"

        fun intent(context: Context, url: String, title: String?, headers: Map<String, String>, requestId: String?) =
            Intent(context, BrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADERS, AndroidJsHost.headersJson(headers))
                .putExtra(UiRequests.EXTRA_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
