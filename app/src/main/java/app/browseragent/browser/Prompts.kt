package app.browseragent.browser

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** One entry of a list a page asks the user to choose from (for example a select dropdown). */
class PromptOption(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val selected: Boolean,
    val depth: Int,
    val header: Boolean,
)

/** What the user answered. ok is false for Cancel, Block or Stay. */
class PromptAnswer(
    val ok: Boolean,
    val text: String = "",
    val ids: List<String> = emptyList(),
    val uris: List<Uri> = emptyList(),
)

/**
 * A question from a page or from the browser engine, in a form the screens can show.
 * The kind is alert, confirm, text, choice, file, beforeunload, permission or media.
 */
class BrowserPrompt(
    val kind: String,
    val host: String,
    val message: String,
    val defaultText: String = "",
    val options: List<PromptOption> = emptyList(),
    val multiple: Boolean = false,
    val fileTypes: List<String> = emptyList(),
    val answer: (PromptAnswer) -> Unit,
)

private typealias PromptResult = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>

/**
 * The questions that pages ask: JavaScript alert, confirm and prompt, dropdown lists, file
 * pickers, the leave-page question, and the permission to use the location, camera or
 * microphone. Firefox shows none of these by itself; this class turns them into BrowserPrompt
 * objects that the screen shows one after the other.
 */
class Prompts(
    private val context: Context,
    private val urlOf: (GeckoSession) -> String?,
) {
    /** The questions waiting for an answer. The first one is shown. */
    val queue = mutableStateListOf<BrowserPrompt>()

    /** Set by the activity: asks Android for runtime permissions and reports whether all were given. */
    var requestAndroid: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    private fun host(session: GeckoSession): String =
        (urlOf(session) ?: "").substringAfter("://").substringBefore('/').removePrefix("www.")

    /** Shows the question, and removes it when the engine withdraws it (for example on navigation). */
    private fun track(bp: BrowserPrompt, prompt: GeckoSession.PromptDelegate.BasePrompt) {
        queue.add(bp)
        prompt.setDelegate(
            object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(p: GeckoSession.PromptDelegate.BasePrompt) {
                    queue.remove(bp)
                }
            },
        )
    }

    private fun flatten(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>?,
        depth: Int,
        out: MutableList<PromptOption>,
    ) {
        if (choices == null) return
        for (c in choices) {
            if (c.separator) continue
            val sub = c.items
            if (sub != null && sub.isNotEmpty()) {
                out.add(PromptOption(c.id.orEmpty(), c.label.orEmpty(), false, false, depth, true))
                flatten(sub, depth + 1, out)
            } else {
                out.add(PromptOption(c.id.orEmpty(), c.label.orEmpty(), !c.disabled, c.selected, depth, false))
            }
        }
    }

    val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt,
        ): PromptResult? {
            val result = PromptResult()
            val bp = BrowserPrompt(kind = "alert", host = host(session), message = prompt.message.orEmpty()) {
                runCatching { result.complete(prompt.dismiss()) }
            }
            track(bp, prompt)
            return result
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        ): PromptResult? {
            val result = PromptResult()
            val bp = BrowserPrompt(kind = "confirm", host = host(session), message = prompt.message.orEmpty()) { a ->
                runCatching {
                    val button = if (a.ok) {
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
                    } else {
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
                    }
                    result.complete(prompt.confirm(button))
                }
            }
            track(bp, prompt)
            return result
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt,
        ): PromptResult? {
            val result = PromptResult()
            val bp = BrowserPrompt(
                kind = "text",
                host = host(session),
                message = prompt.message.orEmpty(),
                defaultText = prompt.defaultValue.orEmpty(),
            ) { a ->
                runCatching { result.complete(if (a.ok) prompt.confirm(a.text) else prompt.dismiss()) }
            }
            track(bp, prompt)
            return result
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        ): PromptResult? {
            val result = PromptResult()
            val options = ArrayList<PromptOption>()
            flatten(prompt.choices, 0, options)
            val multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
            val bp = BrowserPrompt(
                kind = "choice",
                host = host(session),
                message = prompt.message.orEmpty(),
                options = options,
                multiple = multiple,
            ) { a ->
                runCatching {
                    val response = when {
                        !a.ok || a.ids.isEmpty() -> prompt.dismiss()
                        multiple -> prompt.confirm(a.ids.toTypedArray())
                        else -> prompt.confirm(a.ids.first())
                    }
                    result.complete(response)
                }
            }
            track(bp, prompt)
            return result
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): PromptResult? {
            val result = PromptResult()
            val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
            val bp = BrowserPrompt(
                kind = "file",
                host = host(session),
                message = "",
                multiple = multiple,
                fileTypes = prompt.mimeTypes?.toList().orEmpty(),
            ) { a ->
                runCatching {
                    val response = when {
                        !a.ok || a.uris.isEmpty() -> prompt.dismiss()
                        multiple -> prompt.confirm(context, a.uris.toTypedArray())
                        else -> prompt.confirm(context, a.uris.first())
                    }
                    result.complete(response)
                }
            }
            track(bp, prompt)
            return result
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): PromptResult? {
            val result = PromptResult()
            val bp = BrowserPrompt(kind = "beforeunload", host = host(session), message = "") { a ->
                runCatching { result.complete(prompt.confirm(if (a.ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)) }
            }
            track(bp, prompt)
            return result
        }
    }

    /** Shows an allow or block question and completes the result with the choice. */
    private fun ask(kind: String, host: String, message: String, onAnswer: (Boolean) -> Unit) {
        queue.add(BrowserPrompt(kind = kind, host = host, message = message) { a -> onAnswer(a.ok) })
    }

    val permissionDelegate = object : GeckoSession.PermissionDelegate {
        // After the user allowed something, Android may still need its own permission.
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            val needed = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) {
                callback.grant()
                return
            }
            val request = requestAndroid
            if (request == null) {
                callback.reject()
                return
            }
            request(needed.toTypedArray()) { ok -> if (ok) callback.grant() else callback.reject() }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            return when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                    val result = GeckoResult<Int>()
                    ask("permission", host(session), "wants to know your location") { ok ->
                        result.complete(if (ok) allow else deny)
                    }
                    result
                }
                // Harmless: remember data, play silent video, play protected video.
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                -> GeckoResult.fromValue(allow)
                // Notifications, sound that starts by itself and the rest are refused quietly.
                else -> GeckoResult.fromValue(deny)
            }
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            val wants = when {
                video != null && video.isNotEmpty() && audio != null && audio.isNotEmpty() -> "your camera and microphone"
                video != null && video.isNotEmpty() -> "your camera"
                else -> "your microphone"
            }
            ask("media", host(session), "wants to use " + wants) { ok ->
                if (ok) {
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                } else {
                    callback.reject()
                }
            }
        }
    }
}
