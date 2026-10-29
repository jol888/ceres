package app.ceres.reader.ui

import android.webkit.CookieManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ceres.reader.AppGraph
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.rule.AnalyzeUrl
import app.ceres.reader.rule.CookieStore
import app.ceres.reader.rule.JsEngine
import app.ceres.reader.util.parseAnyMap
import app.ceres.reader.util.toJsonString
import app.ceres.reader.web.BrowserActivity
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of Legado's `loginUi`: text / password / button / toggle / select. */
data class LoginRow(
    val name: String,
    val type: String,
    val action: String?,
    val chars: List<String>,
    val default: String?,
    val label: String,
)

fun parseLoginUi(loginUi: String?): List<LoginRow> {
    val raw = loginUi?.trim().orEmpty()
    if (!raw.startsWith("[")) return emptyList()
    return runCatching {
        JsonParser.parseString(raw).asJsonArray.mapNotNull { el ->
            val m = parseAnyMap(el.toString())
            val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LoginRow(
                name = name,
                type = m["type"]?.toString()?.lowercase() ?: "text",
                action = m["action"]?.toString()?.takeIf { it.isNotBlank() },
                chars = (m["chars"] as? List<*>)?.map { it.toString() }.orEmpty(),
                default = m["default"]?.toString(),
                label = m["viewName"]?.toString()?.trim('\'', '"')?.takeIf { it.isNotBlank() } ?: name,
            )
        }
    }.getOrDefault(emptyList())
}

private class LoginState(val source: BookSource, val saved: Map<String, String>, val webHeaders: Map<String, String>)

@Composable
fun SourceLoginScreen(url: String, onBack: () -> Unit) {
    val state by produceState<LoginState?>(null, url) {
        value = withContext(Dispatchers.IO) {
            val s = AppGraph.sources.get(url) ?: return@withContext null
            val headers = s.getLoginWebUrl()?.let { runCatching { AnalyzeUrl(it, source = s).headerMap.toMap() }.getOrNull() }
            LoginState(s, s.getLoginInfoMap(), headers.orEmpty())
        }
    }
    val st = state
    if (st == null) {
        Screen("登录", onBack) { CenterMessage("加载中…") }
        return
    }
    LoginForm(st, onBack)
}

@Composable
private fun LoginForm(st: LoginState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = st.source
    val rows = remember(source) { parseLoginUi(source.loginUi) }
    val values = remember(source) {
        mutableStateMapOf<String, String>().apply {
            rows.forEach { r -> (st.saved[r.name] ?: r.default ?: r.chars.firstOrNull())?.let { put(r.name, it) } }
        }
    }
    val webUrl = remember(source) { source.getLoginWebUrl() }
    val loginJs = remember(source) { source.getLoginJs() }
    var status by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    fun run(label: String, block: () -> Any?) {
        if (running) return
        running = true
        status = "$label…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            status = r.fold(
                { v -> "$label 完成" + (v?.let { JsEngine.toStr(it) }?.takeIf { it.isNotBlank() && it != "undefined" }?.let { "：$it" } ?: "") },
                { e -> "$label 失败：${e.message ?: e.javaClass.simpleName}" },
            )
            running = false
        }
    }

    fun openWeb(target: String) = context.startActivity(
        BrowserActivity.intent(context, target, "登录 · ${source.bookSourceName}", st.webHeaders, null)
    )

    fun action(row: LoginRow) {
        val a = row.action ?: return
        if (a.startsWith("http", true)) openWeb(a)
        else run(row.label) { source.evalJS("${loginJs.orEmpty()}\n$a", HashMap(values)) }
    }

    fun login() = run("登录") {
        if (rows.isNotEmpty()) source.putLoginInfo(toJsonString(HashMap(values)))
        source.login()
    }

    Screen(
        title = "登录 · ${source.bookSourceName}",
        onBack = onBack,
        actions = { if (rows.isNotEmpty() || loginJs != null) Action("登录", ::login) },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rows.isEmpty() && webUrl == null && loginJs == null) {
                Text("这个书源不需要登录。")
            }
            rows.forEach { row ->
                when (row.type) {
                    "button" -> InkButton(row.label, Modifier.fillMaxWidth(), enabled = !running) { action(row) }
                    "toggle", "select" -> {
                        val cur = values[row.name].orEmpty()
                        InkButton("${row.label}：$cur", Modifier.fillMaxWidth(), enabled = !running) {
                            if (row.chars.isNotEmpty()) {
                                values[row.name] = row.chars[(row.chars.indexOf(cur) + 1) % row.chars.size]
                            }
                            action(row)
                        }
                    }
                    else -> OutlinedTextField(
                        value = values[row.name].orEmpty(),
                        onValueChange = { values[row.name] = it },
                        label = { Text(row.label) },
                        singleLine = true,
                        visualTransformation = if (row.type == "password") PasswordVisualTransformation()
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (rows.isNotEmpty() || loginJs != null) {
                InkButton("登录", Modifier.fillMaxWidth(), enabled = !running, onClick = ::login)
            }
            webUrl?.let { u ->
                Text("在网页中登录，关闭网页后登录状态（Cookie）会保存给这个书源使用。", style = MaterialTheme.typography.bodySmall)
                InkButton("网页登录", Modifier.fillMaxWidth(), enabled = !running) { openWeb(u) }
            }
            InkButton("退出登录", Modifier.fillMaxWidth(), enabled = !running) {
                values.clear()
                // Ceres pushes its own cookies before each WebView load, so clearing the WebView jar loses nothing.
                CookieManager.getInstance().removeAllCookies(null)
                run("退出登录") {
                    source.removeLoginInfo()
                    source.removeLoginHeader()
                    CookieStore.removeCookie(source.bookSourceUrl)
                    webUrl?.let { CookieStore.removeCookie(it) }
                    null
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
