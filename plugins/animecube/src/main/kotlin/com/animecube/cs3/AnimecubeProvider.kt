package com.animecube.cs3

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUrl
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * AnimeCube — donghua (Chinese animation) source. Site: https://animecube.live
 *
 * Discovery:
 *  - Next.js App Router site: listings come from the server-rendered RSC payload
 *    (there is no clean list API), plus the sitemap for search
 *
 * Metadata:
 *  - Detail page + episode list are regex-mined out of the RSC payload; episodes
 *    are `<slug>-tab-<T>-ep-<N>` slugs
 *
 * Streams:
 *  - A plaintext JSON API (`/api/anime/<slug>/episode/<epSlug>/sources`) returns
 *    `{platform, videoId, privateId}` for Dailymotion (the primary host) / Rumble.
 *    The per-season `v=` token it requires rotates and lives in the (plaintext)
 *    versions map. Both platforms resolve to a plain HLS m3u8.
 *
 * Playback:
 *  - Dailymotion videos are embed-restricted to animecube.live, so the GEO
 *    endpoint is used with the matching `embedder` (the public metadata API 403s).
 *    Master playlists are expanded via [M3u8Helper] and each media playlist is
 *    emitted directly (single host, no cross-host subtitle group) which mpv opens
 *    reliably. Chinese audio + subs only — there is no dub on this site.
 */
class AnimecubeProvider : MainAPI() {

    override var name = "AnimeCube"
    override var mainUrl = "https://animecube.live"
    override var lang = "zh"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("New Episodes", DATA_NEW_EPISODES),
        MainPageData("Top Rated", DATA_TOP_RATED),
        MainPageData("All Donghua", DATA_ALL)
    )

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == DATA_ALL) {
            // The app uses the first row as the hero carousel, so every row is
            // built from the same catalog, sliced with a different sort.
            val all = homeCards().map { it.toSearchResult() }
            if (all.isEmpty()) return null
            return newHomePageResponse(request.name, all)
        }

        val cards = homeCards()
        if (cards.isEmpty()) return null
        val items: List<SearchResponse> = when (request.data) {
            DATA_NEW_EPISODES -> cards
                .sortedByDescending { it.updatedAt }
                .take(ROW_SIZE)
            DATA_TOP_RATED -> cards
                .sortedByDescending { it.rating }
                .take(ROW_SIZE)
            else -> return null
        }.map { it.toSearchResult() }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        // The site has no search API, but its sitemap lists every title, so it is
        // fetched once (cached) and the query tokens are matched against the slugs.
        val q = query.lowercase().replace(NON_ALNUM, " ").trim()
        if (q.isEmpty()) return null
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        val hits = siteSlugs().filter { slug ->
            val hay = slug.replace('-', ' ')
            tokens.all { hay.contains(it) }
        }.take(MAX_SEARCH_RESULTS)
        return hits.map { slug ->
            newAnimeSearchResponse(titleFromSlug(slug), contentUrl(slug), TvType.Anime, fix = false)
        }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val slug = parseSlug(url) ?: return null
        val html = get("$mainUrl/anime/$slug", mainUrl + "/") ?: return null
        val rsc = rsc(html)

        val card = cards(rsc).firstOrNull { it.slug == slug }
        val eps = episodeSlugs(rsc, slug)
        if (eps.isEmpty()) return null

        val episodes: List<Episode> = eps.mapIndexed { index, parsed ->
            val number = index + 1
            newEpisode("$EPISODE_PREFIX${parsed.epSlug}", {
                this.name = "Episode $number"
                this.season = parsed.tab
                this.episode = parsed.ep
                this.posterUrl = card?.cover
            }, fix = false)
        }

        return newTvSeriesLoadResponse(
            card?.title ?: titleFromSlug(slug),
            url,
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = card?.cover
            this.backgroundPosterUrl = card?.cover
            this.year = card?.year
            this.tags = card?.genres.orEmpty()
            this.plot = "Donghua (Chinese animation) — subbed."
        }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith(EPISODE_PREFIX)) return false
        // The id is self-contained: the episode slug is "<series>-tab-<T>-ep-<N>".
        val epSlug = data.substring(EPISODE_PREFIX.length)
        val match = EPISODE_ID_PATTERN.matchEntire(epSlug) ?: return false
        val slug = match.groupValues[1]
        val seasonId = "tab-${match.groupValues[2]}"

        // The per-season `v=` token rotates; it lives in the versions map.
        val registry = parseJson<VersionsRegistry>(
            get("$mainUrl/api/anime-sources-versions", "$mainUrl/anime/$slug")
        ) ?: return false
        val byPrimary = registry.bySeason[slug].orEmpty()
        var primaryId: String? = null
        var token: String? = null
        for ((id, seasons) in byPrimary) {
            val value = seasons[seasonId]
            if (value != null) {
                primaryId = id
                token = value
                break
            }
        }
        if (token.isNullOrEmpty()) return false

        val sourcesUrl = "$mainUrl/api/anime/$slug/episode/$epSlug/sources" +
            "?v=${token.encodeUrl()}" +
            "&primaryTabId=${primaryId.orEmpty().encodeUrl()}" +
            "&seasonId=${seasonId.encodeUrl()}"
        val sources = parseJson<SourcesResponse>(
            get(sourcesUrl, "$mainUrl/anime/$slug")
        )?.sources.orEmpty()

        var emitted = 0
        for (source in sources) {
            val platform = source.platform ?: continue
            val links = when {
                // Dailymotion videos are embed-restricted to animecube.live; the
                // GEO endpoint serves the HLS manifest with the matching embedder.
                platform == "dailymotion" && !(source.privateId.isNullOrEmpty() &&
                        source.videoId.isNullOrEmpty()) ->
                    dailymotionLinks(source.privateId ?: source.videoId!!, source.quality, slug)

                platform == "rumble" && !source.videoId.isNullOrEmpty() ->
                    rumbleLinks(source.videoId, source.quality)

                else -> emptyList()
            }
            for (link in links) {
                callback(link)
                emitted++
            }
            for (track in source.subtitles) {
                val subUrl = track.url ?: continue
                val subLang = track.lang?.takeIf { it.isNotBlank() } ?: "zh"
                val subReferer =
                    if (platform == "dailymotion") DM_REFERER else RUMBLE_REFERER
                subtitleCallback(
                    newSubtitleFile(subLang, subUrl) {
                        this.headers = mapOf("User-Agent" to UA, "Referer" to subReferer)
                    }
                )
            }
        }
        return emitted > 0
    }

    // ---------------------------------------------------------------- hosts

    private suspend fun get(url: String, referer: String): String? =
        runCatching {
            val response = app.get(
                url = url,
                headers = mapOf("User-Agent" to UA, "Referer" to referer)
            )
            response.text
        }.getOrNull()

    private suspend inline fun <reified T : Any> getJson(url: String, referer: String): T? =
        runCatching { AppUtils.parseJson<T>(get(url, referer).orEmpty()) }.getOrNull()

    private suspend inline fun <reified T : Any> parseJson(body: String?): T? =
        if (body.isNullOrEmpty()) null
        else runCatching { AppUtils.parseJson<T>(body) }.getOrNull()

    // -------------------------------------------------------- RSC extraction

    /**
     * The page data lives in `self.__next_f.push([1,"<escaped>"])` script chunks.
     * Concatenate them and lightly un-escape so the embedded JSON can be regex-mined.
     */
    private fun rsc(html: String): String =
        RSC_CHUNK.findAll(html)
            .map { it.groupValues[1] }
            .joinToString("")
            .replace(UNICODE_ESCAPE) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    private fun field(obj: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\":\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(obj)?.groupValues?.get(1)

    private data class Card(
        val slug: String,
        val title: String,
        val cover: String?,
        val genres: List<String>,
        val year: Int?,
        val rating: Double,
        val updatedAt: String
    )

    /** Catalog card -> a search/home-page row pointing at the detail page. */
    private fun Card.toSearchResult(): SearchResponse =
        newAnimeSearchResponse(title, contentUrl(slug), TvType.Anime, fix = false) {
            posterUrl = cover
            this.year = this@toSearchResult.year
        }

    /**
     * Pull every anime card object ({slug,title,coverImage,...}) out of an RSC blob.
     * Anime objects carry "slug" + "coverImage"; they contain only string/array
     * values (no nested objects), so a brace-free match is safe.
     */
    private fun cards(rsc: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (match in CARD.findAll(rsc)) {
            val obj = match.value
            val slug = field(obj, "slug") ?: continue
            if (slug.isEmpty() || slug in seen) continue
            if (!obj.contains("coverImage") && !obj.contains("title")) continue
            seen.add(slug)
            val genres = GENRES.find(obj)?.groupValues?.get(1)
                ?.let { GENRE_ITEM.findAll(it).map { m -> m.groupValues[1] }.toList() }
                .orEmpty()
            val year = field(obj, "year")?.toIntOrNull()
            out.add(
                Card(
                    slug = slug,
                    title = field(obj, "title") ?: titleFromSlug(slug),
                    cover = field(obj, "coverImage"),
                    genres = genres,
                    year = year,
                    rating = RATING.find(obj)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                    updatedAt = UPDATED_AT.find(obj)?.groupValues?.get(1).orEmpty()
                )
            )
        }
        return out
    }

    private suspend fun homeCards(): List<Card> =
        cards(rsc(get("$mainUrl/", mainUrl + "/").orEmpty()))

    private fun titleFromSlug(slug: String): String =
        slug.replace('-', ' ').replace(LOWER_LETTER) { it.value.uppercase() }

    // --------------------------------------------------------------- sitemap

    private var cachedSlugs: List<String>? = null

    private suspend fun siteSlugs(): List<String> {
        cachedSlugs?.let { return it }
        val xml = get("$mainUrl/sitemap.xml", mainUrl + "/").orEmpty()
        val slugs = LinkedHashSet<String>()
        for (match in SITEMAP.findAll(xml)) slugs.add(match.groupValues[1])
        val list = slugs.toList()
        if (list.isNotEmpty()) cachedSlugs = list
        return list
    }

    // ----------------------------------------------------------------- urls

    private fun contentUrl(slug: String): String = "$mainUrl/anime/$slug"

    private fun parseSlug(url: String): String? {
        val tail = url.substringAfter("$mainUrl/anime/", "")
        val slug = tail.split('?')[0].trim('/')
        return slug.takeIf { SLUG.matches(it) }
    }

    // -------------------------------------------------------------- streams

    /**
     * Dailymotion: the site's primary host. Videos are restricted to the
     * animecube.live embedder, so the public metadata API 403s (DM010); the GEO
     * endpoint with the matching `embedder` returns the HLS manifest. The
     * `privateId` (the embed key) is used as the video id.
     */
    private suspend fun dailymotionLinks(
        privateId: String,
        quality: String?,
        slug: String
    ): List<ExtractorLink> {
        if (privateId.isEmpty()) return emptyList()
        val url = "https://geo.dailymotion.com/video/${privateId.encodeUrl()}" +
            ".json?legacy=true&embedder=${("$mainUrl/anime/$slug").encodeUrl()}"
        val hdr = mapOf("User-Agent" to UA, "Referer" to DM_REFERER)
        val payload = getJson<DailymotionGeo>(url, DM_REFERER) ?: return emptyList()
        if (payload.error != null) return emptyList()
        val master = payload.qualities?.auto?.firstOrNull()?.url ?: return emptyList()

        // The player cannot open Dailymotion's MASTER playlist: it carries a
        // cross-host subtitle group (www.dailymotion.com) + fMP4 variants, and the
        // open fails ("Failed to open"). So the master is expanded here and each
        // variant's media playlist is emitted DIRECTLY — a single host, no
        // subtitle group, the clean shape mpv plays reliably.
        //
        // Renditions above 1080p are skipped (they would default to 4K —
        // needlessly heavy for donghua) but 1080p is kept as the default.
        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = master,
                referer = DM_REFERER,
                quality = qualityValue(quality),
                headers = hdr,
                name = "Dailymotion ${quality.orEmpty()}".trim()
            )
        }.getOrDefault(emptyList())
        val variants = generated.filter { it.url != master && it.quality <= MAX_HEIGHT }
        if (variants.isNotEmpty()) return variants.sortedByDescending { it.quality }

        // Master had no parseable variants — fall back to the master itself.
        return listOf(
            newExtractorLink(
                source = name,
                name = "Dailymotion ${quality ?: "auto"}".trim(),
                url = master,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = DM_REFERER
                this.quality = qualityValue(quality)
                this.headers = hdr
            }
        )
    }

    private suspend fun rumbleLinks(videoId: String, quality: String?): List<ExtractorLink> {
        val url = "https://rumble.com/embedJS/u3/?request=video&ver=2&v=${videoId.encodeUrl()}"
        val payload = getJson<RumbleEmbed>(url, "$mainUrl/") ?: return emptyList()
        val ua = payload.ua ?: return emptyList()
        val hdr = mapOf("User-Agent" to UA, "Referer" to RUMBLE_REFERER)
        val out = ArrayList<ExtractorLink>()

        ua.hls?.auto?.url?.let { hls ->
            out += runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = hls,
                    referer = RUMBLE_REFERER,
                    quality = qualityValue(quality),
                    headers = hdr,
                    name = "Rumble ${quality ?: "auto"}"
                )
            }.getOrDefault(emptyList())
        }

        // Progressive mp4 renditions as a fallback / explicit qualities.
        for ((label, mp4) in ua.mp4.orEmpty()) {
            val mp4Url = mp4.url ?: continue
            out += newExtractorLink(
                source = name,
                name = if (label.all { it.isDigit() }) "${label}p" else label,
                url = mp4Url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = RUMBLE_REFERER
                this.quality = qualityValue(label)
                this.headers = hdr
            }
        }
        return out
    }

    private fun qualityValue(label: String?): Int =
        label?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value

    // ------------------------------------------------------------------ api

    @Serializable
    private data class VersionsRegistry(
        @SerialName("bySeason") val bySeason: Map<String, Map<String, Map<String, String>>> =
            emptyMap()
    )

    @Serializable
    private data class SourcesResponse(
        @SerialName("sources") val sources: List<SourceEntry> = emptyList()
    )

    @Serializable
    private data class SourceEntry(
        @SerialName("platform") val platform: String? = null,
        @SerialName("videoId") val videoId: String? = null,
        @SerialName("privateId") val privateId: String? = null,
        @SerialName("quality") val quality: String? = null,
        @SerialName("subtitles") val subtitles: List<SourceSubtitle> = emptyList()
    )

    @Serializable
    private data class SourceSubtitle(
        @SerialName("lang") val lang: String? = null,
        @SerialName("url") val url: String? = null
    )

    @Serializable
    private data class DailymotionGeo(
        @SerialName("error") val error: JsonElement? = null,
        @SerialName("qualities") val qualities: DailymotionQualities? = null
    )

    @Serializable
    private data class DailymotionQualities(
        @SerialName("auto") val auto: List<DailymotionVariant> = emptyList()
    )

    @Serializable
    private data class DailymotionVariant(
        @SerialName("url") val url: String? = null
    )

    @Serializable
    private data class RumbleEmbed(
        @SerialName("ua") val ua: RumbleUa? = null
    )

    @Serializable
    private data class RumbleUa(
        @SerialName("hls") val hls: RumbleHls? = null,
        @SerialName("mp4") val mp4: Map<String, RumbleMp4>? = null
    )

    @Serializable
    private data class RumbleHls(
        @SerialName("auto") val auto: RumbleAuto? = null
    )

    @Serializable
    private data class RumbleAuto(
        @SerialName("url") val url: String? = null
    )

    @Serializable
    private data class RumbleMp4(
        @SerialName("url") val url: String? = null
    )

    // -------------------------------------------------------------- episodes

    private data class ParsedEpisode(val epSlug: String, val tab: Int, val ep: Int)

    private fun episodeSlugs(rsc: String, slug: String): List<ParsedEpisode> {
        val pattern = Regex("\"" + Regex.escape(slug) + "-tab-(\\d+)-ep-(\\d+)\"")
        val seen = HashSet<String>()
        val eps = ArrayList<ParsedEpisode>()
        for (match in pattern.findAll(rsc)) {
            val epSlug = match.value.trim('"')
            if (!seen.add(epSlug)) continue
            eps.add(ParsedEpisode(epSlug, match.groupValues[1].toInt(), match.groupValues[2].toInt()))
        }
        return eps.sortedWith(compareBy({ it.tab }, { it.ep }))
    }

    private companion object {
        const val DATA_NEW_EPISODES = "new-episodes"
        const val DATA_TOP_RATED = "top-rated"
        const val DATA_ALL = "all-donghua"

        const val EPISODE_PREFIX = "animecube|"

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val DM_REFERER = "https://geo.dailymotion.com/"
        const val RUMBLE_REFERER = "https://rumble.com/"

        const val ROW_SIZE = 24
        const val MAX_SEARCH_RESULTS = 30
        const val MAX_HEIGHT = 1080

        val RSC_CHUNK = Regex("""self\.__next_f\.push\(\[1,"((?:\\.|[^"\\])*)"\]\)""")
        val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
        val CARD = Regex("""\{[^{}]*?"slug":"[a-z0-9\-]+"[^{}]*?\}""")
        val GENRES = Regex(""""genres":\[([^\]]*)]""")
        val GENRE_ITEM = Regex(""""([^"]+)"""")
        val RATING = Regex(""""rating":\s*([0-9.]+)""")
        val UPDATED_AT =
            Regex(""""(?:lastEpisodeAddedAt|latestEpisodePublishedAt|updatedAt)":"([^"]+)"""")
        val SITEMAP = Regex("""/anime/([a-z0-9\-]+)""")
        val SLUG = Regex("""[a-z0-9\-]+""")
        val NON_ALNUM = Regex("""[^a-z0-9]+""")
        val LOWER_LETTER = Regex("""\b\w""")
        val EPISODE_ID_PATTERN = Regex("""(.*)-tab-(\d+)-ep-(\d+)""")
    }
}
