package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val TAG = "OttAggregator"

/**
 * Stage 2: Link Resolution from aggregation sites.
 *
 * For each title discovered in Stage 1, search aggregation sites
 * (SkyMoviesHD, Vegamovies, Cinevood) for matching content with download links,
 * then resolve those links using bypass logic.
 *
 * Each aggregator site has its own scraper that knows how to navigate that
 * site's search/browse pages and extract download links. The scrapers return
 * a list of OttSource objects that can be dispatched to the bypass extractors.
 */

/**
 * SkyMoviesHD scraper.
 *
 * SkyMoviesHD lists movies with download links to various file hosts (HubCloud,
 * GdFlix, etc.). This scraper searches for a title, extracts the download page
 * links, and collects the bypass-able shortener links.
 */
suspend fun scrapeSkyMoviesHD(title: String, year: Int? = null): List<OttSource> {
    return try {
        val baseUrl = OttDomains.current("skymovieshd")
        Log.d(TAG, "scrapeSkyMoviesHD: baseUrl=$baseUrl, title=$title, year=$year")

        // Search URL - SkyMoviesHD typically uses /?s=query format
        val searchQuery = if (year != null) "$title $year" else title
        val url = "$baseUrl/?s=${searchQuery.replace(" ", "+")}"

        val doc = app.get(url).document
        val sources = mutableListOf<OttSource>()

        // Find post links
        val postLinks = doc.select("article a[href], .post-title a[href], h2 a[href]")

        Log.d(TAG, "scrapeSkyMoviesHD: found ${postLinks.size} post links")

        // For each matching post, extract download links
        postLinks.take(5).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.contains(baseUrl)) {
                try {
                    val postDoc = app.get(href).document
                    val downloadLinks = extractDownloadLinks(postDoc, href)
                    sources.addAll(downloadLinks)
                } catch (e: Exception) {
                    Log.w(TAG, "scrapeSkyMoviesHD: failed to fetch post $href: ${e.message}")
                }
            }
        }

        Log.d(TAG, "scrapeSkyMoviesHD: extracted ${sources.size} sources")
        sources
    } catch (e: Exception) {
        Log.e(TAG, "scrapeSkyMoviesHD failed: ${e.message}")
        emptyList()
    }
}

/**
 * Vegamovies scraper.
 *
 * Vegamovies lists movies and web series with download links to various file
 * hosts. This scraper searches for a title and extracts download links.
 */
suspend fun scrapeVegamovies(title: String, year: Int? = null): List<OttSource> {
    return try {
        val baseUrl = OttDomains.current("vegamovies")
        Log.d(TAG, "scrapeVegamovies: baseUrl=$baseUrl, title=$title, year=$year")

        val searchQuery = if (year != null) "$title $year" else title
        val url = "$baseUrl/?s=${searchQuery.replace(" ", "+")}"

        val doc = app.get(url).document
        val sources = mutableListOf<OttSource>()

        val postLinks = doc.select("article a[href], .entry-title a[href], h2 a[href]")

        Log.d(TAG, "scrapeVegamovies: found ${postLinks.size} post links")

        postLinks.take(5).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.contains(baseUrl)) {
                try {
                    val postDoc = app.get(href).document
                    val downloadLinks = extractDownloadLinks(postDoc, href)
                    sources.addAll(downloadLinks)
                } catch (e: Exception) {
                    Log.w(TAG, "scrapeVegamovies: failed to fetch post $href: ${e.message}")
                }
            }
        }

        Log.d(TAG, "scrapeVegamovies: extracted ${sources.size} sources")
        sources
    } catch (e: Exception) {
        Log.e(TAG, "scrapeVegamovies failed: ${e.message}")
        emptyList()
    }
}

/**
 * Cinevood scraper.
 *
 * Cinevood lists movies with download links to various file hosts. This scraper
 * searches for a title and extracts download links.
 */
suspend fun scrapeCinevood(title: String, year: Int? = null): List<OttSource> {
    return try {
        val baseUrl = OttDomains.current("cinevood")
        Log.d(TAG, "scrapeCinevood: baseUrl=$baseUrl, title=$title, year=$year")

        val searchQuery = if (year != null) "$title $year" else title
        val url = "$baseUrl/?s=${searchQuery.replace(" ", "+")}"

        val doc = app.get(url).document
        val sources = mutableListOf<OttSource>()

        val postLinks = doc.select("article a[href], .post-title a[href], h2 a[href]")

        Log.d(TAG, "scrapeCinevood: found ${postLinks.size} post links")

        postLinks.take(5).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.contains(baseUrl)) {
                try {
                    val postDoc = app.get(href).document
                    val downloadLinks = extractDownloadLinks(postDoc, href)
                    sources.addAll(downloadLinks)
                } catch (e: Exception) {
                    Log.w(TAG, "scrapeCinevood: failed to fetch post $href: ${e.message}")
                }
            }
        }

        Log.d(TAG, "scrapeCinevood: extracted ${sources.size} sources")
        sources
    } catch (e: Exception) {
        Log.e(TAG, "scrapeCinevood failed: ${e.message}")
        emptyList()
    }
}

/**
 * Extract download links from a post page.
 *
 * Aggregation sites typically list download buttons/links that point to file
 * hosts (HubCloud, GdFlix, FastDlServer, etc.). This function extracts those
 * links and returns them as OttSource objects.
 */
private fun extractDownloadLinks(doc: org.jsoup.nodes.Document, baseUrl: String): List<OttSource> {
    val sources = mutableListOf<OttSource>()

    // Common patterns for download links on aggregation sites
    val selectors = listOf(
        "a[href*='hubcloud']",
        "a[href*='gdflix']",
        "a[href*='gdlink']",
        "a[href*='fastdlserver']",
        "a[href*='linksmod']",
        "a[href*='sidexfee']",
        "a[href*='hubdrive']",
        "a[href*='hblinks']",
        "a.download",
        "a.btn-download",
        "div.download a",
        "div.entry-content a"
    )

    val seenUrls = mutableSetOf<String>()

    for (selector in selectors) {
        val links = doc.select(selector)
        for (link in links) {
            val href = link.attr("href")
            if (href.isNotBlank() && !seenUrls.contains(href)) {
                seenUrls.add(href)

                // Determine quality from nearby text or link text
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

    return sources
}

/**
 * Search all aggregation sites in parallel and combine results.
 *
 * Used in Stage 2 to find playable sources for a title discovered in Stage 1.
 * Each site is searched concurrently, and the results are combined into a single
 * list of OttSource objects.
 */
suspend fun searchAllAggregationSites(title: String, year: Int? = null): List<OttSource> {
    return coroutineScope {
        val jobs = listOf(
            async { scrapeSkyMoviesHD(title, year) },
            async { scrapeVegamovies(title, year) },
            async { scrapeCinevood(title, year) }
        )
        jobs.awaitAll().flatten()
    }
}

/**
 * Title matching and normalization.
 *
 * When searching aggregation sites for a title discovered on an OTT platform,
 * the titles may not match exactly. This function normalizes titles for
 * comparison: lowercase, remove year, remove special chars.
 */
fun normalizeTitle(title: String): String {
    return title
        .lowercase()
        .replace(Regex("""\(\d{4}\)"""), "") // Remove (year)
        .replace(Regex("""\d{4}"""), "") // Remove standalone year
        .replace(Regex("""[^a-z0-9\s]"""), "") // Remove special chars
        .replace(Regex("""\s+"""), " ") // Normalize whitespace
        .trim()
}

/**
 * Check if two titles match (fuzzy matching).
 *
 * Returns true if the normalized titles are similar enough to be considered the
 * same content. Uses simple substring matching for now.
 */
fun titlesMatch(title1: String, title2: String): Boolean {
    val norm1 = normalizeTitle(title1)
    val norm2 = normalizeTitle(title2)

    // Exact match after normalization
    if (norm1 == norm2) return true

    // Substring match (one contains the other)
    if (norm1.contains(norm2) || norm2.contains(norm1)) return true

    // Word overlap (at least 70% of words match)
    val words1 = norm1.split(" ").toSet()
    val words2 = norm2.split(" ").toSet()
    val overlap = words1.intersect(words2).size
    val total = (words1 + words2).size
    if (total > 0 && overlap.toFloat() / total >= 0.7f) return true

    return false
}
