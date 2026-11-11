package app.browseragent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import app.browseragent.agent.AgentViewModel
import app.browseragent.browser.BrowserController
import app.browseragent.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()
    private lateinit var browser: BrowserController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = BrowserController(this)
        vm.attach(browser)
        if (savedInstanceState == null) browser.load("https://duckduckgo.com")

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (browser.webView.canGoBack()) {
                        browser.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { AppRoot(vm, browser) }
        }
    }

    override fun onDestroy() {
        browser.webView.destroy()
        super.onDestroy()
    }
}
