package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "OttAggregator"

/**
 * Generic OpenGraph metadata extractor.
 *
 * Works across OTT platforms and aggregation sites by extracting og:title,
 * og:image, og:description from any URL. Falls back to HTML title and first
 * image if OpenGraph tags are missing.
 *
 * This is the foundation for Stage 1 (metadata discovery): OTT platforms expose
 * rich metadata via OpenGraph tags, so a single generic extractor serves all
 * platforms without platform-specific code.
 */
suspend fun extractOpenGraph(url: String): OpenGraphData? {
    return try {
        Log.d(TAG, "extractOpenGraph: fetching $url")
        val doc = app.get(url).document

        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.select("meta[name=twitter:title]").attr("content") }
            .ifBlank { doc.select("title").text() }

        val image = doc.select("meta[property=og:image]").attr("content")
            .ifBlank { doc.select("meta[name=twitter:image]").attr("content") }
            .ifBlank { doc.select("meta[property=og:image:url]").attr("content") }

        val description = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=twitter:description]").attr("content") }
            .ifBlank { doc.select("meta[name=description]").attr("content") }

        val ogUrl = doc.select("meta[property=og:url]").attr("content")

        Log.d(TAG, "extractOpenGraph: title='$title', image='${image.take(50)}...'")

        if (title.isBlank()) {
            Log.w(TAG, "extractOpenGraph: no title found for $url")
            null
        } else {
            OpenGraphData(
                title = title,
                image = image,
                description = description,
                url = ogUrl.ifBlank { url }
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "extractOpenGraph failed for $url: ${e.message}")
        null
    }
}

/**
 * Extract metadata from multiple URLs in parallel.
 *
 * Used when scanning an OTT platform's browse page for content listings: each
 * link is fetched concurrently, and the results are combined. Failures are
 * logged but do not stop the batch.
 */
suspend fun extractOpenGraphBatch(urls: List<String>): List<OpenGraphData> {
    return supervisorScope {
        urls.map { url ->
            async {
                extractOpenGraph(url)
            }
        }.awaitAll().filterNotNull()
    }
}
