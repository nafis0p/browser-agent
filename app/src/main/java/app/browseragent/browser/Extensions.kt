package app.browseragent.browser

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** An installed add-on, as the screens show it. */
class ExtItem(
    val ext: WebExtension,
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val enabled: Boolean,
    val optionsUrl: String?,
)

/** A question to the user: this add-on wants to be installed with these permissions. */
class ExtPrompt(
    val name: String,
    val permissions: List<String>,
    val origins: List<String>,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>,
)

/** The page of an add-on's toolbar button (for example the uBlock Origin panel) while it is open. */
class PopupInfo(val name: String, val session: GeckoSession)

/**
 * The add-ons (extensions) of the browser: install from the Mozilla add-on site, turn on and off,
 * remove, and use their toolbar button. Firefox on Android runs them, so ad blockers such as
 * uBlock Origin work.
 */
class Extensions(private val runtime: GeckoRuntime) {
    private val controller: WebExtensionController get() = runtime.webExtensionController
    private val main = Handler(Looper.getMainLooper())

    /** The add-ons the user installed (the app's own helper add-on is not listed). */
    val installed = mutableStateListOf<ExtItem>()

    /** The toolbar button of each add-on that has one, by add-on id. */
    val actions = mutableStateMapOf<String, WebExtension.Action>()

    /** Set while Firefox waits for the user to allow an installation. */
    var prompt by mutableStateOf<ExtPrompt?>(null)
        private set

    /** The name of the add-on that is being installed right now. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** Set while the panel of an add-on's button is shown. */
    var popup by mutableStateOf<PopupInfo?>(null)
        private set

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            val name = extension.metaData?.name?.takeIf { it.isNotBlank() } ?: extension.id
            prompt = ExtPrompt(name, permissions.toList(), origins.toList(), result)
            return result
        }
    }

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (session == null) actions[extension.id] = action
        }

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
            showPopup(extension)

        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
            showPopup(extension)
    }

    init {
        controller.setPromptDelegate(promptDelegate)
    }

    /** The add-on wants to show its panel: Firefox gets a session to load it in. */
    private fun showPopup(extension: WebExtension): GeckoResult<GeckoSession> {
        closePopup()
        val session = GeckoSession()
        session.open(runtime)
        val name = extension.metaData?.name?.takeIf { it.isNotBlank() } ?: extension.id
        popup = PopupInfo(name, session)
        return GeckoResult.fromValue(session)
    }

    /** Presses the toolbar button of an add-on. Returns false when it has no button. */
    fun openAction(id: String): Boolean {
        val a = actions[id] ?: return false
        a.click()
        return true
    }

    /** Hides the panel. The session is closed a moment later, after the screen let go of it. */
    fun closePopup() {
        val p = popup ?: return
        popup = null
        main.postDelayed({ runCatching { p.session.close() } }, 600)
    }

    /** The user's answer to the installation question. */
    fun answerPrompt(allow: Boolean) {
        val p = prompt ?: return
        prompt = null
        p.result.complete(WebExtension.PermissionPromptResponse(allow, false, allow))
    }

    private fun toItem(e: WebExtension): ExtItem {
        val m = e.metaData
        return ExtItem(
            ext = e,
            id = e.id,
            name = m?.name?.takeIf { it.isNotBlank() } ?: e.id,
            description = m?.description.orEmpty(),
            version = m?.version.orEmpty(),
            enabled = m?.enabled ?: true,
            optionsUrl = m?.optionsPageUrl,
        )
    }

    /** Reads the list of installed add-ons again. */
    fun refresh() {
        controller.list().accept(
            { list ->
                val exts = (list ?: emptyList()).filter { !it.isBuiltIn }
                for (e in exts) e.setActionDelegate(actionDelegate)
                val items = exts.map { toItem(it) }
                installed.clear()
                installed.addAll(items)
                val gone = actions.keys.filter { id -> items.none { it.id == id } }
                for (id in gone) actions.remove(id)
            },
            { },
        )
    }

    /** Installs the add-on file at the address. onDone gets null on success, or a short reason. */
    fun install(xpiUrl: String, label: String, onDone: (String?) -> Unit) {
        busy = label
        controller.install(xpiUrl).accept(
            { _ ->
                busy = null
                refresh()
                onDone(null)
            },
            { err ->
                busy = null
                onDone(err?.message ?: "Could not install it")
            },
        )
    }

    fun setEnabled(item: ExtItem, on: Boolean) {
        val source = WebExtensionController.EnableSource.USER
        val r = if (on) controller.enable(item.ext, source) else controller.disable(item.ext, source)
        r.accept({ refresh() }, { refresh() })
    }

    fun uninstall(item: ExtItem) {
        controller.uninstall(item.ext).accept({ refresh() }, { refresh() })
    }

    /** True when an add-on with this id is installed. */
    fun isInstalled(guid: String): Boolean = guid.isNotBlank() && installed.any { it.id == guid }
}
