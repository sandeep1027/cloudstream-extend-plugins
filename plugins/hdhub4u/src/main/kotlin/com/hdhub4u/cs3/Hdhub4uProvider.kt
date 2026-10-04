package com.hdhub4u.cs3

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.metaproviders.tmdbApiKeyOverride
import com.lagradost.cloudstream3.metaproviders.tmdbLanguageOverride
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ShortLink
import com.lagradost.cloudstream3.utils.StringUtils
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ── api payloads ────────────────────────────────────────────────────────────

/** HDHub4u's own search proxy (a Typesense collection) — no key, UA + referer only. */
@Serializable
private data class TypesenseResponse(
    val hits: List<TypesenseHit> = emptyList(),
    val found: Int? = null
)

@Serializable
private data class TypesenseHit(val document: TypesenseDocument? = null)

@Serializable
private data class TypesenseDocument(
    val permalink: String? = null,
    val url: String? = null,
    val post_title: String? = null,
    val title: String? = null,
    val post_thumbnail: String? = null,
    val feature_img: String? = null,
    val image: String? = null
)

/** Payload of the `?id=` redirect hop: base64 fields, one of which is the target. */
@Serializable
private data class RedirectPayload(
    val o: String? = null,
    val data: String? = null,
    val blog_url: String? = null
)

@Serializable
private data class TmdbSearchResponse(val results: List<TmdbSearchItem> = emptyList())

@Serializable
private data class TmdbSearchItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

@Serializable
private data class TmdbDetails(
    val overview: String? = null,
    val poster_path: String? = null,
    val genres: List<TmdbGenre> = emptyList()
)

@Serializable
private data class TmdbGenre(val name: String? = null)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private data class TmdbEpisode(
    val episode_number: Int = 0,
    val name: String? = null,
    val still_path: String? = null
)

@Serializable
private data class TmdbExternalIds(val imdb_id: String? = null)

// ── parsed shapes ───────────────────────────────────────────────────────────
//
// Nested inside the provider: the plugin package is shared with the other
// one plugin can declare several providers, so file-private top level names are not private enough.

/**
 * HDHub4u — Hindi movie / web-series downloads, self-contained stream chain.
 *
 * The site rotates its domain, so the live one is read from a community list and
 * only falls back to a baked-in constant. The catalogue is plain HTML scraped by
 * regex; search goes through the site's own Typesense proxy.
 *
 * Playback is a chain of hops, all of which the provider walks itself rather
 * than handing a landing page to the player:
 *
 *   page link → optional `?id=` redirect resolver (triple-base64 + ROT13)
 *             → hblinks / hubdrive / hubcloud
 *             → direct files (FSL / S3 / Pixeldrain / Buzz / 10Gbps), hdstream4u
 *             and hubstream (the latter an AES-CBC blob decrypted here to an m3u8)
 *
 * A hop that cannot be resolved contributes nothing instead of a broken link —
 * whatever resolved cleanly is offered, and if nothing did the episode reports
 * as unplayable rather than pretending otherwise.
 */
class Hdhub4uProvider : MainAPI() {

    /** One catalogue card: a title, its permalink and its poster. */
    private data class Card(val title: String, val url: String, val cover: String?)

    /**
     * One playable unit. A movie carries every download link of the page in a
     * single entry; a series splits them per "Episode N" heading.
     */
    private data class EpisodeRef(
        val number: Int,
        val title: String,
        val hrefs: List<String>,
        val thumbnail: String? = null
    )

    private data class Detail(
        val title: String,
        val poster: String?,
        val plot: String?,
        val year: Int?,
        val genres: List<String>,
        val isSeries: Boolean,
        val episodes: List<EpisodeRef>
    )

    /** A detail plus the IMDb id TMDB resolved for it — the tracking handle. */
    private data class Enriched(val detail: Detail, val imdbId: String? = null)

    private data class SubTrack(val lang: String, val url: String)

    /** One playable file, ready to be handed to the player. */
    private data class ResolvedStream(
        val url: String,
        val label: String,
        val quality: Int?,
        val referer: String,
        val headers: Map<String, String>,
        val subtitles: List<SubTrack> = emptyList()
    )

    /** The release metadata HubCloud prints above its server buttons. */
    private data class HubFileInfo(
        val tags: String,
        val size: String,
        val quality: Int?,
        val pixeldrain: String
    )

    override var name = "HDHub4u"
    override var mainUrl = FALLBACK_DOMAIN
    override var lang = "hi"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Latest", "/"),
        MainPageData("Bollywood", "/category/bollywood-movies/"),
        MainPageData("Hollywood", "/category/hollywood-movies/"),
        MainPageData("Hindi Dubbed", "/category/hindi-dubbed/"),
        MainPageData("Web Series", "/category/web-series/")
    )

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Every row is one category listing, which the site does not paginate.
        if (page > 1) return null
        val items = rowCards(request.data).map { it.toSearchResponse() }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    /** The site's live domain, refreshed hourly — it changes without warning. */
    private suspend fun domain(): String {
        synchronized(cacheLock) {
            val hit = domainCache
            if (hit != null && System.currentTimeMillis() - hit.first < DOMAIN_TTL_MS) {
                return hit.second
            }
        }
        val live = runCatching {
            val response = app.get(url = DOMAINS_URL, headers = mapOf("User-Agent" to UA))
            if (!response.isSuccessful) return@runCatching null
            val root = runCatching { Json.parseToJsonElement(response.text) }.getOrNull()
                as? JsonObject ?: return@runCatching null
            val main = (root["HDHUB4u"] ?: root["hdhub4u"])?.jsonPrimitive?.contentOrNull
            main?.trimEnd('/')
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val value = live ?: FALLBACK_DOMAIN
        synchronized(cacheLock) { domainCache = System.currentTimeMillis() to value }
        return value
    }

    private suspend fun rowCards(path: String): List<Card> {
        synchronized(cacheLock) {
            val hit = rowCache[path]
            if (hit != null && System.currentTimeMillis() - hit.first < ROW_TTL_MS) {
                return hit.second
            }
        }
        val base = domain()
        val html = getText(base + path, "$base/") ?: return emptyList()
        val cards = cardsOf(html, base)
        if (cards.isNotEmpty()) {
            synchronized(cacheLock) {
                if (rowCache.size > MAX_CACHE) rowCache.clear()
                rowCache[path] = System.currentTimeMillis() to cards
            }
        }
        return cards
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, hasNext) = searchPage(query, page)
        return newSearchResponseList(items, hasNext)
    }

    /**
     * One page of Typesense hits. The index is a snapshot of the site, so a query
     * that returns nothing there is retried against the site's own `?s=` search
     * before it is reported as "no results".
     */
    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<SearchResponse>() to false
        val base = domain()
        val url = "$SEARCH_BASE?query_by_weights=4,2&query_by=post_title,category" +
            "&q=${encode(q)}&sort_by=sort_by_date:desc&limit=$SEARCH_LIMIT" +
            "&highlight_fields=none&use_cache=true&page=$page"
        val response = runCatching {
            app.get(url = url, headers = mapOf("User-Agent" to UA, "Referer" to "$base/"))
        }.getOrNull()?.takeIf { it.isSuccessful }
        val parsed = response?.text?.let {
            runCatching { AppUtils.parseJson<TypesenseResponse>(it) }.getOrNull()
        }
        val cards = parsed?.hits.orEmpty().mapNotNull { hitCard(it.document, base) }
        if (cards.isEmpty()) {
            // No index hit — the site's own search page is the fallback.
            val html = getText("$base/?s=${encode(q)}", "$base/")
            if (html != null) {
                return cardsOf(html, base).map { it.toSearchResponse() } to false
            }
            return emptyList<SearchResponse>() to false
        }
        val found = parsed?.found ?: 0
        val hasNext = page * SEARCH_LIMIT < found
        return cards.map { it.toSearchResponse() } to hasNext
    }

    /** Index permalinks are relative (often on a stale host) — rebase the path. */
    private fun hitCard(hit: TypesenseDocument?, base: String): Card? {
        val doc = hit ?: return null
        val raw = doc.permalink ?: doc.url ?: return null
        val path = raw.replace(SCHEME_RE, "")
        val url = absoluteUrl(path, base)
        val img = doc.post_thumbnail ?: doc.feature_img ?: doc.image
        return Card(
            title = cleanTitle(doc.post_title ?: doc.title.orEmpty()).ifEmpty { "Untitled" },
            url = url,
            cover = img?.takeIf { it.isNotBlank() }?.let { absoluteUrl(it, base) }
        )
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val target = contentUrlOf(url) ?: return null
        val html = getText(target, "$mainUrl/") ?: return null

        // The title sits in the page-title's <span class="material-text"> — the
        // leading <i class="material-icons"> glyph must not be scraped, it renders
        // as a junk first letter.
        val rawTitle = MATERIAL_TEXT_RE.find(html)?.groupValues?.get(1)
            ?: OG_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: PAGE_TITLE_RE.find(html)?.groupValues?.get(1)
            ?: "Untitled"
        val title = cleanTitle(rawTitle)
        val pageText = text(rawTitle)
        val year = YEAR_IN_PARENS_RE.find(pageText)?.groupValues?.get(0)?.trim('(', ')')?.toIntOrNull()
            ?: YEAR_RE.find(html)?.groupValues?.get(0)?.toIntOrNull()
        val isSeries = SERIES_HINT_RE.containsMatchIn(pageText) ||
            EPISODE_HEADING_RE.findAll(html).count() > 1

        var detail = Detail(
            title = title,
            poster = OG_IMAGE_RE.find(html)?.groupValues?.get(1),
            plot = text(DESCRIPTION_META_RE.find(html)?.groupValues?.get(1)),
            year = year,
            genres = emptyList(),
            isSeries = isSeries,
            episodes = if (isSeries) seriesEpisodes(html) else movieEpisodes(html, title)
        )
        val enriched = withTmdb(detail)
        detail = enriched.detail

        if (detail.isSeries) {
            val episodes = detail.episodes.map { it.toEpisode() }
            return newTvSeriesLoadResponse(detail.title, target, TvType.TvSeries, episodes) {
                posterUrl = detail.poster
                backgroundPosterUrl = detail.poster
                this.year = detail.year
                plot = detail.plot
                tags = detail.genres.takeIf { it.isNotEmpty() }
                addImdbId(enriched.imdbId)
            }
        }
        return newMovieLoadResponse(
            detail.title,
            target,
            TvType.Movie,
            detail.episodes.firstOrNull()?.let { encodeHrefs(it.hrefs) }.orEmpty()
        ) {
            posterUrl = detail.poster
            backgroundPosterUrl = detail.poster
            this.year = detail.year
            plot = detail.plot
            tags = detail.genres.takeIf { it.isNotEmpty() }
            addImdbId(enriched.imdbId)
        }
    }

    /**
     * TMDB enrichment — plot, genres, poster, episode names/stills and the IMDb
     * id that drives tracking. Entirely optional: without a key in
     * Settings → Player → Metadata every call here is skipped and the detail
     * stands on the site's own metadata.
     */
    private suspend fun withTmdb(detail: Detail): Enriched {
        val key = TMDB_API_KEY ?: return Enriched(detail)
        val isTv = detail.isSeries
        val query = cleanTitle(detail.title)
            .replace(SEASON_TAIL_RE, "")
            .replace(BRACKET_TAIL_RE, "")
            .trim()
        if (query.isEmpty()) return Enriched(detail)
        val kind = if (isTv) "tv" else "movie"
        val search = tmdbJson<TmdbSearchResponse>(
            "$TMDB_BASE/search/$kind?api_key=$key&query=${encode(query)}&language=$TMDB_LANGUAGE"
        ) ?: return Enriched(detail)
        val item = search.results.firstOrNull()
        val id = item?.id?.takeIf { it > 0 } ?: return Enriched(detail)
        val wanted = detail.year?.toString()
        val match = search.results.firstOrNull { candidate ->
            val date = candidate.first_air_date ?: candidate.release_date
            wanted != null && date?.startsWith(wanted) == true
        } ?: item

        return coroutineScope {
            val details = async {
                tmdbJson<TmdbDetails>(
                    "$TMDB_BASE/$kind/$match.id?api_key=$key&language=$TMDB_LANGUAGE"
                )
            }
            val season = async {
                if (isTv) {
                    tmdbJson<TmdbSeason>("$TMDB_BASE/tv/$match.id/season/1?api_key=$key")
                } else {
                    null
                }
            }
            val external = async {
                tmdbJson<TmdbExternalIds>("$TMDB_BASE/$kind/$match.id/external_ids?api_key=$key")
            }
            val info = details.await()
            val episodes = season.await()?.episodes.orEmpty().associateBy { it.episode_number }
            val imdbId = external.await()?.imdb_id?.takeIf { it.startsWith("tt") }

            return@coroutineScope Enriched(
                detail = detail.copy(
                    poster = info?.poster_path?.let { "$TMDB_POSTER$it" } ?: detail.poster,
                    plot = info?.overview?.takeIf { it.isNotBlank() } ?: detail.plot,
                    genres = info?.genres.orEmpty().mapNotNull { it.name },
                    episodes = detail.episodes.map { episode ->
                        val meta = episodes[episode.number] ?: return@map episode
                        episode.copy(
                            title = "Episode ${episode.number} - " +
                                (meta.name ?: "Episode ${episode.number}"),
                            thumbnail = meta.still_path?.let { "$TMDB_STILL$it" } ?: episode.thumbnail
                        )
                    }
                ),
                imdbId = imdbId
            )
        }
    }

    // ------------------------------------------------------------- loadLinks

    /**
     * Episode data is `"hdhub4u|<id>"`, where `<id>` is the url-encoded list of
     * download links scraped off the detail page — self-contained, so playback
     * needs nothing but that string. The chain behind it is re-walked per
     * request: the landing pages and the redirect blob are re-issued constantly
     * and a cached hop is a dead hop.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 2)
        if (parts.size != 2 || parts[0] != PROVIDER_KEY) return false
        val hrefs = decodeHrefs(parts[1])
        if (hrefs.isEmpty()) return false

        val streams = coroutineScope {
            hrefs.take(MAX_HREFS).map { async { resolveHref(it) } }.awaitAll()
        }.flatten().distinctBy { it.url }.take(MAX_STREAMS)

        var emitted = false
        for (stream in streams) {
            for (track in stream.subtitles) {
                subtitleCallback(
                    newSubtitleFile(track.lang, track.url) {
                        headers = stream.headers
                    }
                )
            }
            if (emitStream(stream, callback)) emitted = true
        }
        return emitted
    }

    /** `?id=` / gadgets links are a resolver hop; everything else is dispatched as-is. */
    private suspend fun resolveHref(href: String): List<ResolvedStream> {
        if (!RESOLVER_HINT_RE.containsMatchIn(href)) return dispatch(href)
        val resolved = resolveRedirect(href)
        return if (resolved.isNullOrBlank()) emptyList() else dispatch(resolved)
    }

    private suspend fun emitStream(
        stream: ResolvedStream,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (M3U8_RE.containsMatchIn(stream.url)) {
            // Adaptive masters are expanded by the helper, so the player gets a
            // real quality menu instead of a single opaque url.
            val links = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = stream.url,
                    referer = stream.referer,
                    quality = stream.quality,
                    headers = stream.headers,
                    name = stream.label
                )
            }.getOrNull().orEmpty()
            if (links.isEmpty()) return false
            links.forEach { callback(it) }
            return true
        }
        callback(
            newExtractorLink(
                source = name,
                name = stream.label,
                url = stream.url,
                type = ExtractorLinkType.VIDEO
            ) {
                quality = stream.quality ?: Qualities.Unknown.value
                referer = stream.referer
                headers = stream.headers
            }
        )
        return true
    }

    // ------------------------------------------------------- redirect resolver

    /**
     * The `?id=` hop hides its destination in two obfuscated script calls; the
     * payload is triple-base64 with a ROT13 in the middle, and either carries the
     * target outright or a second hop onto the blog host.
     */
    private suspend fun resolveRedirect(url: String): String? {
        val html = getText(url) ?: return null
        val combined = StringBuilder()
        for (m in REDIRECT_PAYLOAD_RE.findAll(html)) {
            combined.append(m.groupValues[1].ifEmpty { m.groupValues[2] })
        }
        if (combined.isEmpty()) return null
        val decoded = runCatching {
            base64Decode(rot13(base64Decode(base64Decode(combined.toString()))))
        }.getOrNull() ?: return null
        val payload = runCatching { AppUtils.parseJson<RedirectPayload>(decoded) }.getOrNull()
            ?: return null

        val direct = payload.o?.let { runCatching { base64Decode(it) }.getOrNull() }?.trim()
        if (!direct.isNullOrEmpty()) return direct

        val data = payload.data?.let { runCatching { base64Decode(it) }.getOrNull() }
        val wp = payload.blog_url
        if (wp.isNullOrBlank() || data.isNullOrBlank()) return null
        val body = getText("$wp?re=$data") ?: return null
        return text(body).takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------- dispatch

    private suspend fun dispatch(link: String): List<ResolvedStream> {
        val low = link.lowercase()
        return when {
            "hblinks" in low -> fromHblinks(link)
            "hubcloud" in low -> fromHubCloud(link)
            "hubdrive" in low -> fromHubDrive(link)
            "hdstream4u" in low -> fromVidHide(link)
            "pixeldra" in low -> listOfNotNull(
                pixeldrainStream(link, null, "HDHub4u [Pixeldrain]")
            )
            "hubstream" in low -> fromHubStream(link)
            DIRECT_FILE_RE.containsMatchIn(low) -> listOf(
                stream(
                    url = link,
                    quality = qualityValue(link),
                    label = "HDHub4u",
                    referer = "",
                    headers = mapOf("User-Agent" to UA)
                )
            )
            // One more hop for the known shorteners, in case the site starts
            // handing those out in place of its own resolver.
            ShortLink.isShortLink(link) -> {
                val unshortened = runCatching { ShortLink.unshorten(link) }.getOrNull()
                if (!unshortened.isNullOrBlank() && unshortened != link) {
                    dispatch(unshortened)
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- hubcloud

    private suspend fun fromHubCloud(url: String): List<ResolvedStream> {
        val base = baseOf(url).orEmpty()
        val href = if ("hubcloud.php" in url) {
            url
        } else {
            val html = getText(url) ?: return emptyList()
            val raw = DOWNLOAD_HREF_RE.find(html)?.groupValues?.get(1)
                ?: DOWNLOAD_HREF_REV_RE.find(html)?.groupValues?.get(1)
                ?: return emptyList()
            if (raw.startsWith("http", ignoreCase = true)) raw else "$base/${raw.trimStart('/')}"
        }

        val doc = getText(href) ?: return emptyList()
        val title = text(CARD_HEADER_RE.find(doc)?.groupValues?.get(1))
        // The site posts sample cuts, trailers and post-credit clips next to the
        // real release, tagged with the same quality — left in, one of them wins
        // the "best source" pick and you get seven minutes instead of the film.
        if (NON_RELEASE_RE.containsMatchIn(title)) return emptyList()
        // No resolution in the title means we don't know it — say so rather than
        // claiming one, so the player hides its quality menu.
        val quality = qualityOf(title)
        val info = HubFileInfo(
            tags = releaseTags(title),
            size = text(SIZE_RE.find(doc)?.groupValues?.get(1)),
            quality = quality,
            pixeldrain = PXL_RE.find(doc)?.groupValues?.get(1).orEmpty()
        )

        val out = ArrayList<ResolvedStream>()
        for (m in BTN_ANCHOR_RE.findAll(doc)) {
            val link = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val label = text(m.groupValues[2].ifEmpty { m.groupValues[4] }).lowercase()
            if (link.isBlank()) continue
            hubServerStream(link, label, info)?.let { out.add(it) }
        }
        return out
    }

    /** One HubCloud server button → the file behind it, when the kind is known. */
    private suspend fun hubServerStream(
        link: String,
        label: String,
        info: HubFileInfo
    ): ResolvedStream? {
        val server = serverName(label)
        val display = streamLabel(server, info)

        if ("buzz" in label) {
            val response = runCatching {
                app.get(
                    url = "$link/download",
                    headers = mapOf("User-Agent" to UA, "Referer" to link),
                    allowRedirects = false
                )
            }.getOrNull() ?: return null
            val download = response.headers["hx-redirect"]
            if (download.isNullOrBlank()) return null
            return stream(download, info.quality, display, link, mapOf("User-Agent" to UA))
        }
        if ("pixeldra" in label || "pixel" in label) {
            // The button's href is a decoy that is the same on every page — the
            // real file id is swapped in by the script below it, so prefer that.
            return pixeldrainStream(
                info.pixeldrain.ifBlank { link },
                info.quality,
                display
            )
        }
        if ("fsl" in label || "download" in label || "s3" in label || "10gb" in label) {
            return stream(link, info.quality, display, link, mapOf("User-Agent" to UA))
        }
        if (DIRECT_FILE_RE.containsMatchIn(link.lowercase())) {
            return stream(link, info.quality, display, link, mapOf("User-Agent" to UA))
        }
        return null
    }

    private fun pixeldrainStream(
        link: String,
        quality: Int?,
        label: String
    ): ResolvedStream? {
        val base = baseOf(link).orEmpty()
        val file = if ("download" in link) {
            link
        } else {
            "$base/api/file/${link.trimEnd('/').substringAfterLast('/')}?download"
        }
        if (file.isBlank() || base.isBlank()) return null
        return stream(file, quality, label, base, mapOf("User-Agent" to UA))
    }

    private suspend fun fromHubDrive(url: String): List<ResolvedStream> {
        val html = getText(url) ?: return emptyList()
        val href = HUBDRIVE_LINK_RE.find(html)?.groupValues?.get(1)
            ?: HUBDRIVE_LINK_REV_RE.find(html)?.groupValues?.get(1)
            ?: HUBDRIVE_HUBCLOUD_RE.find(html)?.groupValues?.get(1)
            ?: return emptyList()
        if (href.isBlank()) return emptyList()
        return fromHubCloud(href)
    }

    /** hblinks is a page of hubcloud/hubdrive links — walk each of them. */
    private suspend fun fromHblinks(url: String): List<ResolvedStream> {
        val html = getText(url) ?: return emptyList()
        val links = ArrayList<String>()
        for (m in ANCHOR_HREF_RE.findAll(html)) {
            val href = m.groupValues[1]
            val low = href.lowercase()
            if (("hubcloud" in low || "hubdrive" in low) && !links.contains(href)) {
                links.add(href)
            }
            if (links.size >= MAX_HBLINK_LINKS) break
        }
        if (links.isEmpty()) return emptyList()
        return coroutineScope {
            links.map { async { dispatch(it) } }.awaitAll()
        }.flatten()
    }

    // -------------------------------------------------------------- hdstream4u

    /** hdstream4u (VidHide): packed-JS player at /v/{id} → m3u8. Best-effort. */
    private suspend fun fromVidHide(url: String): List<ResolvedStream> {
        val id = VIDHIDE_ID_RE.find(url)?.groupValues?.get(1)
        val base = baseOf(url).orEmpty()
        val embed = if (id.isNullOrBlank()) url else "$base/v/$id"
        val embedBase = baseOf(embed).orEmpty()
        val body = getText(embed, "$embedBase/") ?: return emptyList()
        val unpacked = runCatching { getAndUnpack(body) }.getOrDefault(body)
        val haystack = "$unpacked\n$body"
        val file = VIDHIDE_SRC_RE.find(haystack)?.groupValues?.get(1)
            ?: VIDHIDE_URL_RE.find(haystack)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(
            stream(
                url = file.replace("\\/", "/"),
                quality = null,
                label = "HDHub4u [HdStream4u]",
                referer = "$embedBase/",
                headers = mapOf("User-Agent" to UA, "Referer" to "$embedBase/")
            )
        )
    }

    // --------------------------------------------------------------- hubstream

    /**
     * hubstream (VidStack): /api/v1/video?id= answers one hex blob that is
     * AES-128-CBC with a static key, and one of two IVs. The plaintext carries
     * the m3u8 and the subtitle tracks.
     */
    private suspend fun fromHubStream(url: String): List<ResolvedStream> {
        val host = baseOf(url) ?: HUBSTREAM_FALLBACK
        // The playable id is the tail of the fragment, after the last '/'.
        val tail = url.substringAfterLast('#', "")
        if (tail.isBlank()) return emptyList()
        val hash = tail.substringAfterLast('/')
        if (hash.isBlank()) return emptyList()

        val body = runCatching {
            app.get(
                url = "$host/api/v1/video?id=$hash",
                headers = mapOf("User-Agent" to UA, "Referer" to url)
            )
        }.getOrNull()?.text?.trim().orEmpty()
        if (!HEX_RE.containsMatchIn(body) || body.length % 32 != 0) return emptyList()
        val cipherText = hexToBytes(body) ?: return emptyList()

        for (iv in HUBSTREAM_IVS) {
            val plain = aesCbcDecrypt(cipherText, HUBSTREAM_KEY.toByteArray(), iv.toByteArray())
                ?: continue
            val decrypted = String(plain, Charsets.UTF_8)
            val file = HUBSTREAM_SOURCE_RE.find(decrypted)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?: continue
            if (!file.startsWith("http", ignoreCase = true)) continue
            return listOf(
                stream(
                    url = file,
                    quality = qualityOf(file),
                    label = "HDHub4u [HubStream]",
                    referer = "$host/",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$host/",
                        "Origin" to host
                    ),
                    subtitles = hubStreamSubtitles(decrypted, host)
                )
            )
        }
        return emptyList()
    }

    private fun hubStreamSubtitles(plain: String, host: String): List<SubTrack> {
        val block = HUBSTREAM_SUB_RE.find(plain)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<SubTrack>()
        for (m in HUBSTREAM_PAIR_RE.findAll(block)) {
            val url = m.groupValues[2].replace("\\/", "/").substringBefore('#')
            if (!SUBTITLE_FILE_RE.containsMatchIn(url)) continue
            out.add(
                SubTrack(
                    lang = m.groupValues[1],
                    url = if (url.startsWith("http", ignoreCase = true)) url else "$host$url"
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun stream(
        url: String,
        quality: Int?,
        label: String,
        referer: String,
        headers: Map<String, String>,
        subtitles: List<SubTrack> = emptyList()
    ): ResolvedStream = ResolvedStream(url, label, quality, referer, headers, subtitles)

    /**
     * Episode data packs every download link of that episode, so playback does
     * not have to refetch the detail page to find them.
     */
    private fun EpisodeRef.toEpisode() = newEpisode(
        encodeHrefs(hrefs),
        {
            name = title
            season = 1
            episode = number
            posterUrl = thumbnail
        },
        fix = false
    )

    private fun Card.toSearchResponse(): SearchResponse {
        val series = SERIES_HINT_RE.containsMatchIn(title) || SERIES_URL_HINT_RE.containsMatchIn(url)
        return if (series) {
            newTvSeriesSearchResponse(title, url) {
                posterUrl = cover
            }
        } else {
            newMovieSearchResponse(title, url) {
                posterUrl = cover
            }
        }
    }

    private fun encodeHrefs(hrefs: List<String>): String =
        "$PROVIDER_KEY|${URLEncoder.encode(hrefs.joinToString(HREF_SEP), "UTF-8")}"

    private fun decodeHrefs(id: String): List<String> {
        val raw = runCatching {
            with(StringUtils) { id.decodeUrl() }
        }.getOrNull() ?: return emptyList()
        return raw.split(HREF_SEP).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Accepts the absolute urls handed out by search/home as well as a bare path,
     * so a card that got stored with an older domain still opens.
     */
    private fun contentUrlOf(url: String): String? {
        val trimmed = url.trim().substringBefore('#')
        if (trimmed.isEmpty()) return null
        // Absolute urls are taken as given: the domain rotates, and a card stored
        // against an older one is still worth one request before it is dropped.
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        return absoluteUrl(trimmed, mainUrl)
    }

    private suspend inline fun <reified T : Any> tmdbJson(url: String): T? {
        val response = runCatching {
            app.get(url = url, headers = mapOf("User-Agent" to UA))
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<T>(response.text) }.getOrNull()
    }

    private suspend fun getText(url: String, referer: String = url): String? {
        val response = runCatching {
            app.get(url = url, headers = pageHeaders(referer))
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return response.text
    }

    private fun pageHeaders(referer: String): Map<String, String> = linkedMapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        // The site's own pages carry this; the aggregator endpoints refuse
        // anything that looks like an anonymous client without it.
        "Cookie" to "xla=s4t"
    )

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Scheme + host of a url. */
    private fun baseOf(url: String): String? =
        BASE_RE.find(url)?.groupValues?.get(1)

    private fun absoluteUrl(raw: String, base: String): String {
        val href = raw.trim()
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "${base.trimEnd('/')}$href"
            else -> "${base.trimEnd('/')}/$href"
        }
    }

    private fun rot13(value: String): String = buildString(value.length) {
        for (c in value) {
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + ((c - 'a' + 13) % 26)
                    in 'A'..'Z' -> 'A' + ((c - 'A' + 13) % 26)
                    else -> c
                }
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = byte.toByte()
        }
        return out
    }

    private fun aesCbcDecrypt(
        cipherText: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(cipherText)
    }.getOrNull()

    // ------------------------------------------------------------------ cards

    /**
     * `.recent-movies > li.thumb` cards, the one shape the home, category and
     * `?s=` listings all share.
     */
    private fun cardsOf(html: String, base: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        val chunks = THUMB_SPLIT_RE.split(html)
        for (i in 1 until chunks.size) {
            val chunk = chunks[i]
            val href = FIGCAPTION_HREF_RE.find(chunk)?.groupValues?.get(1)
                ?: ANCHOR_HREF_RE.find(chunk)?.groupValues?.get(1)
                ?: continue
            val url = absoluteUrl(href, base)
            if (url.contains("/category/") || url.contains("/page/")) continue
            if (!seen.add(url)) continue
            val rawTitle = FIGCAPTION_TITLE_RE.find(chunk)?.groupValues?.get(1)
                ?: IMG_ALT_RE.find(chunk)?.groupValues?.get(1)
                ?: ""
            val cover = IMG_SRC_RE.find(chunk)?.groupValues?.get(1)
            out.add(
                Card(
                    title = cleanTitle(rawTitle).ifEmpty { "Untitled" },
                    url = url,
                    cover = cover?.let { absoluteUrl(it, base) }
                )
            )
        }
        return out
    }

    private fun movieEpisodes(html: String, title: String): List<EpisodeRef> {
        val hrefs = linkedHrefs(html)
        if (hrefs.isEmpty()) return emptyList()
        return listOf(EpisodeRef(1, title.ifBlank { "Movie" }, hrefs))
    }

    /**
     * Series pages carry no episode list markup — the links sit in document order
     * under "Episode N" headings, so the heading in effect at each anchor is the
     * episode it belongs to. Pages that expose no headings at all fall back to a
     * single entry, which is how a season pack is posted.
     */
    private fun seriesEpisodes(html: String): List<EpisodeRef> {
        val heads = ArrayList<Pair<Int, Int>>()
        for (m in EPISODE_HEADING_RE.findAll(html)) {
            val number = m.groupValues[1].toIntOrNull() ?: continue
            heads.add(m.range.first to number)
        }
        val grouped = LinkedHashMap<Int, MutableList<String>>()
        for (m in ANCHOR_HREF_RE.findAll(html)) {
            val href = m.groupValues[1]
            if (!LINK_HINT_RE.containsMatchIn(href)) continue
            var episode = 1
            for ((index, number) in heads) {
                if (index < m.range.first) episode = number else break
            }
            val list = grouped.getOrPut(episode) { ArrayList() }
            if (!list.contains(href)) list.add(href)
        }
        val out = ArrayList<EpisodeRef>()
        for (number in grouped.keys.sorted()) {
            out.add(EpisodeRef(number, "Episode $number", grouped.getValue(number)))
        }
        if (out.isEmpty()) return movieEpisodes(html, "Full")
        return out
    }

    private fun linkedHrefs(html: String): List<String> {
        val out = ArrayList<String>()
        for (m in ANCHOR_HREF_RE.findAll(html)) {
            val href = m.groupValues[1]
            if (!LINK_HINT_RE.containsMatchIn(href)) continue
            if (!out.contains(href)) out.add(href)
        }
        return out
    }

    /**
     * Release names are the site's own quality/format advertisement; trimmed of
     * the season/quality noise so the card title reads like a title.
     */
    private fun cleanTitle(raw: String): String {
        val plain = text(raw).replace(DOWNLOAD_PREFIX_RE, "")
        val trimmed = plain.split(BRACKET_SPLIT_RE, limit = 2)[0]
            .split(SEASON_SPLIT_RE, limit = 2)[0]
            .split(SEASON_NUM_RE, limit = 2)[0]
            .replace(TRAILING_TAGS_RE, "")
            .replace(WHITESPACE_RE, " ")
            .trim()
        return trimmed.ifEmpty { text(raw).trim() }
    }

    /** Normalised release tags out of a HubCloud filename. */
    private fun releaseTags(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val spaced = EXTENSION_RE.replace(title.trim(), "")
            .replace('.', ' ').replace('_', ' ')
        val normalised = (" " + spaced
            .replace(WEB_DL_RE, "WEB-DL")
            .replace(WEB_RIP_RE, "WEBRIP")
            .replace(H265_RE, "H265")
            .replace(H264_RE, "H264") + " ").uppercase()

        val out = ArrayList<String>()
        for (group in TAG_GROUPS) {
            for ((tag, pattern) in group) {
                if (pattern.containsMatchIn(normalised)) {
                    out.add(tag)
                    break
                }
            }
        }
        if (ATMOS_RE.containsMatchIn(normalised)) out.add("ATMOS")
        if (HDR10_RE.containsMatchIn(normalised)) out.add("HDR10+") else if (HDR_RE.containsMatchIn(normalised)) out.add("HDR")
        if (DUAL_RE.containsMatchIn(normalised)) out.add("DUAL") else if (HINDI_RE.containsMatchIn(normalised)) out.add("HINDI")
        return out.distinct().joinToString(" ")
    }

    private fun serverName(label: String): String {
        val l = label.lowercase()
        return when {
            "fsl" in l -> "FSL Server"
            "buzz" in l -> "Buzz Server"
            "pixeldra" in l || "pixel" in l -> "Pixeldrain"
            "s3" in l -> "S3 Server"
            "10gb" in l -> "10Gbps"
            "download" in l -> "Download"
            else -> "HubCloud"
        }
    }

    /** "HDHub4u [FSL Server] [WEB-DL H265 DDP5.1] [2.1GB] 1080p" */
    private fun streamLabel(server: String, info: HubFileInfo): String = buildString {
        append("$name [$server]")
        if (info.tags.isNotBlank()) append(" [${info.tags}]")
        if (info.size.isNotBlank()) append(" [${info.size}]")
        if (info.quality != null) {
            append(' ')
            append(
                if (info.quality == Qualities.P2160.value) "4K"
                else "${info.quality}p"
            )
        }
    }

    /** `1080p` out of a release name or a file url, or null when it carries none. */
    private fun qualityOf(raw: String?): Int? =
        QUALITY_RE.find(raw.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    /** Same, but 4K spelled out — some CDNs put it in the path instead of a height. */
    private fun qualityValue(raw: String?): Int =
        qualityOf(raw)
            ?: getQualityFromName(if (raw.orEmpty().contains("4k", ignoreCase = true)) "4k" else null)

    // ------------------------------------------------------------------ text

    private fun text(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return htmlText(raw.replace(TAG_RE, " ")).replace(WHITESPACE_RE, " ").trim()
    }

    private fun htmlText(raw: String): String {
        if ('&' !in raw) return raw
        var out = NUMERIC_ENTITY_RE.replace(raw) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) {
                m.groupValues[2].toIntOrNull(16)
            } else {
                m.groupValues[2].toIntOrNull()
            }
            if (code == null || code <= 0 || code > 0x10FFFF) m.value else code.toChar().toString()
        }
        for ((entity, ch) in ENTITIES) out = out.replace(entity, ch)
        return out
    }

    private var domainCache: Pair<Long, String>? = null
    private val rowCache = HashMap<String, Pair<Long, List<Card>>>()
    private val cacheLock = Any()

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * The site's domain changes without warning; this one is only used when
         * the community list itself can't be reached.
         */
        const val FALLBACK_DOMAIN = "https://new5.hdhub4u.cl"
        const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

        const val SEARCH_BASE = "https://search.pingora.fyi/collections/post/documents/search"
        const val SEARCH_LIMIT = 20

        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_POSTER = "https://image.tmdb.org/t/p/w500"
        const val TMDB_STILL = "https://image.tmdb.org/t/p/w300"

        const val HUBSTREAM_FALLBACK = "https://hubstream.art"

        /** Provider key + url-encoded, newline joined list of download links. */
        const val PROVIDER_KEY = "hdhub4u"
        const val HREF_SEP = "\n"

        const val DOMAIN_TTL_MS = 60 * 60 * 1000L
        const val ROW_TTL_MS = 30 * 60 * 1000L
        const val MAX_CACHE = 12
        const val MAX_HREFS = 12
        const val MAX_STREAMS = 20
        const val MAX_HBLINK_LINKS = 5

        /** hubstream's blob is AES-CBC with a fixed key and one of two IVs. */
        const val HUBSTREAM_KEY = "kiemtienmua911ca"
        val HUBSTREAM_IVS = listOf("1234567890oiuytr", "0123456789abcdef")

        val TMDB_API_KEY: String?
            get() = tmdbApiKeyOverride?.takeIf { it.isNotBlank() }
        val TMDB_LANGUAGE: String
            get() = tmdbLanguageOverride?.takeIf { it.isNotBlank() } ?: "en-US"

        val BASE_RE = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
        val SCHEME_RE = Regex("^https?://[^/]+")
        val TAG_RE = Regex("<[^>]*>")
        val WHITESPACE_RE = Regex("\\s+")

        val M3U8_RE = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE)
        val DIRECT_FILE_RE = Regex("\\.(mp4|mkv|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)
        val HEX_RE = Regex("^[0-9a-fA-F]+$")
        val QUALITY_RE = Regex("(\\d{3,4})[pP]")
        val DOWNLOAD_PREFIX_RE = Regex("^download\\s+", RegexOption.IGNORE_CASE)

        /** Anything that can lead to a file: the site's link hops and its resolver. */
        val LINK_HINT_RE = Regex(
            "hdstream4u|hubstream|hubcloud|hubdrive|hblinks|pixeldra|gadgets|\\?id=",
            RegexOption.IGNORE_CASE
        )
        val RESOLVER_HINT_RE = Regex("\\?id=|gadgets", RegexOption.IGNORE_CASE)

        val THUMB_SPLIT_RE = Regex("<li[^>]*class=\"[^\"]*\\bthumb\\b", RegexOption.IGNORE_CASE)
        val FIGCAPTION_HREF_RE =
            Regex("<figcaption[\\s\\S]*?<a[^>]+href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val FIGCAPTION_TITLE_RE =
            Regex("<figcaption[\\s\\S]*?<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
        val IMG_SRC_RE =
            Regex("<img[^>]+(?:data-lazy-src|data-src|src)=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val IMG_ALT_RE = Regex("<img[^>]+alt=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val ANCHOR_HREF_RE = Regex("<a[^>]+href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        val MATERIAL_TEXT_RE = Regex("<span class=\"material-text\">([\\s\\S]*?)</span>")
        val OG_TITLE_RE = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val OG_IMAGE_RE = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val DESCRIPTION_META_RE =
            Regex("<meta name=\"description\" content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val PAGE_TITLE_RE =
            Regex("<h1[^>]*class=\"[^\"]*page-title[^\"]*\"[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)
        val SERIES_HINT_RE =
            Regex("\\bseason\\b|\\bS0?\\d|web[- ]?series|episode\\s*0*\\d", RegexOption.IGNORE_CASE)
        val SERIES_URL_HINT_RE = Regex("/(season|web-series)[-/]", RegexOption.IGNORE_CASE)
        val EPISODE_HEADING_RE = Regex("episode\\s*0*(\\d{1,3})", RegexOption.IGNORE_CASE)
        val YEAR_IN_PARENS_RE = Regex("\\((19|20)\\d{2}\\)")
        val YEAR_RE = Regex("\\b(19|20)\\d{2}\\b")
        val BRACKET_SPLIT_RE = Regex("\\s*\\(")
        val SEASON_SPLIT_RE = Regex("\\bseason\\b", RegexOption.IGNORE_CASE)
        val SEASON_NUM_RE = Regex("\\bS0?\\d")
        val SEASON_TAIL_RE = Regex("\\bseason\\b.*$", RegexOption.IGNORE_CASE)
        val BRACKET_TAIL_RE = Regex("\\(.*$")
        val TRAILING_TAGS_RE = Regex(
            "\\b(480p|720p|1080p|2160p|4k|web[- ]?dl|hdrip|bluray|x264|x265|hevc|hindi|dual audio).*$",
            RegexOption.IGNORE_CASE
        )

        val REDIRECT_PAYLOAD_RE = Regex(
            "s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'"
        )

        val DOWNLOAD_HREF_RE = Regex("id=[\"']download[\"'][^>]*href=\"([^\"]+)\"")
        val DOWNLOAD_HREF_REV_RE = Regex("href=\"([^\"]+)\"[^>]*id=[\"']download[\"']")
        val CARD_HEADER_RE = Regex("<div class=\"card-header[^\"]*\"[^>]*>([\\s\\S]*?)</div>")
        val SIZE_RE = Regex("id=[\"']size[\"'][^>]*>([\\s\\S]*?)</")
        val PXL_RE = Regex("var\\s+pxl\\s*=\\s*[\"']([^\"']+)[\"']")
        val BTN_ANCHOR_RE = Regex(
            "<a[^>]*href=\"([^\"]+)\"[^>]*class=\"[^\"]*\\bbtn\\b[^\"]*\"[^>]*>([\\s\\S]*?)</a>" +
                "|<a[^>]*class=\"[^\"]*\\bbtn\\b[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE
        )
        /** Samples, trailers and post-credit clips are tagged like the real release. */
        val NON_RELEASE_RE = Regex("\\bsample\\b|post[-. ]?credit|\\btrailer\\b", RegexOption.IGNORE_CASE)

        val HUBDRIVE_LINK_RE = Regex("class=\"[^\"]*btn-success1[^\"]*\"[^>]*href=\"([^\"]+)\"")
        val HUBDRIVE_LINK_REV_RE = Regex("href=\"([^\"]+)\"[^>]*class=\"[^\"]*btn-success1")
        val HUBDRIVE_HUBCLOUD_RE = Regex("href=\"([^\"]+)\"[^>]*>[^<]*hubcloud", RegexOption.IGNORE_CASE)

        val VIDHIDE_ID_RE = Regex("/(?:file|v|e|d)/([A-Za-z0-9]+)")
        val VIDHIDE_SRC_RE =
            Regex("(?:file|source|src)\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\"", RegexOption.IGNORE_CASE)
        val VIDHIDE_URL_RE = Regex("\"(https?://[^\"]+\\.m3u8[^\"]*)\"", RegexOption.IGNORE_CASE)

        val HUBSTREAM_SOURCE_RE = Regex("\"source\":\"([^\"]+)\"")
        val HUBSTREAM_SUB_RE = Regex("\"subtitle\":\\{([^}]*)\\}")
        val HUBSTREAM_PAIR_RE = Regex("\"([^\"]+)\":\"([^\"]+)\"")
        val SUBTITLE_FILE_RE = Regex("\\.(vtt|srt)", RegexOption.IGNORE_CASE)

        val EXTENSION_RE = Regex("\\.(mkv|mp4|avi|m4v)\\s*$", RegexOption.IGNORE_CASE)
        val WEB_DL_RE = Regex("\\bWEB[ -]?DL\\b", RegexOption.IGNORE_CASE)
        val WEB_RIP_RE = Regex("\\bWEB[ -]?RIP\\b", RegexOption.IGNORE_CASE)
        val H265_RE = Regex("\\bH[ .]?265\\b", RegexOption.IGNORE_CASE)
        val H264_RE = Regex("\\bH[ .]?264\\b", RegexOption.IGNORE_CASE)
        val ATMOS_RE = Regex("\\bATMOS\\b")
        val HDR10_RE = Regex("\\bHDR10\\+\\b|\\bHDR10PLUS\\b")
        val HDR_RE = Regex("\\bHDR\\b")
        val DUAL_RE = Regex("\\bDUAL\\b")
        val HINDI_RE = Regex("\\bHINDI\\b")

        /** One tag per group, in the order the site's filenames list them. */
        val TAG_GROUPS: List<List<Pair<String, Regex>>> = listOf(
            listOf(
                "WEB-DL" to WEB_DL_RE,
                "WEBRIP" to WEB_RIP_RE,
                "BLURAY" to Regex("\\bBLU ?RAY\\b|\\bBDRIP\\b"),
                "HDRIP" to Regex("\\bHDRIP\\b"),
                "HDTV" to Regex("\\bHDTV\\b"),
                "HDTC" to Regex("\\bHDTC\\b"),
                "HDCAM" to Regex("\\bHDCAM\\b")
            ),
            listOf(
                "H265" to Regex("\\bH265\\b|\\bHEVC\\b"),
                "X265" to Regex("\\bX265\\b"),
                "H264" to Regex("\\bH264\\b"),
                "X264" to Regex("\\bX264\\b"),
                "AVC" to Regex("\\bAVC\\b")
            ),
            listOf(
                "DDP5.1" to Regex("\\bDDP5\\.1\\b"),
                "DDP" to Regex("\\bDDP\\b"),
                "DD5.1" to Regex("\\bDD5\\.1\\b"),
                "DTS" to Regex("\\bDTS\\b"),
                "AAC" to Regex("\\bAAC\\b"),
                "AC3" to Regex("\\bAC3\\b")
            )
        )

        val NUMERIC_ENTITY_RE = Regex("&#(x?)([0-9a-fA-F]+);")

        /** `&amp;` last — decoding it first would double-unescape. */
        val ENTITIES = linkedMapOf(
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&apos;" to "'",
            "&nbsp;" to " ",
            "&hellip;" to "\u2026",
            "&ndash;" to "-",
            "&mdash;" to "-",
            "&amp;" to "&"
        )
    }
}
