package com.bollyflix.cs3

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * BollyFlix — a WordPress catalogue of *download* posts.
 *
 * Discovery reads the site's own open `wp-json/wp/v2` REST API, which needs no
 * key and returns clean JSON: search, category rows, and per-post metadata.
 * That is the same discovery your Torrentio-backed version used, and it is the
 * half that should not change.
 *
 * Playback is what changed. Torrentio is gone. Every post carries its download
 * mirrors, and those sit behind a Cloudflare challenge, so they are resolved
 * here rather than by an index: the post is fetched with CloudflareKiller, the
 * mirror buttons are collected, and each one is unwrapped to a direct URL.
 *
 * Consequences worth stating plainly, because they differ from a debrid:
 *  - Links are ExtractorLinkType.VIDEO to real files, not MAGNETs, so the app's
 *    debrid layer is not involved and no account is needed.
 *  - Every mirror is a hardcoded case in [BollyflixExtractors]. When the site
 *    adds, renames or retires one, that branch needs updating. This is the
 *    fragility that comes with resolving downloads directly.
 */

@Serializable
data class WpPost(
    val id: Int = 0,
    /** ISO date, e.g. "2026-10-06T20:56:44". The only year signal on some posts. */
    val date: String? = null,
    val slug: String? = null,
    val link: String? = null,
    val title: WpRendered? = null,
    val content: WpRendered? = null,
    val excerpt: WpRendered? = null,
    @SerialName("featured_media") val featuredMedia: Int = 0,
    /**
     * Yoast's raw head block. This is where og:image lives — content.rendered is
     * the post body and has no meta tags, so artwork read from there is always
     * missing and every row falls back to the grey placeholder.
     */
    val yoast_head: String? = null,
)

@Serializable
data class WpRendered(val rendered: String? = null)

@Serializable
data class WpSearchEnvelope(val posts: List<WpPost> = emptyList())

/** One resolved download, carried from load() to loadLinks through the data string. */
@Serializable
data class BollyflixSource(val url: String, val label: String = "")

/**
 * The domain map. BollyFlix rotates domains and a hardcoded one goes stale, so
 * the current address is read from a small JSON file and only falls back to the
 * compiled-in value when it cannot be read.
 *
 * Kept in this plugin's own repository rather than an external one so the
 * plugin cannot break because a third party moved or deleted a file.
 */
object BollyflixDomains {
    private const val MAP_URL =
        "https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/bollyflix/domains.json"

    /** The address this build was tested against. */
    const val FALLBACK = "https://new.bollyflix.vote"

    private var resolved: String? = null

    suspend fun current(): String {
        resolved?.let { return it }
        val fromMap = try {
            app.get(MAP_URL).parsedSafe<Map<String, String>>()?.get("bollyflix")
        } catch (_: Throwable) {
            null
        }
        val chosen = fromMap?.takeIf { it.startsWith("http") } ?: FALLBACK
        resolved = chosen
        return chosen
    }

    /** "https://host/path" -> "https://host", for rewriting a stale url. */
    fun baseOf(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Throwable) {
        url
    }
}

/** Index of a quality token: 1080 -> 1080, "4k" -> 2160, absent -> Qualities.Unknown. */
fun bollyflixQuality(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    Regex("""(\d{3,4})[pP]""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.let { return it }
    val lower = text.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> 0
    }
}