package com.hianime.cs3

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor

// ── Api payloads ─────────────────────────────────────────────────────────────

/** Both /api/theme routes answer `{ status, html }` — the payload is HTML. */
@Serializable
private data class AjaxResponse(val html: String? = null)

/**
 * `<embed>/stream/getSources(id, type)` payload.
 *
 * `sources` is not consistently shaped: VidPlay answers an object
 * (`{"file": "…"}`) while some MegaPlay builds answer an array of those
 * objects, so it stays raw and is unwrapped by [JsonElement.sourceFile].
 */
@Serializable
private data class PlayerResponse(
    val sources: JsonElement? = null,
    val tracks: List<PlayerTrack> = emptyList()
)

@Serializable
private data class PlayerTrack(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null
)

/** Zoko carries the whole player config in one obfuscated `window.__P` blob. */
@Serializable
private data class ZokoConfig(
    val src: String? = null,
    val subtitles: List<ZokoSubtitle> = emptyList()
)

@Serializable
private data class ZokoSubtitle(
    val src: String? = null,
    val lang: String? = null,
    val label: String? = null
)

// ── Parsed shapes ────────────────────────────────────────────────────────────

private data class Card(
    val title: String,
    val slug: String,
    val cover: String?
)

private data class HomeRow(val data: String, val items: List<Card>)

private data class EpisodeRef(val number: Int, val title: String, val id: String)

private data class Server(val type: String, val name: String, val url: String)

private data class Detail(
    val title: String,
    val poster: String?,
    val plot: String?,
    val statusText: String?,
    val genres: List<String>,
    val studios: List<String>,
    val year: Int?,
    val episodes: List<EpisodeRef>,
    val cuts: Set<String>,
    val malId: Int?
)

/**
 * HiAnime — anime source for CloudStream (hianime.at).
 *
 * The site renders its catalogue server-side, so browse/detail/episodes are all
 * regex over HTML. The two ajax routes the player uses aren't in the page markup
 * at all — the theme builds them from a `rest_url` config:
 *   /search?keyword=                           -> flw-item cards
 *   /watch/<slug>                              -> poster, synopsis, sub/dub ticks
 *   /api/theme/episode/list/<animeId>          -> { html } of ep-item anchors
 *   /api/theme/episode/servers?episodeId=<id>  -> { html }, data-hash = b64 embed
 *   <embed>/stream/getSources(New)?id=&type=   -> m3u8 + subtitle tracks
 *
 * The anime id is just the trailing number of the slug (dan-da-dan-86 -> 86).
 *
 * VidPlay (vidtube) uses getSources. MegaPlay now encrypts BOTH getSources and
 * getSourcesNew into an `enc` blob, so it hands us nothing playable any more.
 * Zoko has no sources endpoint at all — the embed page carries the whole player
 * config in one obfuscated blob, which is a request cheaper than either.
 *
 * That mix matters: roughly a quarter of episodes list no VidPlay server (ep1 of
 * One Piece and Naruto among them). Those used to fail outright with every other
 * server either encrypted or unsupported. Zoko is on all of them.
 */
class HianimeProvider : MainAPI() {

    override var name = "HiAnime"
    override var mainUrl = "https://hianime.at"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Spotlight", DATA_SPOTLIGHT),
        MainPageData("Trending", DATA_TRENDING),
        MainPageData("Latest Episode", DATA_LATEST),
        MainPageData("New On HiAnime", DATA_NEW),
        MainPageData("Top Upcoming", DATA_UPCOMING)
    )

    private val api get() = "$mainUrl/api/theme/"

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Every row is sliced out of the one /home document, so there is no page 2.
        if (page > 1) return null
        val row = homeRows().firstOrNull { it.data == request.data } ?: return null
        if (row.items.isEmpty()) return null
        return newHomePageResponse(request.name, row.items.map { it.toSearchResponse() })
    }

    /**
     * Sections are marked by their `<h2 class="cat-heading">`; slice each from
     * its heading to the next one. The spotlight carousel sits above the first
     * heading.
     */
    private suspend fun homeRows(): List<HomeRow> {
        synchronized(cacheLock) {
            val hit = homeCache
            if (hit != null && System.currentTimeMillis() - hit.first < CACHE_TTL_MS) {
                return hit.second
            }
        }
        // The sections live on /home. The site root is a thin landing page with
        // no cat-heading and no cards at all, so it yields no rows.
        val html = getText("$mainUrl/home", "$mainUrl/") ?: return emptyList()

        val marks = ArrayList<Pair<String, Int>>()
        for (m in HEADING_RE.findAll(html)) {
            marks.add(m.groupValues[1] to m.range.first)
        }

        val rows = ArrayList<HomeRow>()
        val spotlight = cardsOf(html.substring(0, marks.firstOrNull()?.second ?: 0))
        if (spotlight.isNotEmpty()) rows.add(HomeRow(DATA_SPOTLIGHT, spotlight))

        for (i in marks.indices) {
            val data = when (marks[i].first) {
                "Trending" -> DATA_TRENDING
                "Latest Episode" -> DATA_LATEST
                "New On HiAnime" -> DATA_NEW
                "Top Upcoming" -> DATA_UPCOMING
                else -> null
            } ?: continue
            val end = marks.getOrNull(i + 1)?.second ?: html.length
            val items = cardsOf(html.substring(marks[i].second, end))
            if (items.isNotEmpty()) rows.add(HomeRow(data, items))
        }

        synchronized(cacheLock) { homeCache = System.currentTimeMillis() to rows }
        return rows
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /**
     * One page of results, no pagination links in the markup — page 2+ is empty
     * rather than a repeat of page 1.
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/search?keyword=" + with(StringUtils) { q.encodeUrl() }
        val html = getText(url, "$mainUrl/") ?: return emptyList()
        // Results only: the page's Top 10 sidebar is built from the same card
        // markup and would otherwise ride along as matches.
        return cardsOf(resultsOnly(html)).map { it.toSearchResponse() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        return newSearchResponseList(search(query).orEmpty(), false)
    }

    private fun resultsOnly(html: String): String {
        val i = html.indexOf("film_list-wrap")
        if (i < 0) return ""
        val j = html.indexOf("</section", i)
        return html.substring(i, if (j < 0) html.length else j)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOfUrl(url) ?: return null
        val detail = detailOf(slug) ?: return null
        return detail.toLoadResponse(slug)
    }

    /**
     * Opening a show and then pressing play asks for the same detail twice: once
     * to draw the episode list, once on the playback path. The second pass would
     * otherwise refetch everything — the watch page and the episode list (~1MB on
     * a long-running show) — which on a TV box is enough on its own to blow the
     * app's per-source budget and get the source benched as dead. Five minutes is
     * far shorter than the gap between episodes.
     */
    private suspend fun detailOf(slug: String): Detail? {
        synchronized(cacheLock) {
            val hit = detailCache[slug]
            if (hit != null && System.currentTimeMillis() - hit.first < CACHE_TTL_MS) {
                return hit.second
            }
        }
        val detail = fetchDetail(slug) ?: return null
        // Only a detail that actually carries episodes is worth keeping —
        // caching a failed parse would pin the failure for the whole window.
        if (detail.episodes.isNotEmpty()) {
            synchronized(cacheLock) {
                if (detailCache.size > CACHE_MAX) detailCache.clear()
                detailCache[slug] = System.currentTimeMillis() to detail
            }
        }
        return detail
    }

    private suspend fun fetchDetail(slug: String): Detail? {
        val watchUrl = watchUrlOf(slug)
        var html = getText(watchUrl, "$mainUrl/")
        if (html == null || FILM_NAME_RE.find(html) == null) {
            // The title page is served at both /watch/<slug> and /<slug>.
            val bare = getText("$mainUrl/$slug", "$mainUrl/") ?: return null
            html = bare
        }
        val page = html ?: return null

        val animeId = ANIME_ID_ATTR_RE.find(page)?.groupValues?.get(1) ?: animeIdOf(slug)

        var episodes = emptyList<EpisodeRef>()
        var cuts = emptySet<String>()
        var malId: Int? = null
        if (animeId != null) {
            val listHtml = ajax("episode/list/$animeId", watchUrl)
            if (listHtml != null) {
                episodes = episodesOf(listHtml)
                val first = episodes.firstOrNull()
                if (first != null) {
                    // No MAL id anywhere in the page markup, but Zoko's embed url
                    // is built as /stream/mal/<malId>/<ep>/, so one server list for
                    // the first episode hands it over — and it also tells us which
                    // cuts (sub/dub) this title actually carries.
                    val servers = fetchServers(first.id, watchUrl)
                    cuts = servers.map { it.type }.toSet()
                    malId = malIdOf(servers)
                }
            }
        }
        if (cuts.isEmpty()) cuts = setOf(CAT_SUB)

        return Detail(
            title = text(FILM_NAME_RE.find(page)?.groupValues?.get(1)).ifEmpty { slug },
            poster = POSTER_RE.find(page)?.groupValues?.get(1),
            plot = text(DESCRIPTION_RE.find(page)?.groupValues?.get(1)),
            statusText = infoOf(page, "Status") ?: "unknown",
            genres = infoList(page, "Genres", 8),
            studios = infoList(page, "Studios", 4),
            year = yearOf(infoOf(page, "Aired")),
            episodes = episodes.take(MAX_EPISODES),
            cuts = cuts,
            malId = malId
        )
    }

    private suspend fun Detail.toLoadResponse(slug: String): LoadResponse {
        val detail = this
        val url = watchUrlOf(slug)
        // Prefer the sub cut when the title carries both — it is what the episode
        // list is ordered around, and the app opens on the first list.
        val subs = if (cuts.contains(CAT_SUB) || !cuts.contains(CAT_DUB)) {
            episodes.map { it.toEpisode(CAT_SUB, detail.poster) }
        } else {
            emptyList()
        }
        val dubs = if (cuts.contains(CAT_DUB)) {
            episodes.map { it.toEpisode(CAT_DUB, detail.poster) }
        } else {
            emptyList()
        }
        return newAnimeLoadResponse(detail.title, url, TvType.Anime) {
            posterUrl = detail.poster
            backgroundPosterUrl = detail.poster
            year = detail.year
            plot = detail.plot
            tags = (detail.genres + detail.studios).distinct().takeIf { it.isNotEmpty() }
            showStatus = detail.statusText.toShowStatus()
            episodes[DubStatus.Subbed] = subs
            episodes[DubStatus.Dubbed] = dubs
            addMalId(detail.malId)
        }
    }

    // ------------------------------------------------------------- loadLinks

    /**
     * The player defaults to Cronet, which does not reliably carry the referer
     * these embed hosts insist on — the CDN answers 403 without it. Handing back
     * an interceptor moves playback onto the OkHttp data source, which does send
     * it. Header values are applied with `header()`, so they replace rather than
     * duplicate what the data source already set.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        Interceptor { chain ->
            val request = chain.request().newBuilder()
                .apply {
                    extractorLink.referer
                        .takeIf { it.isNotBlank() }
                        ?.let { header("Referer", it) }
                    extractorLink.headers.forEach { (name, value) -> header(name, value) }
                }
                .build()
            chain.proceed(request)
        }

    /**
     * Episode data is `"hianime|<cat>|<epId>"`. Everything downstream of it (the
     * embed url, the sources endpoint, the cut's audio) is re-resolved at play
     * time — those urls are handed out per request and go stale fast.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3 || parts[0] != PROVIDER_KEY) return false
        val cat = if (parts[1] == CAT_DUB) CAT_DUB else CAT_SUB
        val epId = parts[2]
        if (epId.isEmpty() || !epId.all { it.isDigit() }) return false

        val servers = fetchServers(epId, "$mainUrl/watch/")
        // hsub is the same audio as sub with hardcoded signs, so it stands in.
        // No fallback to the other cut: the file a host hands back is only ever
        // labelled by what we asked for, so standing in a sub pack for a missing
        // dub would play Japanese audio under a Dub badge.
        val want = servers.filter { server ->
            if (cat == CAT_DUB) server.type == CAT_DUB
            else server.type == CAT_SUB || server.type == "hsub"
        }.sortedBy { serverRank(it.name) }

        // VidPlay first (one request cheaper than Zoko, and a stable CDN), Zoko
        // next. The MegaPlay-backed names trail them: they answer, but only with
        // `enc`, so they cost two requests to learn nothing. Left in rather than
        // dropped — if MegaPlay ever serves a plain file again this still works.
        for (server in want) {
            val ok = runCatching {
                when {
                    ZOKO_RE.containsMatchIn(server.url) ->
                        extractZoko(server.url, cat, subtitleCallback, callback)
                    PLAYER_RE.containsMatchIn(server.url) ->
                        extractPlayer(server.url, cat, subtitleCallback, callback)
                    else -> false
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * VidPlay (vidtube) only exposes getSources. MegaPlay's getSourcesNew used to
     * return a plain sources.file and no longer does — kept pointing at it anyway,
     * since it's the endpoint that would come back first if they ever relent.
     */
    private suspend fun extractPlayer(
        embed: String,
        cat: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = baseOf(embed) ?: "https://vidtube.site"
        // The cut (sub/hsub/dub) the embed url was issued for. Sources are keyed
        // by the embed's data-id, and that id is SHARED across the three cuts — the
        // audio comes from `type` alone, so a dub embed asked without it answers
        // with the sub stream.
        val type = cutOf(embed) ?: cat

        val embedHtml = getText(embed, "$mainUrl/") ?: return false
        val dataId = EMBED_DATA_ID_RE.find(embedHtml)?.groupValues?.get(1) ?: return false

        val path =
            if (MEGAPLAY_RE.containsMatchIn(base)) "/stream/getSourcesNew" else "/stream/getSources"
        val body = runCatching {
            app.get(
                url = "$base$path?id=$dataId&type=$type",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to embed,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.text ?: return false

        val json = runCatching { AppUtils.parseJson<PlayerResponse>(body) }.getOrNull() ?: return false
        // No sources.file -> walk on to the next server.
        val file = json.sources?.sourceFile() ?: return false
        val referer = "$base/"
        val headers = mapOf("User-Agent" to UA, "Referer" to referer, "Origin" to base)

        for (track in json.tracks) {
            val url = track.file ?: continue
            if (track.kind != null && track.kind != "captions" && track.kind != "subtitles") continue
            emitSubtitle(subtitleCallback, url, track.label ?: "Sub", headers)
        }
        return emitStreams(file, headers, referer, cat, callback)
    }

    /**
     * Zoko ships the player config as base64 of the JSON XOR'd with a fixed key
     * (its own core/obfuscate.js does exactly this, in reverse). One GET, no
     * sources endpoint, no decryption key to chase.
     *
     * The blob is UTF-8, and subtitle labels are the part that isn't ASCII.
     */
    private suspend fun extractZoko(
        embed: String,
        cat: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = baseOf(embed) ?: "https://zokoanime.video"
        val html = getText(embed, "$mainUrl/") ?: return false
        val cfg = zokoConfig(html) ?: return false
        val file = cfg.src ?: return false
        val referer = "$base/"
        val headers = mapOf("User-Agent" to UA, "Referer" to referer, "Origin" to base)

        // The CDN hands these out per embed fetch and stops answering a while
        // after, so they are resolved at play time and never cached.
        for (track in cfg.subtitles) {
            val url = track.src ?: continue
            emitSubtitle(subtitleCallback, url, track.label ?: track.lang ?: "Sub", headers)
        }
        return emitStreams(file, headers, referer, cat, callback)
    }

    private suspend fun emitStreams(
        file: String,
        headers: Map<String, String>,
        referer: String,
        cat: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "${catLabel(cat)} • Auto"
        if (M3U8_RE.containsMatchIn(file)) {
            // Adaptive masters are expanded by the helper, so the player gets a
            // real quality menu with "auto" still first for adaptive switching.
            val links = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = file,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    headers = headers,
                    name = label
                )
            }.getOrNull().orEmpty()
            if (links.isEmpty()) return false
            links.forEach { callback(it) }
            return true
        }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = file,
                type = ExtractorLinkType.VIDEO
            ) {
                quality = Qualities.Unknown.value
                this.headers = headers
                this.referer = referer
            }
        )
        return true
    }

    private suspend fun emitSubtitle(
        subtitleCallback: (SubtitleFile) -> Unit,
        url: String,
        lang: String,
        headers: Map<String, String>
    ) {
        subtitleCallback(
            newSubtitleFile(lang, url) {
                this.headers = headers
            }
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The episode list gives no per-episode sub/dub flags — that only comes from
     * the server list — so the data string carries the category, then the id.
     */
    private fun EpisodeRef.toEpisode(cat: String, poster: String?) = newEpisode(
        "$PROVIDER_KEY|$cat|$id",
        {
            name = title
            season = 1
            episode = number
            posterUrl = poster
        },
        fix = false
    )

    private fun Card.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(title, watchUrlOf(slug), TvType.Anime) {
            posterUrl = cover
        }

    private fun catLabel(cat: String) = if (cat == CAT_DUB) "Dub" else "Sub"

    private fun watchUrlOf(slug: String) = "$mainUrl/watch/$slug"

    /** Accepts `hianime.at/watch/<slug>`, a bare `<slug>` and anything in between. */
    private fun slugOfUrl(url: String): String? {
        val tail = url.substringAfter("$mainUrl/", url)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        val slug = tail.substringAfter("watch/", tail).substringBefore('/')
        return slug.takeIf { it.isNotEmpty() }
    }

    private fun animeIdOf(slug: String): String? =
        TRAILING_ID_RE.find(slug)?.groupValues?.get(1)

    /** Scheme + host of an embed url — the CDN the sources endpoint lives on. */
    private fun baseOf(embed: String): String? =
        BASE_RE.find(embed)?.groupValues?.get(1)

    /**
     * The cut (sub/hsub/dub) an embed url was issued for, spelled out in the path.
     */
    private fun cutOf(embed: String): String? =
        CUT_RE.find(embed)?.groupValues?.get(1)

    private fun serverRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("vidplay") -> 0
            n.contains("zoko") -> 1
            n.contains("vidstream") -> 2
            n.contains("hd") -> 3
            else -> 5
        }
    }

    private fun yearOf(raw: String?): Int? =
        raw?.let { YEAR_RE.find(it)?.groupValues?.get(0) }?.toIntOrNull()

    private fun String?.toShowStatus(): ShowStatus? = when {
        this == null -> null
        contains("complete", ignoreCase = true) -> ShowStatus.Completed
        contains("finish", ignoreCase = true) -> ShowStatus.Completed
        contains("releas", ignoreCase = true) -> ShowStatus.Ongoing
        contains("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
        else -> null
    }

    // --------------------------------------------------------------- fetching

    private fun pageHeaders(referer: String, ajax: Boolean): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = UA
        h["Referer"] = referer
        // The /api/theme routes are called with this by the site itself — they
        // answer without it today, but sending it costs nothing and survives them
        // tightening up.
        if (ajax) h["X-Requested-With"] = "XMLHttpRequest"
        return h
    }

    private suspend fun getText(url: String, referer: String, ajax: Boolean = false): String? {
        val response = runCatching {
            app.get(url = url, headers = pageHeaders(referer, ajax))
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return response.text
    }

    private suspend fun ajax(path: String, referer: String): String? {
        val body = getText(api + path, referer, ajax = true) ?: return null
        val json = runCatching { AppUtils.parseJson<AjaxResponse>(body) }.getOrNull() ?: return null
        return json.html
    }

    private suspend fun fetchServers(epId: String, referer: String): List<Server> {
        val html = ajax("episode/servers?episodeId=$epId", referer) ?: return emptyList()
        val out = ArrayList<Server>()
        for (m in SERVER_RE.findAll(html)) {
            val url = runCatching { base64Decode(m.groupValues[3]) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: continue
            out.add(Server(m.groupValues[1], m.groupValues[2], url))
        }
        return out
    }

    private fun malIdOf(servers: List<Server>): Int? {
        for (s in servers) {
            val id = MAL_ID_RE.find(s.url)?.groupValues?.get(1)?.toIntOrNull()
            if (id != null) return id
        }
        return null
    }

    private fun zokoConfig(html: String): ZokoConfig? {
        val blob = ZOKO_BLOB_RE.find(html)?.groupValues?.get(1) ?: return null
        val bytes = runCatching { base64DecodeArray(blob) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val key = ZOKO_KEY
        val out = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor key[i % key.length].code).toByte()
        }
        return runCatching { AppUtils.parseJson<ZokoConfig>(String(out, Charsets.UTF_8)) }
            .getOrNull()
    }

    // ------------------------------------------------------------------ cards

    /**
     * The three card shapes on the site (grid flw-item, home spotlight
     * deslide-item, trending item) differ in layout but all carry a
     * film-poster-img `<img>` whose alt is the title, plus a slug link, so one
     * parser covers the lot. The poster is what tells a card apart from the site
     * furniture that also sits in a `class="item"` (the az-list, the login menu).
     */
    private fun cardsOf(seg: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        // Splitting on the card containers alone leaves the last card running to
        // the end of whatever was passed in, so it reads its badges off the
        // sidebar or the footer — end the run at the enclosing section too.
        val chunks = CARD_SPLIT_RE.split(seg)
        for (i in 1 until chunks.size) {
            val chunk = chunks[i]
            val img = CARD_IMG_RE.find(chunk)?.value ?: continue
            val slug = SLUG_RE.find(chunk)?.groupValues?.get(1) ?: continue
            if (!seen.add(slug)) continue
            val raw = ALT_RE.find(img)?.groupValues?.get(1)
                ?: TITLE_ATTR_RE.find(chunk)?.groupValues?.get(1)
            val title = text(raw)
            if (title.isEmpty()) continue
            out.add(
                Card(
                    title = title,
                    slug = slug,
                    cover = SRC_ATTR_RE.find(img)?.groupValues?.get(1)
                )
            )
        }
        return out
    }

    private fun episodesOf(html: String): List<EpisodeRef> {
        val out = ArrayList<EpisodeRef>()
        val seen = HashSet<String>()
        for (m in EP_ITEM_RE.findAll(html)) {
            val attrs = m.groupValues[1]
            val epId = DATA_ID_RE.find(attrs)?.groupValues?.get(1) ?: continue
            if (!seen.add(epId)) continue
            val number = DATA_NUMBER_RE.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                ?: (out.size + 1)
            val title = text(TITLE_ATTR_RE.find(attrs)?.groupValues?.get(1))
            out.add(EpisodeRef(number, title.ifEmpty { "Episode $number" }, epId))
        }
        return out
    }

    private fun infoOf(html: String, label: String): String? =
        infoRe(label).find(html)?.groupValues?.get(1)
            ?.let { text(it) }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Anchor texts out of one sidebar row. Bounded to that row's block: the page
     * also carries an all-genres widget and a promo blurb that links studios, and
     * scanning the whole document picks those up as the title's own.
     */
    private fun infoList(html: String, label: String, cap: Int): List<String> {
        val block = infoBlockRe(label).find(html)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (m in ANCHOR_TEXT_RE.findAll(block)) {
            if (out.size >= cap) break
            val t = text(m.groupValues[1])
            if (t.isNotEmpty() && seen.add(t)) out.add(t)
        }
        return out
    }

    // ------------------------------------------------------------------ text

    private fun text(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return htmlText(raw.replace(TAG_RE, " ")).replace(WS_RE, " ").trim()
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

    private var homeCache: Pair<Long, List<HomeRow>>? = null
    private val detailCache = HashMap<String, Pair<Long, Detail>>()
    private val cacheLock = Any()

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        const val DATA_SPOTLIGHT = "spotlight"
        const val DATA_TRENDING = "trending"
        const val DATA_LATEST = "latest-episode"
        const val DATA_NEW = "new-on-hianime"
        const val DATA_UPCOMING = "top-upcoming"

        const val PROVIDER_KEY = "hianime"
        const val CAT_SUB = "sub"
        const val CAT_DUB = "dub"

        const val CACHE_TTL_MS = 300_000L
        const val CACHE_MAX = 32
        const val MAX_EPISODES = 5000

        /** Fixed XOR key from the player's own core/obfuscate.js. */
        const val ZOKO_KEY = "otaku-embed-v1"

        /** Embed hosts that hand back a plain m3u8 (via getSources/getSourcesNew). */
        val PLAYER_RE = Regex(
            "^https?://(?:[a-z0-9-]+\\.)?(?:vidtube\\.[a-z]+|megaplay\\.[a-z]+)",
            RegexOption.IGNORE_CASE
        )

        /** Zoko is extracted differently — its embed page carries the config. */
        val ZOKO_RE = Regex(
            "^https?://(?:[a-z0-9-]+\\.)?zokoanime\\.[a-z]+",
            RegexOption.IGNORE_CASE
        )
        val MEGAPLAY_RE = Regex("megaplay\\.[a-z]+", RegexOption.IGNORE_CASE)
        val M3U8_RE = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE)

        val BASE_RE = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
        val CUT_RE = Regex("/(sub|hsub|dub)/?(?:[?#]|\$)", RegexOption.IGNORE_CASE)
        val MAL_ID_RE = Regex("/mal/(\\d+)/")

        val TAG_RE = Regex("<[^>]*>")
        val WS_RE = Regex("\\s+")
        val YEAR_RE = Regex("(19|20)\\d{2}")
        val SLUG_RE = Regex("href=\"[^\"]*/(?:watch/)?([a-z0-9][a-z0-9-]*-\\d+)(?:\\?[^\"]*)?\"", RegexOption.IGNORE_CASE)
        val TRAILING_ID_RE = Regex("-(\\d+)$")

        val HEADING_RE = Regex("<h2 class=\"cat-heading[^\"]*\">\\s*([^<]+?)\\s*</h2>")
        val CARD_SPLIT_RE = Regex("class=\"(?:flw-item|deslide-item|item)[\\s\"]|</section|<footer")
        val CARD_IMG_RE = Regex("<img\\b[^>]*film-poster-img[^>]*>", RegexOption.IGNORE_CASE)
        val ALT_RE = Regex("alt=\"([^\"]+?)(?:\\s+Poster)?\"", RegexOption.IGNORE_CASE)
        val TITLE_ATTR_RE = Regex("title=\"([^\"]*)\"")
        val SRC_ATTR_RE = Regex("src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

        val FILM_NAME_RE = Regex("<h2[^>]*class=\"[^\"]*film-name[^\"]*\"[^>]*>([\\s\\S]*?)</h2>")
        val POSTER_RE = Regex("class=\"anisc-poster\"[\\s\\S]{0,400}?<img[^>]+src=\"([^\"]+)\"")
        val DESCRIPTION_RE =
            Regex("class=\"film-description[^\"]*\"[\\s\\S]{0,200}?<div class=\"text\">([\\s\\S]*?)</div>")
        val ANIME_ID_ATTR_RE = Regex("data-animeid=\"(\\d+)\"")

        val ANCHOR_TEXT_RE = Regex("<a\\b[^>]*>\\s*([^<]+?)\\s*<")
        val EP_ITEM_RE = Regex("<a\\b([^>]*\\bep-item\\b[^>]*)>")
        val DATA_ID_RE = Regex("data-id=\"(\\d+)\"")
        val DATA_NUMBER_RE = Regex("data-number=\"(\\d+)\"")

        val SERVER_RE = Regex(
            "data-type=\"(\\w+)\"[\\s\\S]{0,200}?data-server-name=\"([^\"]+)\"[\\s\\S]{0,200}?data-hash=\"([^\"]+)\""
        )
        val EMBED_DATA_ID_RE = Regex("data-id=\"(\\d+)\"")
        val ZOKO_BLOB_RE = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"")

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

        fun infoRe(label: String) = Regex(
            "item-head\">${Regex.escape(label)}:</span>\\s*<span class=\"name\">([\\s\\S]*?)</span>"
        )

        fun infoBlockRe(label: String) = Regex(
            "item-head\">${Regex.escape(label)}:</span>([\\s\\S]*?)</div>"
        )
    }
}

/** `sources` is either `{file}`, `[{file}]` or a bare url string. */
private fun JsonElement.sourceFile(): String? {
    val node = this
    return when (node) {
        is JsonPrimitive -> node.contentOrNull
        is JsonObject -> (node["file"] as? JsonPrimitive)?.contentOrNull
        is JsonArray -> node.firstOrNull()?.sourceFile()
        else -> null
    }
}