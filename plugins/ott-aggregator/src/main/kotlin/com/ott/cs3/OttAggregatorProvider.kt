package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "OttAggregator"

/**
 * OTT Metadata Aggregator — combines metadata from OTT platforms with playable
 * sources from aggregation sites.
 *
 * Two-stage flow:
 * 1. Stage 1 (Metadata Discovery): Scrape OTT platforms (Prime Video, ZEE5,
 *    AirtelXstream, AppleTV+) for content listings and metadata via OpenGraph.
 * 2. Stage 2 (Link Resolution): For each title, search aggregation sites
 *    (SkyMoviesHD, Vegamovies, Cinevood) for matching content with download
 *    links, then resolve those links using bypass logic.
 *
 * This provides rich metadata from official OTT platforms while finding playable
 * sources from aggregation sites that already have bypass-able links.
 */
class OttAggregatorProvider : MainAPI() {
    override var name = "OttAggregator"
    override var mainUrl = "https://www.primevideo.com" // Default, overridden by domains.json
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    init {
        // Read the current domain from domains.json at load time.
        kotlinx.coroutines.runBlocking {
            mainUrl = OttDomains.current("primevideo")
        }
    }

    override val mainPage = mainPageOf(
        "" to "Home",
        "primevideo" to "Prime Video",
        "zee5" to "ZEE5",
        "airtelxstream" to "AirtelXstream",
        "appletv" to "AppleTV+"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (page > 1) return null // Single-slice rows

        Log.d(TAG, "getMainPage: ${request.name} (${request.data})")

        val items = when (request.data) {
            "primevideo" -> scrapePrimeVideo()
            "zee5" -> scrapeZee5()
            "airtelxstream" -> scrapeAirtelXstream()
            "appletv" -> scrapeAppleTV()
            else -> {
                // Home: combine all platforms
                supervisorScope {
                    val jobs = listOf(
                        async { scrapePrimeVideo() },
                        async { scrapeZee5() },
                        async { scrapeAirtelXstream() },
                        async { scrapeAppleTV() }
                    )
                    jobs.awaitAll().flatten()
                }
            }
        }

        if (items.isEmpty()) return null

        val searchResponses = items.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null // Single-slice results

        Log.d(TAG, "search: query=$query")

        // Search all OTT platforms in parallel
        val items = searchAllOttPlatforms(query)

        if (items.isEmpty()) {
            Log.d(TAG, "search: no results from OTT platforms")
            return null
        }

        Log.d(TAG, "search: found ${items.size} items")
        val results = items.map { it.toSearchResponse() }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")

        // Stage 1: Extract metadata from OTT platform
        val metadata = extractOpenGraph(url)
        if (metadata == null) {
            Log.e(TAG, "load: failed to extract metadata from $url")
            return null
        }

        Log.d(TAG, "load: title='${metadata.title}', poster='${metadata.image.take(50)}...'")

        val title = metadata.title
        val posterUrl = metadata.image
        val description = metadata.description
        val isSeries = url.contains("series", ignoreCase = true) ||
                       url.contains("tv-shows", ignoreCase = true) ||
                       url.contains("title", ignoreCase = true)

        // Extract year from title or URL if possible
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{4})""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        // Stage 2: Search aggregation sites for playable sources
        Log.d(TAG, "load: searching aggregation sites for '$title'")
        val sources = searchAllAggregationSites(title, year)

        if (sources.isEmpty()) {
            Log.w(TAG, "load: no playable sources found for '$title'")
            // Return metadata-only response (user can still see the poster/description)
            return if (isSeries) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, "") {
                    this.posterUrl = posterUrl
                    this.plot = description
                }
            }
        }

        Log.d(TAG, "load: found ${sources.size} playable sources")

        // Pass sources as JSON data to loadLinks
        val dataJson = sources.toJson()

        return if (isSeries) {
            // For series, create a single "episode" with all sources
            val episodes = listOf(
                newEpisode(dataJson) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = posterUrl
                }
            )
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data length=${data.length}")

        if (data.isBlank()) {
            Log.w(TAG, "loadLinks: no data provided")
            return false
        }

        val sources = try {
            parseJson<List<OttSource>>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: failed to parse sources: ${e.message}")
            emptyList()
        }

        if (sources.isEmpty()) {
            Log.w(TAG, "loadLinks: no sources to process")
            return false
        }

        Log.d(TAG, "loadLinks: processing ${sources.size} sources")

        // Dispatch sources to bypass extractors
        emitOttSources(sources, subtitleCallback, callback)

        return true
    }

    // Helper extensions

    private fun ContentItem.toSearchResponse(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }
}
