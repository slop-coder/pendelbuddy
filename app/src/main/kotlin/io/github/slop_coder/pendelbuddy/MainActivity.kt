package io.github.slop_coder.pendelbuddy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.slop_coder.pendelbuddy.core.Station
import io.github.slop_coder.pendelbuddy.data.Alerts
import io.github.slop_coder.pendelbuddy.ui.MainScreen
import io.github.slop_coder.pendelbuddy.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
                dark -> darkColorScheme(primary = Color(0xFFFFB4AB))
                else -> lightColorScheme(primary = Color(0xFFEB0000))
            }
            MaterialTheme(colorScheme = colors) { MainScreen(vm) }
        }
        if (savedInstanceState == null) opened(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** Tapped a notification about your trip. */
    private fun opened(i: Intent) {
        val what = i.getStringExtra(Alerts.OPEN) ?: return
        val id = i.getStringExtra(Alerts.STATION_ID)
        val name = i.getStringExtra(Alerts.STATION_NAME)
        val at = i.getLongExtra(Alerts.AT, 0).takeIf { it > 0 }
        vm.openFromNotification(what, if (id != null && name != null) Station(id, name) else null, at)
        i.removeExtra(Alerts.OPEN)
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        super.onStop()
        vm.stop()
    }
}
