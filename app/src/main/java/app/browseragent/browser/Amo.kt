package app.browseragent.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One add-on from the Mozilla add-on site, with the address of the file to install. */
class AmoAddon(
    val guid: String,
    val slug: String,
    val name: String,
    val summary: String,
    val xpiUrl: String,
    val version: String,
    val users: Long,
)

/**
 * Mozilla's add-on site (addons.mozilla.org). Only add-ons that work in Firefox on Android are
 * listed (the app=android filter), so everything shown here can be installed in this browser.
 */
object Amo {
    private const val BASE = "https://addons.mozilla.org/api/v5/addons/"
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** The most used add-ons for Android. */
    suspend fun popular(): List<AmoAddon> =
        list("search/?app=android&type=extension&sort=users&page_size=20&lang=en-US")

    suspend fun search(query: String): List<AmoAddon> =
        list("search/?app=android&type=extension&page_size=20&lang=en-US&q=" + URLEncoder.encode(query, "UTF-8"))

    /** One add-on by its short name on the site, for example ublock-origin. */
    suspend fun bySlug(slug: String): AmoAddon? = withContext(Dispatchers.IO) {
        val body = fetch(BASE + "addon/" + slug + "/?lang=en-US") ?: return@withContext null
        try {
            parseOne(Json.parseToJsonElement(body).jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun list(path: String): List<AmoAddon> = withContext(Dispatchers.IO) {
        val body = fetch(BASE + path) ?: return@withContext emptyList()
        try {
            val results = Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray
            results?.mapNotNull { (it as? JsonObject)?.let(::parseOne) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetch(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    // Texts come as plain strings, or as an object per language when no language was asked for.
    private fun text(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonObject -> (e.values.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
        else -> ""
    }

    private fun parseOne(o: JsonObject): AmoAddon? {
        val version = o["current_version"] as? JsonObject
        val file = version?.get("file") as? JsonObject
        val xpi = (file?.get("url") as? JsonPrimitive)?.contentOrNull ?: return null
        return AmoAddon(
            guid = text(o["guid"]),
            slug = text(o["slug"]),
            name = text(o["name"]),
            summary = text(o["summary"]),
            xpiUrl = xpi,
            version = text(version["version"]),
            users = (o["average_daily_users"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}
