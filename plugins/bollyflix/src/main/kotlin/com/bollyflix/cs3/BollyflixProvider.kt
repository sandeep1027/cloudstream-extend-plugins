package com.bollyflix.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.base64Decode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

private const val TAG = "Bollyflix"

/**
 * BollyFlix — https://bollyflix.frl (or current domain from domains.json)
 *
 * Discovery and playback both use HTML scraping. The site sits behind Cloudflare,
 * but the app's HTTP stack handles the challenge automatically (unlike plain curl
 * or a kotlin-jvm plugin trying to use CloudflareKiller directly).
 *
 * Mirror buttons are unlocked via the sidexfee bypass, then dispatched to the
 * matching extractor (GdFlix, FastDlServer, or the app's generic loader).
 */
class BollyflixProvider : MainAPI() {
    override var mainUrl = "https://bollyflix.frl"
    override var name = "BollyFlix"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    init {
        // Read the current domain from domains.json at load time.
        kotlinx.coroutines.runBlocking {
            mainUrl = BollyflixDomains.current()
        }
    }

    override val mainPage = mainPageOf(
        "" to "Home",
        "/movies/bollywood/" to "Bollywood Movies",
        "/movies/hollywood/" to "Hollywood Movies",
        "/movies/south-hindi-dubbed/" to "South (Hindi Dubbed)",
        "/dual-audio/" to "Dual Audio",
        "/genre/18/" to "[18+] Movies",
        "/web-series/" to "WEB Series",
        "/anime/" to "Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("div.post-cards > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun bypass(id: String): String {
        val url = "https://web.sidexfee.com/?id=$id"
        val document = app.get(url).text
        val encodeUrl = Regex("""link":"([^"]+)""").find(document)?.groupValues?.get(1) ?: ""
        return base64Decode(encodeUrl.replace("\\/", "/"))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("a").attr("title").replace("Download ", "")
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/search/$query/page/$page/"
        val document = app.get(url).document
        val results = document.select("div.post-cards > article").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load() called with url: $url")
        val document = app.get(url).document
        Log.d(TAG, "load() fetched document, size: ${document.html().length}")
        val title = document.selectFirst("title")?.text()?.replace("Download ", "").orEmpty()
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("span#summary")?.text()
        Log.d(TAG, "load() title: $title")

        // Detect series vs movie from title or URL.
        val isSeries = title.contains("Series", ignoreCase = true) ||
            url.contains("web-series", ignoreCase = true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val episodesMap = mutableMapOf<Pair<Int, Int>, MutableList<String>>()

            // Find all download buttons (season packs).
            val buttons = document.select("a.maxbutton-download-links, a.dl, a.btnn")

            supervisorScope {
                buttons.map { button ->
                    async {
                        try {
                            var link = button.attr("href")

                            // Unlock if needed.
                            if (!link.contains("fastdlserver")) {
                                val id = link.substringAfterLast("id=")
                                link = bypass(id)
                            }

                            // Extract season number from nearby text.
                            val seasonText = button.parent()?.previousElementSibling()?.text().orEmpty()
                            val season = Regex("""(?:Season |S)(\d+)""").find(seasonText)
                                ?.groupValues?.get(1)?.toIntOrNull() ?: 1

                            // Fetch the pack page and extract episode links.
                            val seasonDoc = app.get(link).document
                            val epLinks = seasonDoc.select("h3 > a")
                                .filter { !it.text().contains("Zip", ignoreCase = true) }

                            synchronized(episodesMap) {
                                epLinks.forEachIndexed { index, epLink ->
                                    val epUrl = epLink.attr("href")
                                    val key = Pair(season, index + 1)
                                    episodesMap.getOrPut(key) { mutableListOf() }.add(epUrl)
                                }
                            }
                        } catch (e: Exception) {
                            // Skip this pack on error.
                        }
                    }
                }.awaitAll()
            }

            // Build episodes from the map.
            for ((key, urls) in episodesMap) {
                val (season, episode) = key
                val data = urls.map { BollyflixSource(it) }
                episodes.add(
                    newEpisode(data) {
                        this.name = "Episode $episode"
                        this.season = season
                        this.episode = episode
                        this.posterUrl = posterUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        } else {
            // Movie: unlock all mirrors and pass as data.
            Log.d(TAG, "load() processing as movie")
            val dlButtons = document.select("a.dl")
            Log.d(TAG, "load() found ${dlButtons.size} a.dl buttons")
            val data = supervisorScope {
                dlButtons.map { link ->
                    async {
                        try {
                            var decodeUrl = link.attr("href")
                            Log.d(TAG, "load() button href: $decodeUrl")
                            // Only bypass if the URL has ?id= (sidexfee redirector)
                            if (!decodeUrl.contains("fastdlserver") && decodeUrl.contains("?id=")) {
                                val id = decodeUrl.substringAfterLast("id=")
                                Log.d(TAG, "load() bypassing id: $id")
                                decodeUrl = bypass(id)
                                Log.d(TAG, "load() bypassed to: $decodeUrl")
                            } else {
                                Log.d(TAG, "load() using direct URL (no bypass needed)")
                            }
                            BollyflixSource(decodeUrl)
                        } catch (e: Exception) {
                            Log.e(TAG, "load() button failed: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            Log.d(TAG, "load() created ${data.size} BollyflixSource entries")

            return newMovieLoadResponse(title, url, TvType.Movie, data) {
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
        Log.d(TAG, "loadLinks() called with data length: ${data.length}")
        val sources = parseJson<List<BollyflixSource>>(data)
        Log.d(TAG, "loadLinks() parsed ${sources.size} sources")
        if (sources.isEmpty()) return false
        sources.forEachIndexed { i, src -> Log.d(TAG, "loadLinks() source[$i]: ${src.url}") }
        emitBollyflixSources(sources.map { it.url }, subtitleCallback, callback)
        return true
    }
}
