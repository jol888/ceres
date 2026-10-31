package app.ceres.reader.data.webdav

import app.ceres.reader.rule.Http
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class WebDavException(message: String) : IOException(message)

/** Minimal WebDAV client (Basic auth) for Ceres shelf sync. */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val root = normalizeRoot(baseUrl)
    private val auth = Credentials.basic(username, password)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun ensureDir(relative: String = "") {
        val parts = relative.trim('/').split('/').filter { it.isNotBlank() }
        var path = root
        for (part in parts) {
            path = join(path, part) + "/"
            val code = request("MKCOL", path).use { it.code }
            if (code !in listOf(201, 405, 301, 200, 204)) {
                // 405 = already exists on many servers
                if (code == 401 || code == 403) throw WebDavException("WebDAV 认证失败 ($code)")
                if (code !in 400..499) throw WebDavException("创建目录失败 HTTP $code：$path")
            }
        }
    }

    fun getText(relative: String): String? {
        val url = join(root, relative)
        return request("GET", url).use { resp ->
            when (resp.code) {
                200 -> resp.body.string()
                404 -> null
                401, 403 -> throw WebDavException("WebDAV 认证失败 (${resp.code})")
                else -> throw WebDavException("下载失败 HTTP ${resp.code}：$url")
            }
        }
    }

    fun putText(relative: String, body: String) {
        val url = join(root, relative)
        val reqBody = body.toRequestBody(jsonType)
        request("PUT", url, reqBody).use { resp ->
            when (resp.code) {
                in 200..299 -> Unit
                401, 403 -> throw WebDavException("WebDAV 认证失败 (${resp.code})")
                else -> throw WebDavException("上传失败 HTTP ${resp.code}：$url")
            }
        }
    }

    fun test(): String {
        ensureDir("")
        request("PROPFIND", root, depth = 0).use { resp ->
            when (resp.code) {
                in 200..299, 207 -> return root
                401, 403 -> throw WebDavException("WebDAV 认证失败 (${resp.code})")
                404, 405, 501 -> {
                    // Some providers reject PROPFIND on root but accept MKCOL/PUT.
                    ensureDir("ceres")
                    return root
                }
                else -> throw WebDavException("连接失败 HTTP ${resp.code}：$root")
            }
        }
    }

    private fun request(
        method: String,
        url: String,
        body: okhttp3.RequestBody? = null,
        depth: Int? = null,
    ) = Http.client.newCall(
        Request.Builder()
            .url(url)
            .method(method, body)
            .header("Authorization", auth)
            .apply { if (depth != null) header("Depth", depth.toString()) }
            .build(),
    ).execute()

    companion object {
        fun normalizeRoot(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) throw WebDavException("WebDAV 地址为空")
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            return if (u.endsWith("/")) u else "$u/"
        }

        fun join(root: String, relative: String): String {
            val r = root.trimEnd('/') + "/"
            val rel = relative.trimStart('/')
            return r + rel
        }
    }
}
