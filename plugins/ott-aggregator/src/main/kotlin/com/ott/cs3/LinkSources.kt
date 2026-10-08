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

                    // Try to get poster image
                    val img = link.selectFirst("img")
                    val poster = img?.attr("src")

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

        val doc = app.get(url).document
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

                    val img = link.selectFirst("img")
                    val poster = img?.attr("src")

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

                    val img = link.selectFirst("img")
                    val poster = img?.attr("src")

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

        val seenUrls = mutableSetOf<String>()

        downloadLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
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
