package app.ceres.reader

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ceres.reader.ui.CeresTheme
import app.ceres.reader.ui.DetailScreen
import app.ceres.reader.ui.SearchScreen
import app.ceres.reader.ui.SettingsScreen
import app.ceres.reader.ui.ShelfScreen
import app.ceres.reader.ui.SourceDebugScreen
import app.ceres.reader.ui.SourcesScreen
import app.ceres.reader.ui.reader.ReaderScreen

class MainActivity : ComponentActivity() {

    /** Set by the reader to turn pages with volume / page keys. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CeresTheme {
                val nav = rememberNavController()
                fun reader(url: String, index: Int? = null) =
                    nav.navigate("reader/${Uri.encode(url)}?index=${index ?: -1}")

                NavHost(
                    navController = nav,
                    startDestination = "shelf",
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable("shelf") {
                        ShelfScreen(
                            openReader = { reader(it.bookUrl) },
                            openDetail = { nav.navigate("detail/${Uri.encode(it.bookUrl)}") },
                            openSearch = { nav.navigate("search") },
                            openSources = { nav.navigate("sources") },
                            openSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            onBack = { nav.popBackStack() },
                            openDetail = { url -> nav.navigate("detail/${Uri.encode(url)}") },
                        )
                    }
                    composable("detail/{url}") { entry ->
                        val url = entry.arguments?.getString("url").orEmpty()
                        DetailScreen(url, onBack = { nav.popBackStack() }, openReader = { reader(url, it) })
                    }
                    composable(
                        "reader/{url}?index={index}",
                        arguments = listOf(navArgument("index") { type = NavType.IntType; defaultValue = -1 }),
                    ) { entry ->
                        val url = entry.arguments?.getString("url").orEmpty()
                        val index = entry.arguments?.getInt("index")?.takeIf { it >= 0 }
                        ReaderScreen(url, index, onBack = { nav.popBackStack() })
                    }
                    composable("sources") {
                        SourcesScreen(
                            onBack = { nav.popBackStack() },
                            openDebug = { nav.navigate("debug/${Uri.encode(it)}") },
                        )
                    }
                    composable("debug/{url}") { entry ->
                        SourceDebugScreen(entry.arguments?.getString("url").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
