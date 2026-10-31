package app.ceres.reader.data

import app.ceres.reader.BuildConfig
import app.ceres.reader.rule.Http
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

data class AppRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int?,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkName: String?,
)

sealed class UpdateCheck {
    data class UpToDate(val latest: AppRelease) : UpdateCheck()
    data class Available(val latest: AppRelease) : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

object AppUpdate {
    private val TAG_VERSION = Regex("""v?(\d+(?:\.\d+)*)""")
    private val CODE_IN_BODY = Regex("""versionCode\s*[：:=\s]\s*(\d+)""", RegexOption.IGNORE_CASE)

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Ceres/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            Http.client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheck.Failed("HTTP ${resp.code}: ${body.take(120)}")
                }
                val root = JsonParser.parseString(body).asJsonObject
                val release = parseRelease(root)
                    ?: return@withContext UpdateCheck.Failed("最新 Release 无法解析")
                if (isNewer(release)) UpdateCheck.Available(release)
                else UpdateCheck.UpToDate(release)
            }
        }.getOrElse { UpdateCheck.Failed(it.message ?: it.toString()) }
    }

    private fun parseRelease(root: JsonObject): AppRelease? {
        val tag = root.get("tag_name")?.asString?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val versionName = TAG_VERSION.find(tag)?.groupValues?.get(1)
            ?: TAG_VERSION.find(root.get("name")?.asString.orEmpty())?.groupValues?.get(1)
            ?: tag.removePrefix("v")
        val notes = root.get("body")?.asString.orEmpty()
        val versionCode = CODE_IN_BODY.find(notes)?.groupValues?.get(1)?.toIntOrNull()
        val htmlUrl = root.get("html_url")?.asString.orEmpty()
        val assets = root.getAsJsonArray("assets") ?: return AppRelease(
            tag, versionName, versionCode, notes, htmlUrl, null, null,
        )
        val apk = assets.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o.get("name")?.asString.orEmpty()
            val download = o.get("browser_download_url")?.asString.orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true) || download.isBlank()) null
            else name to download
        }.minByOrNull { (name, _) ->
            when {
                name.contains(versionName) -> 0
                name.startsWith("ceres", ignoreCase = true) -> 1
                else -> 2
            }
        }
        return AppRelease(tag, versionName, versionCode, notes, htmlUrl, apk?.second, apk?.first)
    }

    /** True when [latest] is strictly newer than the running build. */
    fun isNewer(latest: AppRelease): Boolean {
        val code = latest.versionCode
        if (code != null && code > BuildConfig.VERSION_CODE) return true
        if (code != null && code < BuildConfig.VERSION_CODE) return false
        return versionGt(latest.versionName, BuildConfig.VERSION_NAME)
    }

    fun versionGt(a: String, b: String): Boolean {
        if (a == b) return false
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
