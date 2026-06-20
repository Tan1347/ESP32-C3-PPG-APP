package org.tan.ppgtoolapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.tan.ppgtoolapp.ui.navigation.AppNavigation
import org.tan.ppgtoolapp.ui.theme.PPGToolTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PPGToolTheme {
                AppNavigation()
            }
        }
    }
}
