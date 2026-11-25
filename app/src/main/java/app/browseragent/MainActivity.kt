package app.browseragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController
import app.browseragent.data.ACCENTS
import app.browseragent.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()
    private lateinit var browser: BrowserController

    // A page asked for the location, camera or microphone and Android needs the user's permission.
    private var androidPermissionDone: ((Boolean) -> Unit)? = null
    private val androidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val done = androidPermissionDone
        androidPermissionDone = null
        done?.invoke(results.isNotEmpty() && results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = BrowserController(this)
        vm.attach(browser)
        browser.prompts.requestAndroid = { permissions, done ->
            androidPermissionDone = done
            androidPermissions.launch(permissions)
        }

        // The tabs of the last run are brought back. Only when there are none, the homepage
        // is opened (or the start page is shown when no homepage is set).
        if (savedInstanceState == null && !browser.restoredTabs) {
            val homepage = vm.store.homepage
            if (homepage.isNotBlank()) browser.load(homepage)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (browser.canGoBack()) {
                        browser.back()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        setContent {
            val dark = when (vm.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val accent = ACCENTS.firstOrNull { it.key == vm.accent } ?: ACCENTS[0]
            val primary = Color(accent.argb)
            val scheme = if (dark) {
                darkColorScheme(
                    primary = primary,
                    onPrimary = Color.White,
                    primaryContainer = primary.copy(alpha = 0.30f),
                    onPrimaryContainer = Color.White,
                )
            } else {
                lightColorScheme(
                    primary = primary,
                    onPrimary = Color.White,
                    primaryContainer = primary.copy(alpha = 0.18f),
                    onPrimaryContainer = primary,
                )
            }
            MaterialTheme(colorScheme = scheme) { AppRoot(vm, browser, vm.prefs) }
        }
    }

    override fun onStop() {
        // Remember the open tabs right away in case the system ends the app.
        browser.persistTabs()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The phone is short of memory: tabs that are not in use go to sleep.
        if (::browser.isInitialized && level >= TRIM_MEMORY_RUNNING_LOW) {
            browser.sleepBackgroundTabs()
        }
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
