package app.ceres.reader

import android.app.Application
import kotlinx.coroutines.launch

class CeresApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.appScope.launch {
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            AppGraph.db.bookDao().deleteStaleNonShelf(weekAgo)
        }
    }
}
