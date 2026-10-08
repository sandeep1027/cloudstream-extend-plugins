package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "OttAggregator"

/**
 * Stage 1: Metadata Discovery from OTT platforms.
 *
 * Scrapes OTT platforms (Prime Video, ZEE5, AirtelXstream, AppleTV+) for
 * content listings and metadata via OpenGraph tags. Each platform has its own
 * scraper that knows how to navigate that platform's browse/search pages and
 * extract content links.
 *
 * The scrapers return a list of content items (title, poster, description, URL)
 * that can be displayed on the home screen or returned from search. The actual
 * playable sources are found in Stage 2 (LinkSources) by searching aggregation
 * sites for matching titles.
 */

/** One content item discovered from an OTT platform. */
data class ContentItem(
    val title: String,
    val poster: String,
    val description: String,
    val url: String,
    val year: Int? = null,
    val isSeries: Boolean = false
)

/**
 * Prime Video scraper.
 *
 * Prime Video's browse pages list content with posters and titles. Each item
 * links to a watch page that has OpenGraph metadata. This scraper extracts
 * those links and fetches metadata for each.
 */
suspend fun scrapePrimeVideo(query: String? = null): List<ContentItem> {
    return try {
        val baseUrl = OttDomains.current("primevideo")
        Log.d(TAG, "scrapePrimeVideo: baseUrl=$baseUrl, query=$query")

        // Prime Video search or browse URL
        val url = if (query != null) {
            "$baseUrl/search/?phrase=$query"
        } else {
            "$baseUrl/browse/"
        }

        val doc = app.get(url).document

        // Extract content links from the page
        val items = mutableListOf<ContentItem>()
        val links = doc.select("a[href*='/detail/'], a[href*='/gp/video/detail/']")

        Log.d(TAG, "scrapePrimeVideo: found ${links.size} links")

        // Limit to first 20 items to avoid excessive requests
        links.take(20).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !items.any { it.url == href }) {
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val metadata = extractOpenGraph(fullUrl)
                if (metadata != null) {
                    items.add(
                        ContentItem(
                            title = metadata.title,
                            poster = metadata.image,
                            description = metadata.description,
                            url = fullUrl,
                            isSeries = href.contains("series", ignoreCase = true)
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapePrimeVideo: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapePrimeVideo failed: ${e.message}")
        emptyList()
    }
}

/**
 * ZEE5 scraper.
 *
 * ZEE5's browse pages list content with posters and titles. Each item links to
 * a watch page that has OpenGraph metadata.
 */
suspend fun scrapeZee5(query: String? = null): List<ContentItem> {
    return try {
        val baseUrl = OttDomains.current("zee5")
        Log.d(TAG, "scrapeZee5: baseUrl=$baseUrl, query=$query")

        val url = if (query != null) {
            "$baseUrl/search/$query"
        } else {
            "$baseUrl/browse"
        }

        val doc = app.get(url).document
        val items = mutableListOf<ContentItem>()

        // Extract content links
        val links = doc.select("a[href*='/details/'], a[href*='/movie/'], a[href*='/tv-shows/']")

        Log.d(TAG, "scrapeZee5: found ${links.size} links")

        links.take(20).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !items.any { it.url == href }) {
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val metadata = extractOpenGraph(fullUrl)
                if (metadata != null) {
                    items.add(
                        ContentItem(
                            title = metadata.title,
                            poster = metadata.image,
                            description = metadata.description,
                            url = fullUrl,
                            isSeries = href.contains("tv-shows", ignoreCase = true)
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapeZee5: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapeZee5 failed: ${e.message}")
        emptyList()
    }
}

/**
 * AirtelXstream scraper.
 */
suspend fun scrapeAirtelXstream(query: String? = null): List<ContentItem> {
    return try {
        val baseUrl = OttDomains.current("airtelxstream")
        Log.d(TAG, "scrapeAirtelXstream: baseUrl=$baseUrl, query=$query")

        val url = if (query != null) {
            "$baseUrl/search?q=$query"
        } else {
            "$baseUrl/browse"
        }

        val doc = app.get(url).document
        val items = mutableListOf<ContentItem>()
        val links = doc.select("a[href*='/details/'], a[href*='/movie/']")

        Log.d(TAG, "scrapeAirtelXstream: found ${links.size} links")

        links.take(20).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !items.any { it.url == href }) {
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val metadata = extractOpenGraph(fullUrl)
                if (metadata != null) {
                    items.add(
                        ContentItem(
                            title = metadata.title,
                            poster = metadata.image,
                            description = metadata.description,
                            url = fullUrl
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapeAirtelXstream: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapeAirtelXstream failed: ${e.message}")
        emptyList()
    }
}

/**
 * AppleTV+ scraper.
 */
suspend fun scrapeAppleTV(query: String? = null): List<ContentItem> {
    return try {
        val baseUrl = OttDomains.current("appletv")
        Log.d(TAG, "scrapeAppleTV: baseUrl=$baseUrl, query=$query")

        val url = if (query != null) {
            "$baseUrl/search?query=$query"
        } else {
            "$baseUrl/browse"
        }

        val doc = app.get(url).document
        val items = mutableListOf<ContentItem>()
        val links = doc.select("a[href*='/title/'], a[href*='/movie/']")

        Log.d(TAG, "scrapeAppleTV: found ${links.size} links")

        links.take(20).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !items.any { it.url == href }) {
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val metadata = extractOpenGraph(fullUrl)
                if (metadata != null) {
                    items.add(
                        ContentItem(
                            title = metadata.title,
                            poster = metadata.image,
                            description = metadata.description,
                            url = fullUrl,
                            isSeries = href.contains("title", ignoreCase = true)
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapeAppleTV: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapeAppleTV failed: ${e.message}")
        emptyList()
    }
}

/**
 * Search all OTT platforms in parallel and combine results.
 */
suspend fun searchAllOttPlatforms(query: String): List<ContentItem> {
    return supervisorScope {
        val jobs = listOf(
            async { scrapePrimeVideo(query) },
            async { scrapeZee5(query) },
            async { scrapeAirtelXstream(query) },
            async { scrapeAppleTV(query) }
        )
        jobs.awaitAll().flatten()
    }
}
