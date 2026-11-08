package de.glas.smart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val nav = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onRemote = { nav.navigate("remote") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("remote") { RemoteScreen() }
                    composable("settings") { SettingsScreen(onResults = { nav.navigate("places") }) }
                    composable("places") { PlacesScreen(onPicked = { nav.popBackStack("home", inclusive = false) }) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WeatherStore.load(this)
    }
}
