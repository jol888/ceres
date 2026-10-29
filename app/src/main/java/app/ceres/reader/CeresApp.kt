package app.ceres.reader

import android.app.Application
import app.ceres.reader.rule.Host
import app.ceres.reader.rule.JsEngine
import app.ceres.reader.web.AndroidJsHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

class CeresApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Host.current = AndroidJsHost(this)
        Host.filesDir = File(cacheDir, "js")
        AppGraph.appScope.launch {
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            AppGraph.db.bookDao().deleteStaleNonShelf(weekAgo)
        }
        AppGraph.appScope.launch(Dispatchers.Default) {
            JsEngine.warmUp()
            Jsoup.parse("<p>warm</p>").select("p").text()
        }
    }
}
