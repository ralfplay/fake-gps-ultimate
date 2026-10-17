package dev.ramiro.fakegps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.ramiro.fakegps.ui.MainScreen
import dev.ramiro.fakegps.ui.theme.FakeGpsTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FakeGpsTheme {
                MainScreen()
            }
        }
    }
}
