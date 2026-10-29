package app.ceres.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.ceres.reader.data.model.Book
import app.ceres.reader.data.model.BookChapter
import app.ceres.reader.data.model.KvEntry
import app.ceres.reader.data.model.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE inShelf = 1 ORDER BY durChapterTime DESC, addTime DESC")
    fun shelf(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE bookUrl = :url")
    suspend fun get(url: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl = :url")
    fun observe(url: String): Flow<Book?>

    @Upsert
    suspend fun upsert(book: Book)

    @Query("DELETE FROM books WHERE bookUrl = :url")
    suspend fun delete(url: String)

    @Query("UPDATE books SET durChapterIndex = :index, durChapterPos = :pos, durChapterTitle = :title, durChapterTime = :time WHERE bookUrl = :url")
    suspend fun saveProgress(url: String, index: Int, pos: Int, title: String?, time: Long)

    @Query("DELETE FROM books WHERE inShelf = 0 AND durChapterTime < :before")
    suspend fun deleteStaleNonShelf(before: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY `index`")
    suspend fun list(bookUrl: String): List<BookChapter>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun count(bookUrl: String): Int

    @Query("DELETE FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<BookChapter>)

    @Transaction
    suspend fun replace(bookUrl: String, chapters: List<BookChapter>) {
        deleteByBook(bookUrl)
        insertAll(chapters)
    }
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY sortOrder, name")
    suspend fun enabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE url = :url")
    suspend fun get(url: String): SourceEntity?

    @Upsert
    suspend fun upsertAll(list: List<SourceEntity>)

    @Query("UPDATE sources SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE sources SET enabled = :enabled")
    suspend fun setAllEnabled(enabled: Boolean)

    @Query("DELETE FROM sources WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun get(key: String): String?

    @Upsert
    fun put(entry: KvEntry)

    @Query("DELETE FROM kv WHERE `key` = :key")
    fun delete(key: String)
}

@Database(
    entities = [Book::class, BookChapter::class, SourceEntity::class, KvEntry::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun sourceDao(): SourceDao
    abstract fun kvDao(): KvDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "ceres.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
