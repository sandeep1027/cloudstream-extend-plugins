package com.ott.cs3

import com.lagradost.cloudstream3.app
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * OTT Metadata Aggregator — combines metadata from OTT platforms with playable
 * sources from aggregation sites.
 *
 * Stage 1 (Metadata Discovery): Scrape OTT platforms (Prime Video, ZEE5, etc.)
 * for content listings and metadata via OpenGraph tags.
 *
 * Stage 2 (Link Resolution): For each title, search aggregation sites
 * (SkyMoviesHD, Vegamovies, Cinevood) for matching content with download links,
 * then resolve those links using bypass logic (HubCloud, GdFlix, etc.).
 *
 * This provides rich metadata from official OTT platforms while finding playable
 * sources from aggregation sites that already have bypass-able links.
 */

/** One resolved download link from an aggregation site. */
@Serializable
data class OttSource(
    val url: String,
    val label: String = "",
    val quality: Int = 0
)

/** OpenGraph metadata extracted from a page. */
@Serializable
data class OpenGraphData(
    val title: String = "",
    val image: String = "",
    val description: String = "",
    val url: String = ""
)

/**
 * Domain map for all rotating domains. Each site rotates its domain and a
 * hardcoded one goes stale, so the current addresses are read from a small JSON
 * file and only fall back to compiled-in values when it cannot be read.
 *
 * Kept in this plugin's own repository so the plugin cannot break because a
 * third party moved or deleted a file.
 */
object OttDomains {
    private const val MAP_URL =
        "https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/ott-aggregator/domains.json"

    // Fallback domains (the addresses this build was tested against)
    const val FALLBACK_SKYMOVIESHD = "https://skymovieshd.example.com"
    const val FALLBACK_VEGAMOVIES = "https://vegamovies.example.com"
    const val FALLBACK_CINEVOOD = "https://cinevood.example.com"
    const val FALLBACK_PRIMEVIDEO = "https://www.primevideo.com"
    const val FALLBACK_ZEE5 = "https://www.zee5.com"
    const val FALLBACK_AIRTELXSTREAM = "https://www.airtelxstream.com"
    const val FALLBACK_APPLETV = "https://tv.apple.com"

    private var resolved: Map<String, String>? = null

    suspend fun current(key: String): String {
        resolved?.let { return it[key] ?: fallbackFor(key) }
        val fromMap = try {
            app.get(MAP_URL).parsedSafe<Map<String, String>>()
        } catch (_: Throwable) {
            null
        }
        resolved = fromMap
        return fromMap?.get(key)?.takeIf { it.startsWith("http") } ?: fallbackFor(key)
    }

    private fun fallbackFor(key: String): String = when (key) {
        "skymovieshd" -> FALLBACK_SKYMOVIESHD
        "vegamovies" -> FALLBACK_VEGAMOVIES
        "cinevood" -> FALLBACK_CINEVOOD
        "primevideo" -> FALLBACK_PRIMEVIDEO
        "zee5" -> FALLBACK_ZEE5
        "airtelxstream" -> FALLBACK_AIRTELXSTREAM
        "appletv" -> FALLBACK_APPLETV
        else -> ""
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
fun ottQuality(text: String?): Int {
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
