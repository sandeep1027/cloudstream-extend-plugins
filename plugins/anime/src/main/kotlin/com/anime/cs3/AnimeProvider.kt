package com.anime.cs3

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Anime — an anime content provider with AniList integration.
 *
 * Discovery:
 *  - Trending, Popular, and Currently Airing rows from AniList
 *  - Search through AniList's GraphQL API
 *
 * Metadata:
 *  - Full anime details from AniList (descriptions, genres, studios, etc.)
 *  - Episode lists from AniList
 *
 * Streams:
 *  - This plugin emits placeholder links. Actual stream resolution depends on
 *    the user's installed extensions and debrid services.
 */
class AnimeProvider : MainAPI() {

    override var name = "Anime"
    override var mainUrl = "https://anilist.co"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)
    override val providerType = ProviderType.MetaProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Trending Anime", DATA_TRENDING),
        MainPageData("Popular Anime", DATA_POPULAR),
        MainPageData("Currently Airing", DATA_AIRING),
    )

    // ─── Main Page ───────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val query = when (request.data) {
            DATA_TRENDING -> AniList.TRENDING_QUERY
            DATA_POPULAR -> AniList.POPULAR_QUERY
            DATA_AIRING -> AniList.AIRING_QUERY
            else -> return null
        }

        val response = fetchAniList(query, mapOf("page" to page, "perPage" to PAGE_SIZE))
        val media = response?.data?.Page?.media ?: return null
        if (media.isEmpty()) return null

        val items = media.mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) return null

        return newHomePageResponse(request.name, items)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null

        val response = fetchAniList(
            AniList.SEARCH_QUERY,
            mapOf("search" to q, "page" to 1, "perPage" to MAX_SEARCH_RESULTS)
        )
        val media = response?.data?.Page?.media ?: return null
        return media.mapNotNull { it.toSearchResult() }
    }

    // ─── Load ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = parseAniListUrl(url) ?: return null

        val response = fetchAniList(
            AniList.MEDIA_QUERY,
            mapOf("id" to anilistId)
        )
        val media = response?.data?.Media ?: return null

        val title = media.title?.getPreferred() ?: return null
        val year = media.seasonYear
        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
        val banner = media.bannerImage
        val plot = media.description?.stripHtml()
        val episodes = media.episodes ?: 1
        val genres = media.genres
        val studio = media.studios?.nodes?.firstOrNull()?.name

        // Build episode list
        val episodeList = (1..episodes).map { epNum ->
            newEpisode("$anilistId|$epNum") {
                this.name = "Episode $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodeList) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = genres
            this.comingSoon = media.status == "NOT_YET_RELEASED"
            addActors(studio?.let { listOf(it) } ?: emptyList())
        }
    }

    // ─── Load Links ──────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse the data: "anilistId|episodeNum"
        val parts = data.split("|")
        val anilistId = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: return false

        // For now, emit a placeholder that indicates this episode needs external resolution.
        // In a full implementation, this would query torrent/anime streaming sources.
        // The user's debrid services (Torrin/TorBox/Real-Debrid) would handle actual playback
        // if the extension provides magnet links.

        // This is a meta-provider, so we indicate that playback requires external sources.
        // The actual stream resolution depends on other installed extensions.

        return false // No direct links - requires external extension
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun AniList.MediaData.toSearchResult(): SearchResponse? {
        val title = this.title?.getPreferred() ?: return null
        val year = this.seasonYear
        val poster = this.coverImage?.extraLarge ?: this.coverImage?.large
        val episodes = this.episodes ?: 0
        val isOngoing = this.status == "RELEASING"

        val url = "$mainUrl/anime/${this.id}"

        return if (episodes <= 1 && !isOngoing) {
            // Movie
            newMovieSearchResponse(title, url) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun fetchAniList(query: String, variables: Map<String, Any>): AniList.AniListResponse? {
        return try {
            val body = mapOf(
                "query" to query,
                "variables" to variables
            )
            val response = app.post(
                url = AniList.API_URL,
                json = body,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                )
            )
            if (!response.isSuccessful) return null
            AppUtils.parseJson<AniList.AniListResponse>(response.text)
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseAniListUrl(url: String): Int? {
        // URL format: https://anilist.co/anime/12345/...
        val match = Regex("/anime/(\\d+)").find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.stripHtml(): String {
        return this
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .trim()
    }

    private companion object {
        const val DATA_TRENDING = "trending"
        const val DATA_POPULAR = "popular"
        const val DATA_AIRING = "airing"
        const val PAGE_SIZE = 20
        const val MAX_SEARCH_RESULTS = 20
    }
}
