package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink

private const val TAG = "OttAggregator"

/**
 * OTT Metadata Aggregator — focused on SkyMoviesHD (skymovieshd.band)
 *
 * Scrapes SkyMoviesHD for content listings and download links, then resolves
 * those links using bypass logic (HubCloud, GdFlix, etc.).
 */
class OttAggregatorProvider : MainAPI() {
    override var name = "OttAggregator"
    override var mainUrl = "https://skymovieshd.band"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    init {
        kotlinx.coroutines.runBlocking {
            mainUrl = OttDomains.current("skymovieshd")
        }
    }

    override val mainPage = mainPageOf(
        "" to "Home",
        "/category/bollywood-movies/" to "Bollywood",
        "/category/hollywood-english-movies/" to "Hollywood English",
        "/category/hollywood-hindi-dubbed-movies/" to "Hollywood Hindi",
        "/category/south-indian-hindi-dubbed-movies/" to "South Hindi Dubbed",
        "/category/web-series/" to "Web Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (page > 1) return null

        Log.d(TAG, "getMainPage: ${request.name} (${request.data})")

        val items = if (request.data.isBlank()) {
            scrapeSkyMoviesHDHome()
        } else {
            scrapeSkyMoviesHDCategory(request.data)
        }

        if (items.isEmpty()) return null

        val searchResponses = items.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null

        Log.d(TAG, "search: query=$query")

        val items = searchSkyMoviesHD(query)

        if (items.isEmpty()) {
            Log.d(TAG, "search: no results")
            return null
        }

        Log.d(TAG, "search: found ${items.size} items")
        val results = items.map { it.toSearchResponse() }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")

        // Extract download links from the movie detail page
        val sources = extractSkyMoviesHDDownloadLinks(url)

        if (sources.isEmpty()) {
            Log.w(TAG, "load: no download links found")
            return null
        }

        Log.d(TAG, "load: found ${sources.size} download sources")

        // Extract title from URL or page
        val title = url.substringAfterLast("/movie/")
            .substringBefore(".html")
            .replace("-", " ")
            .trim()

        // Try to get poster from the page
        val doc = app.get(url).document
        val poster = doc.selectFirst("img[src*=http]")?.attr("src")

        val dataJson = sources.toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = poster
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

        emitOttSources(sources, subtitleCallback, callback)

        return true
    }

    private fun SkyMovieItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
