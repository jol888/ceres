package app.ceres.reader

import android.content.Context
import app.ceres.reader.data.AppDatabase
import app.ceres.reader.data.BookRepository
import app.ceres.reader.data.CacheManager
import app.ceres.reader.data.KvDao
import app.ceres.reader.data.LocalImporter
import app.ceres.reader.data.Settings
import app.ceres.reader.data.SourceRepository
import app.ceres.reader.data.model.KvEntry
import app.ceres.reader.rule.KeyValueStore
import app.ceres.reader.rule.Kv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Minimal service locator. */
object AppGraph {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var sources: SourceRepository
        private set
    lateinit var books: BookRepository
        private set
    lateinit var cache: CacheManager
        private set
    lateinit var importer: LocalImporter
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        db = AppDatabase.create(context)
        Kv.store = RoomKv(db.kvDao())
        settings = Settings(context)
        sources = SourceRepository(db.sourceDao())
        books = BookRepository(db, sources, File(context.filesDir, "books"))
        cache = CacheManager(appScope, books)
        importer = LocalImporter(context.applicationContext, books, db)
    }
}

private class RoomKv(private val dao: KvDao) : KeyValueStore {
    override fun get(key: String): String? = dao.get(key)
    override fun put(key: String, value: String?) {
        if (value == null) dao.delete(key) else dao.put(KvEntry(key, value))
    }
}
