package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

private const val TAG = "OttAggregator"

/**
 * SkyMoviesHD scraper for https://skymovieshd.band
 *
 * Site structure:
 * - Homepage has "MOST POPULAR MOVIES" and "Latest Updated Movies"
 * - Each movie links to a .html detail page
 * - Detail pages have download links to file hosts (HubCloud, GdFlix, etc.)
 */

/** One content item from SkyMoviesHD. */
data class SkyMovieItem(
    val title: String,
    val url: String,
    val poster: String? = null,
    val year: Int? = null,
    val quality: String? = null
)

/**
 * Scrape SkyMoviesHD homepage for popular and latest movies.
 */
suspend fun scrapeSkyMoviesHDHome(): List<SkyMovieItem> {
    return try {
        val baseUrl = OttDomains.current("skymovieshd")
        Log.d(TAG, "scrapeSkyMoviesHDHome: baseUrl=$baseUrl")

        val doc = app.get(baseUrl).document
        val items = mutableListOf<SkyMovieItem>()

        // Extract movie links from the page
        // Pattern: <a href="/movie/Title-(Year)-format-size.html">
        val movieLinks = doc.select("a[href*=/movie/]")

        Log.d(TAG, "scrapeSkyMoviesHDHome: found ${movieLinks.size} movie links")

        val seenUrls = mutableSetOf<String>()

        movieLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
                seenUrls.add(href)

                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val title = link.text().trim()

                if (title.isNotBlank()) {
                    // Extract year from title if present
                    val yearMatch = Regex("""\((\d{4})\)""").find(title)
                    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

                    // Extract quality from title
                    val quality = when {
                        title.contains("1080p", ignoreCase = true) -> "1080p"
                        title.contains("720p", ignoreCase = true) -> "720p"
                        title.contains("480p", ignoreCase = true) -> "480p"
                        else -> null
                    }

                    // Try to get poster image - check multiple locations
                    var poster: String? = null
                    // First check for img inside the link
                    val img = link.selectFirst("img")
                    poster = img?.attr("src")

                    // If not found, check parent container
                    if (poster.isNullOrBlank()) {
                        val parent = link.parent()
                        val parentImg = parent?.selectFirst("img[src*=http], img[data-src*=http]")
                        poster = parentImg?.attr("src") ?: parentImg?.attr("data-src")
                    }

                    // If still not found, look for nearby img tags
                    if (poster.isNullOrBlank()) {
                        val nearbyImg = link.closest("div, li, article")?.selectFirst("img[src*=http]")
                        poster = nearbyImg?.attr("src")
                    }

                    items.add(
                        SkyMovieItem(
                            title = title,
                            url = fullUrl,
                            poster = poster,
                            year = year,
                            quality = quality
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapeSkyMoviesHDHome: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapeSkyMoviesHDHome failed: ${e.message}")
        emptyList()
    }
}

/**
 * Scrape SkyMoviesHD category page.
 */
suspend fun scrapeSkyMoviesHDCategory(categoryPath: String): List<SkyMovieItem> {
    return try {
        val baseUrl = OttDomains.current("skymovieshd")
        val url = "$baseUrl$categoryPath"
        Log.d(TAG, "scrapeSkyMoviesHDCategory: url=$url")

        // Use documentLarge for category pages which may be large
        val doc = app.get(url).documentLarge
        val items = mutableListOf<SkyMovieItem>()

        val movieLinks = doc.select("a[href*=/movie/]")

        Log.d(TAG, "scrapeSkyMoviesHDCategory: found ${movieLinks.size} movie links")

        val seenUrls = mutableSetOf<String>()

        movieLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
                seenUrls.add(href)

                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val title = link.text().trim()

                if (title.isNotBlank()) {
                    val yearMatch = Regex("""\((\d{4})\)""").find(title)
                    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

                    val quality = when {
                        title.contains("1080p", ignoreCase = true) -> "1080p"
                        title.contains("720p", ignoreCase = true) -> "720p"
                        title.contains("480p", ignoreCase = true) -> "480p"
                        else -> null
                    }

                    // Try to get poster image - check multiple locations
                    var poster: String? = null
                    val img = link.selectFirst("img")
                    poster = img?.attr("src")

                    if (poster.isNullOrBlank()) {
                        val parent = link.parent()
                        val parentImg = parent?.selectFirst("img[src*=http], img[data-src*=http]")
                        poster = parentImg?.attr("src") ?: parentImg?.attr("data-src")
                    }

                    if (poster.isNullOrBlank()) {
                        val nearbyImg = link.closest("div, li, article")?.selectFirst("img[src*=http]")
                        poster = nearbyImg?.attr("src")
                    }

                    items.add(
                        SkyMovieItem(
                            title = title,
                            url = fullUrl,
                            poster = poster,
                            year = year,
                            quality = quality
                        )
                    )
                }
            }
        }

        Log.d(TAG, "scrapeSkyMoviesHDCategory: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "scrapeSkyMoviesHDCategory failed: ${e.message}")
        emptyList()
    }
}

/**
 * Search SkyMoviesHD for a specific title.
 */
suspend fun searchSkyMoviesHD(query: String): List<SkyMovieItem> {
    return try {
        val baseUrl = OttDomains.current("skymovieshd")
        val url = "$baseUrl/search.php?search=${query.replace(" ", "+")}&cat=All"
        Log.d(TAG, "searchSkyMoviesHD: url=$url")

        val doc = app.get(url).document
        val items = mutableListOf<SkyMovieItem>()

        val movieLinks = doc.select("a[href*=/movie/]")

        Log.d(TAG, "searchSkyMoviesHD: found ${movieLinks.size} movie links")

        val seenUrls = mutableSetOf<String>()

        movieLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
                seenUrls.add(href)

                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val title = link.text().trim()

                if (title.isNotBlank()) {
                    val yearMatch = Regex("""\((\d{4})\)""").find(title)
                    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

                    val quality = when {
                        title.contains("1080p", ignoreCase = true) -> "1080p"
                        title.contains("720p", ignoreCase = true) -> "720p"
                        title.contains("480p", ignoreCase = true) -> "480p"
                        else -> null
                    }

                    // Try to get poster image - check multiple locations
                    var poster: String? = null
                    val img = link.selectFirst("img")
                    poster = img?.attr("src")

                    if (poster.isNullOrBlank()) {
                        val parent = link.parent()
                        val parentImg = parent?.selectFirst("img[src*=http], img[data-src*=http]")
                        poster = parentImg?.attr("src") ?: parentImg?.attr("data-src")
                    }

                    if (poster.isNullOrBlank()) {
                        val nearbyImg = link.closest("div, li, article")?.selectFirst("img[src*=http]")
                        poster = nearbyImg?.attr("src")
                    }

                    items.add(
                        SkyMovieItem(
                            title = title,
                            url = fullUrl,
                            poster = poster,
                            year = year,
                            quality = quality
                        )
                    )
                }
            }
        }

        Log.d(TAG, "searchSkyMoviesHD: extracted ${items.size} items")
        items
    } catch (e: Exception) {
        Log.e(TAG, "searchSkyMoviesHD failed: ${e.message}")
        emptyList()
    }
}

/**
 * Extract download links from a SkyMoviesHD movie detail page.
 */
suspend fun extractSkyMoviesHDDownloadLinks(movieUrl: String): List<OttSource> {
    return try {
        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: url=$movieUrl")

        val doc = app.get(movieUrl).document
        val sources = mutableListOf<OttSource>()

        // Find all download links on the page
        val downloadLinks = doc.select("a[href]")

        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: found ${downloadLinks.size} total links")

        // Log all unique link domains to understand the page structure
        val allDomains = mutableSetOf<String>()
        downloadLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http")) {
                val domain = href.substringAfter("://").substringBefore("/").substringBefore("?")
                allDomains.add(domain)
            }
        }
        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: unique domains: ${allDomains.joinToString(", ")}")

        // Check for iframes (embedded video players)
        val iframes = doc.select("iframe[src]")
        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: found ${iframes.size} iframes")
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                Log.d(TAG, "extractSkyMoviesHDDownloadLinks: iframe src=$src")
            }
        }

        // Check for video tags
        val videos = doc.select("video[src], source[src]")
        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: found ${videos.size} video sources")
        videos.forEach { video ->
            val src = video.attr("src")
            if (src.isNotBlank()) {
                Log.d(TAG, "extractSkyMoviesHDDownloadLinks: video src=$src")
            }
        }

        val seenUrls = mutableSetOf<String>()

        downloadLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
                // Log the link for debugging
                if (href.startsWith("http")) {
                    Log.d(TAG, "extractSkyMoviesHDDownloadLinks: checking link: $href")
                }

                // Check if it's a bypass-able link
                if (href.contains("hubcloud", ignoreCase = true) ||
                    href.contains("gdflix", ignoreCase = true) ||
                    href.contains("gdlink", ignoreCase = true) ||
                    href.contains("fastdlserver", ignoreCase = true) ||
                    href.contains("linksmod", ignoreCase = true) ||
                    href.contains("sidexfee", ignoreCase = true) ||
                    href.contains("howblogs", ignoreCase = true) ||
                    href.contains("tpead", ignoreCase = true) ||
                    href.contains("skybap", ignoreCase = true)) {

                    seenUrls.add(href)
                    val linkText = link.text()
                    val quality = ottQuality(linkText)

                    Log.d(TAG, "extractSkyMoviesHDDownloadLinks: extracted source: $href (text=$linkText)")

                    sources.add(
                        OttSource(
                            url = href,
                            label = linkText.ifBlank { "download" },
                            quality = quality
                        )
                    )
                }
            }
        }

        Log.d(TAG, "extractSkyMoviesHDDownloadLinks: extracted ${sources.size} download sources")
        sources
    } catch (e: Exception) {
        Log.e(TAG, "extractSkyMoviesHDDownloadLinks failed: ${e.message}")
        emptyList()
    }
}
