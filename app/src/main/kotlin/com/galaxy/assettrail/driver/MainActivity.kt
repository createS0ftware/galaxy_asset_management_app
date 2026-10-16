package com.galaxy.assettrail.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.galaxy.assettrail.driver.ui.AppRoot
import com.galaxy.assettrail.driver.ui.LocalContainer
import com.galaxy.assettrail.driver.ui.theme.AssetTrailTheme
import com.galaxy.assettrail.driver.ui.theme.Palette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as AssetTrailApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.NavBar.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                AssetTrailTheme { AppRoot() }
            }
        }

        // NFC reader mode only while a check-in waits for a tag, and only while we're in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    container.nfc.listening.collect { listening ->
                        if (listening) container.nfc.enable(this@MainActivity) else container.nfc.disable(this@MainActivity)
                    }
                } finally {
                    container.nfc.disable(this@MainActivity)
                }
            }
        }
    }
}
