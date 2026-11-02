package app.ceres.reader.data

import app.ceres.reader.BuildConfig
import app.ceres.reader.data.model.BookSource
import app.ceres.reader.data.webdav.WebDavSync
import app.ceres.reader.util.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupWebDav(
    val url: String = "",
    val username: String = "",
    val password: String = "",
)

/** Export file: book sources (JSON + the addresses they came from) and the WebDAV sync credentials. */
data class CeresBackup(
    val app: String = APP,
    val type: String = TYPE,
    val version: Int = 1,
    val exportedAt: Long = 0,
    val exportedAtText: String = "",
    val appVersion: String = "",
    val sources: List<BookSource> = emptyList(),
    val sourceUrls: List<SourceSubscription> = emptyList(),
    val webDav: BackupWebDav? = null,
) {
    companion object {
        const val APP = "ceres"
        const val TYPE = "backup"
    }
}

data class ExportResult(
    val fileName: String,
    val sourceCount: Int,
    /** Remote path when uploaded; null when WebDAV isn't set up or the upload failed. */
    val remotePath: String?,
    val uploadError: String?,
)

class BackupManager(
    private val sources: SourceRepository,
    private val settings: Settings,
    private val webDav: WebDavSync,
) {
    fun fileName(at: Long): String =
        "ceres-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))}.json"

    suspend fun build(at: Long): CeresBackup {
        val dav = settings.webDav.value
        return CeresBackup(
            exportedAt = at,
            exportedAtText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(at)),
            appVersion = BuildConfig.VERSION_NAME,
            sources = sources.exportSources(),
            sourceUrls = settings.sourceSubscriptions(),
            webDav = BackupWebDav(dav.url, dav.username, dav.password).takeIf { it.url.isNotBlank() },
        )
    }

    /** Uploads the backup to WebDAV (when configured), then hands the same JSON to [write]. */
    suspend fun export(at: Long, write: (String) -> Unit): ExportResult {
        val backup = build(at)
        val json = GSON.toJson(backup)
        val name = fileName(at)
        var remotePath: String? = null
        var uploadError: String? = null
        if (webDav.configured()) {
            runCatching { webDav.uploadBackup(name, json) }
                .onSuccess { remotePath = it }
                .onFailure { uploadError = it.message ?: it.javaClass.simpleName }
        }
        withContext(Dispatchers.IO) { write(json) }
        return ExportResult(name, backup.sources.size, remotePath, uploadError)
    }

    /** Restores sources, source addresses and WebDAV credentials from an exported file. */
    suspend fun restore(json: String): String {
        val backup = withContext(Dispatchers.Default) {
            runCatching { GSON.fromJson(json, CeresBackup::class.java) }.getOrNull()
        }
        if (backup == null || backup.app != CeresBackup.APP || backup.type != CeresBackup.TYPE) {
            throw IllegalArgumentException("不是 Ceres 导出的备份文件")
        }
        val imported = backup.sources.takeIf { it.isNotEmpty() }
            ?.let { sources.importJson(GSON.toJson(it)) } ?: 0
        backup.sourceUrls.forEach { settings.addSourceSubscription(it.url, it.addedAt) }
        val dav = backup.webDav?.takeIf { it.url.isNotBlank() }
        if (dav != null) {
            settings.updateWebDav {
                it.copy(enabled = true, url = dav.url, username = dav.username, password = dav.password)
            }
        }
        return buildString {
            append("已恢复 $imported 个书源")
            if (backup.sourceUrls.isNotEmpty()) append("、${backup.sourceUrls.size} 个书源地址")
            if (dav != null) append("和 WebDAV 账号")
            if (backup.exportedAtText.isNotBlank()) append("（备份于 ${backup.exportedAtText}）")
        }
    }
}
