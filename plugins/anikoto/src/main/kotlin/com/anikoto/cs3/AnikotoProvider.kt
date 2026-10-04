package com.anikoto.cs3

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
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
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AniKoto — anime source for anikototv.to.
 *
 * Port of the anikoto.js provider from the upstream provider repo.
 *
 * anikototv.to is an aniwatch-style site. Streams are NOT keyed by the episode id
 * — each episode carries an encrypted `data-ids` (server_ids) blob, and the real
 * player is resolved through the site's own two-step server chain, then extracted
 * from the embed host (plain m3u8 + subtitle tracks):
 *   /filter?keyword=            -> slug
 *   /watch/<slug>               -> anime id (data-id) + metadata
 *   /ajax/episode/list/<id>     -> [{ data-id, num, sub, dub, data-ids(server_ids) }]
 *   /ajax/server/list?servers=<server_ids> -> server list (VidPlay/HD/Vidstream/…)
 *   /ajax/server?get=<link_id>  -> { url: <player embed>, skip_data }
 *   <embed host>/stream/getSources(New)?id=<embed data-id> -> m3u8 + subs
 *   (MegaPlay/VidWish: both getSources and getSourcesNew now return an AES-CBC
 *    `enc` blob instead of plain sources.file — decrypted with the player's own
 *    TRUST_AES_KEY/IV from megaplay's newclient. VidPlay/vidtube still plain.)
 *
 * Some episodes only list servers that no longer hand back a plain file. For those
 * the site's own player asks a mapper API for EXTRA servers, keyed by the
 * <mal>/<episode>/<timestamp> the episode anchors already carry, so we carry those
 * three through the episode data and ask the same API when the episode's own
 * servers all come up empty.
 *
 * Home = /home spotlight (hero) + recent from the JSON API (anikotoapi.site);
 * slugs are shared with the site, so those cards resolve through load().
 */
class AnikotoProvider : MainAPI() {

    override var name = "AniKoto"
    override var mainUrl = "https://anikototv.to"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None
    override val hasChromecastSupport = false

    override val mainPage = listOf(
        MainPageData("Spotlight", DATA_SPOTLIGHT),
        MainPageData("Top Anime", DATA_TOP),
        MainPageData("Recently Updated", DATA_RECENT)
    )

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items: List<SearchResponse> = when (request.data) {
            DATA_SPOTLIGHT -> {
                val html = get(HOME_URL) ?: return null
                parseSpotlight(sections(html)[SECTION_SPOTLIGHT] ?: html)
            }

            DATA_TOP -> {
                val html = get(HOME_URL) ?: return null
                parseGrid(sections(html)[SECTION_TOP].orEmpty())
            }

            DATA_RECENT -> recentItems()
            else -> return null
        }.map { it.toSearchResponse() }

        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return searchCards(q, 1).map { it.toSearchResponse() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val cards = searchCards(q, page)
        return newSearchResponseList(cards.map { it.toSearchResponse() }, cards.isNotEmpty())
    }

    private suspend fun searchCards(query: String, page: Int): List<Card> {
        val html = get("$SITE/filter?keyword=${query.encodeUrl()}&page=$page") ?: return emptyList()
        return parseCards(html.split(SEARCH_CARD_SPLIT))
    }

    // ------------------------------------------------------- home sub-sources

    /**
     * /home spotlight cards carry the title in an `<h2 …d-title>` + a bg-image.
     * Generic grid (#top-anime a.item, #recent-update div.item) -> cards.
     */
    private suspend fun recentItems(): List<Card> {
        val fromApi = runCatching { apiRecent() }.getOrDefault(emptyList())
        if (fromApi.isNotEmpty()) return fromApi
        // Fallback: the JSON API's recent list if the /home scrape yielded nothing.
        val html = get(HOME_URL) ?: return emptyList()
        return parseGrid(sections(html)[SECTION_RECENT].orEmpty())
    }

    private suspend fun apiRecent(): List<Card> {
        val body = get("$API/recent-anime?page=1&per_page=24") ?: return emptyList()
        val json = runCatching { AppUtils.parseJson<ApiRecent>(body) }.getOrNull() ?: return emptyList()
        return json.data.orEmpty().mapNotNull { entry ->
            val slug = entry.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Card(
                slug = slug,
                title = entry.title?.takeIf { it.isNotBlank() }
                    ?: entry.alternative?.takeIf { it.isNotBlank() }
                    ?: slug,
                cover = entry.poster
            )
        }
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromUrl(url) ?: return null
        val html = get("$SITE/watch/${slug.encodeUrl()}") ?: return null

        val animeId = DATA_ID.find(html)?.groupValues?.get(1)
        // The canonical clean title is the <h1 class="… d-title">; og:title is
        // marketing fluff ("Watch X Anime Online Free"), used only as a fallback.
        val title = H1_TITLE.find(html)?.groupValues?.get(1)?.htmlToText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: OG_TITLE.find(html)?.groupValues?.get(1)?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: slug
        val poster = OG_IMAGE.find(html)?.groupValues?.get(1)
        val synopsis = SYNOPSIS.find(html)?.groupValues?.get(1)?.htmlToText()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val genres = parseGenres(html)
        val year = YEAR_PATTERN.find(html)?.value?.toIntOrNull()

        val infos = animeId?.let { parseEpisodeInfos(it) }.orEmpty()
        val malId = infos.firstNotNullOfOrNull { it.malId }

        val episodes: List<Episode> = infos.map { info ->
            newEpisode(
                info.toData(),
                {
                    name = info.title?.takeIf { it.isNotBlank() } ?: "Episode ${info.num}"
                    season = 1
                    episode = info.num
                    posterUrl = poster
                },
                fix = false
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres.takeIf { it.isNotEmpty() }
            addMalId(malId)
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = parseEpisodeData(data) ?: return false
        val playable = runCatching { resolvePlayable(info) }.getOrNull() ?: return false

        for (track in playable.subs) {
            subtitleCallback(
                newSubtitleFile(track.label, track.url) {
                    headers = playable.headers
                }
            )
        }

        val label = listOfNotNull(
            if (info.cat == CAT_DUB) "Dub" else "Sub",
            if (playable.isM3u8) "HLS" else "MP4"
        ).joinToString(" • ")

        var emitted = false
        if (playable.isM3u8) {
            val links = runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = playable.url,
                    referer = playable.referer,
                    quality = null,
                    headers = playable.headers,
                    name = label
                )
            }.getOrDefault(emptyList())
            if (links.isNotEmpty()) {
                links.forEach(callback)
                emitted = true
            }
        }
        if (!emitted) {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = playable.url,
                    type = if (playable.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = playable.referer
                    quality = Qualities.Unknown.value
                    headers = playable.headers
                }
            )
            emitted = true
        }
        return emitted
    }

    // -------------------------------------------------------------- episodes

    /**
     * `<a data-id="…" data-num="…" data-sub="…" data-dub="…" data-ids="…" …>`
     * anchors. `data-ids` (server_ids) is the only thing that makes an episode
     * playable, so an anchor without it is skipped entirely.
     */
    private suspend fun parseEpisodeInfos(animeId: String): List<EpisodeInfo> {
        val html = ajaxHtml("/ajax/episode/list/$animeId") ?: return emptyList()
        val out = ArrayList<EpisodeInfo>()
        for (match in EPISODE_ANCHOR.findAll(html)) {
            val attrs = match.groupValues[1]
            val serverIds = ATTR_SERVER_IDS.find(attrs)?.groupValues?.get(1) ?: continue
            val num = ATTR_NUM.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val sub = ATTR_SUB.find(attrs)?.groupValues?.get(1) == "1"
            val dub = ATTR_DUB.find(attrs)?.groupValues?.get(1) == "1"
            if (!sub && !dub) continue
            // The mapper is keyed per episode, so slug/timestamp come off THIS
            // anchor; data-slug is the episode number the API wants, not the show
            // slug. All three or none — a partial token is a 404.
            val mal = ATTR_MAL.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
            val epSlug = ATTR_SLUG.find(attrs)?.groupValues?.get(1)
            val timestamp = ATTR_TIMESTAMP.find(attrs)?.groupValues?.get(1)
            val token = if (mal != null && !epSlug.isNullOrBlank() && !timestamp.isNullOrBlank())
                "$mal/${epSlug.encodeUrl()}/$timestamp"
            else
                null
            out += EpisodeInfo(
                cat = CAT_SUB,
                num = num,
                sub = sub,
                dub = dub,
                serverIds = serverIds,
                token = token,
                malId = mal,
                title = ATTR_TITLE.find(attrs)?.groupValues?.get(1)?.htmlToText()?.trim()
            )
        }
        return out
    }

    /**
     * Self-contained episode data:
     * `anikoto|<cat>~<serverIds>~<sub>~<dub>~<num>~<mapperToken>`
     *
     * `~` is never produced by [encodeField] (it percent-escapes it), so the
     * separator is unambiguous.
     */
    private fun EpisodeInfo.toData(): String = EPISODE_PREFIX + listOf(
        cat,
        serverIds.encodeField(),
        if (sub) "1" else "0",
        if (dub) "1" else "0",
        num.toString(),
        token.orEmpty()
    ).joinToString(EPISODE_SEPARATOR)

    private fun parseEpisodeData(data: String): EpisodeInfo? {
        if (!data.startsWith(EPISODE_PREFIX)) return null
        val parts = data.removePrefix(EPISODE_PREFIX).split(EPISODE_SEPARATOR)
        if (parts.size < 6) return null
        val serverIds = parts[1].decodeField().takeIf { it.isNotBlank() } ?: return null
        return EpisodeInfo(
            cat = if (parts[0] == CAT_DUB) CAT_DUB else CAT_SUB,
            num = parts[4].toIntOrNull() ?: 0,
            sub = parts[2] == "1",
            dub = parts[3] == "1",
            serverIds = serverIds,
            token = parts[5].takeIf { it.isNotBlank() },
            malId = null,
            title = null
        )
    }

    // ------------------------------------------------------- stream pipeline
    //
    // server_ids -> server list -> server?get -> embed getSources(New)

    private suspend fun resolvePlayable(info: EpisodeInfo): Playable {
        val listHtml = ajaxHtml("/ajax/server/list?servers=${info.serverIds.encodeField()}").orEmpty()
        val all = parseServers(listHtml)
        val wanted = all.filter { server ->
            if (info.cat == CAT_DUB) server.type == CAT_DUB
            else server.type == CAT_SUB || server.type == CAT_HSUB
        }
        val servers = (wanted.ifEmpty { all }).sortedBy { serverRank(it.name) }
        tryServers(servers, info)?.let { return it }

        // Only once the episode's own servers are exhausted — VidPlay is a direct
        // m3u8 and one request cheaper, so it stays first.
        val token = info.token
            ?: throw IllegalStateException("AniKoto: no playable server")
        val extra = mapperServers(token, info.cat)
            ?: throw IllegalStateException("AniKoto: no playable server")
        return tryServers(extra, info)
            ?: throw IllegalStateException("AniKoto: no playable server")
    }

    /**
     * Extra servers for episodes whose own list is all dead hosts. The site's
     * player asks the same API and appends whatever it returns as ordinary
     * servers, so an entry's `url` is just another link id for the
     * /ajax/server?get= route — it drops straight back into [tryServers].
     *
     * Only the `url` entries are used. An entry can also carry a `download` map of
     * quality -> redirector link, but those all end up on kwik, and kwik blocks
     * every HTTP/1.1 request outright (Cloudflare 1020, cookies and User-Agent
     * make no difference).
     */
    private suspend fun mapperServers(token: String, cat: String): List<Server>? {
        val body = get("$MAPPER$token") ?: return null
        val root = runCatching { LENIENT_JSON.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return null
        val out = ArrayList<Server>()
        for ((name, value) in root) {
            if (name == "status" || value !is JsonObject) continue
            val bucket = value[cat] as? JsonObject ?: continue
            val url = strOf(bucket["url"]) ?: continue
            out += Server(type = cat, linkId = url, name = name)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Resolve servers in preference order; take the first that yields a known
     * embed host, then extract its m3u8. A host that no longer returns a plain
     * file (see [PLAYER_RE]) just throws and we fall through to the next server.
     */
    private suspend fun tryServers(list: List<Server>, info: EpisodeInfo): Playable? {
        for (server in list) {
            val payload = runCatching { ajax("/ajax/server?get=${server.linkId.encodeField()}") }
                .getOrNull() ?: continue
            val url = strOf((payload.result as? JsonObject)?.get("url")) ?: continue
            if (!PLAYER_RE.containsMatchIn(url)) continue
            val playable = runCatching { extractPlayer(url, info.cat) }.getOrNull()
            if (playable != null) return playable
        }
        return null
    }

    private fun parseServers(html: String): List<Server> {
        val out = ArrayList<Server>()
        for (block in SERVER_BLOCK.findAll(html)) {
            val type = block.groupValues[1]
            for (link in SERVER_LINK.findAll(block.groupValues[2])) {
                out += Server(
                    type = type,
                    linkId = link.groupValues[1],
                    name = link.groupValues[2].trim()
                )
            }
        }
        return out
    }

    /** Prefer VidPlay; Vidstream/HD (MegaPlay) work again via the `enc` decrypt. */
    private fun serverRank(name: String): Int {
        val n = name.lowercase()
        return when {
            "vidplay" in n -> 0
            "vidstream" in n -> 1
            "hd" in n -> 2
            "vidcloud" in n -> 3
            else -> 5
        }
    }

    // ------------------------------------------------------ embed extraction

    /**
     * MegaPlay/VidWish: getSourcesNew is the endpoint their player hits; both
     * routes now encrypt the file into `enc`. VidPlay (vidtube) only exposes
     * getSources.
     */
    private fun sourcesUrl(base: String, dataId: String, type: String): String {
        val path = if (MEGA_HOST.containsMatchIn(base)) "/stream/getSourcesNew" else "/stream/getSources"
        return "$base$path?id=$dataId&type=$type"
    }

    /** The sub/hsub/dub cut an embed URL was issued for. */
    private fun cutOf(embed: String): String? =
        CUT_OF.find(embed)?.groupValues?.get(1)?.lowercase()

    /**
     * Embed page -> data-id -> getSources(New) (plain m3u8 + subtitle tracks).
     *
     * Sources are keyed by the embed's data-id, which is shared across the
     * sub/hsub/dub cuts — the audio comes from `type`, so carry over the one this
     * embed was issued for or a dub episode comes back with the sub stream.
     *
     * NOTE: the JS also probes the sibling (sub<->dub) pack when the video and VTT
     * packs disagree, purely to derive a subtitle sync offset. CloudStream's
     * SubtitleFile has no skew field, so that second pack is never fetched here.
     */
    private suspend fun extractPlayer(embed: String, cat: String): Playable {
        val base = ORIGIN.find(embed)?.groupValues?.get(1) ?: DEFAULT_PLAYER
        val type = cutOf(embed) ?: cat

        val embedHtml = get(embed) ?: throw IllegalStateException("AniKoto: no embed id")
        val dataId = DATA_ID.find(embedHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("AniKoto: no embed id")

        val body = get(sourcesUrl(base, dataId, type), referer = embed, xhr = true)
            ?: throw IllegalStateException("AniKoto: bad getSources")
        val payload = runCatching { AppUtils.parseJson<SourcesPayload>(body) }.getOrNull()
            ?: throw IllegalStateException("AniKoto: bad getSources")

        val file = fileFromSources(payload)
            ?: throw IllegalStateException("AniKoto: no stream file")

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to "$base/",
            "Origin" to base
        )
        val subs = payload.tracks.orEmpty().mapNotNull { track ->
            val url = track.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = track.kind
            if (kind != null && kind != "captions" && kind != "subtitles") return@mapNotNull null
            PlayerSub(url = url, label = track.label?.takeIf { it.isNotBlank() } ?: "Sub")
        }

        return Playable(url = file, headers = headers, referer = "$base/", subs = subs)
    }

    /**
     * Plain `sources.file` when present; otherwise AES-CBC-decrypt `enc` ->
     * `{file}` / `{sources:{file}}` / `{sources:[{file}]}`.
     */
    private fun fileFromSources(payload: SourcesPayload): String? {
        fileOf(payload.sources)?.let { return it }
        payload.file?.let { return it }
        val enc = payload.enc ?: return null
        val plain = decryptEnc(enc) ?: return null
        val obj = runCatching { LENIENT_JSON.parseToJsonElement(plain).jsonObject }
            .getOrNull() ?: return null
        strOf(obj["file"])?.let { return it }
        return fileOf(obj["sources"])
    }

    private fun fileOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> strOf(element["file"])
        is JsonArray -> element.firstNotNullOfOrNull { fileOf(it) }
        else -> null
    }

    private fun strOf(element: JsonElement?): String? = element?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }

    // ------------------------------------------------------- MegaPlay `enc`
    //
    // The player's TRUST_AES_KEY/IV from megaplay's newclient.min.js decrypt the
    // file blob. The JS bridges this in pure JS; here the JVM's AES-256-CBC with
    // PKCS#7 padding is the exact same primitive, with the same key/iv bytes.

    private fun decryptEnc(enc: String): String? {
        val cipherText = runCatching { Base64.getUrlDecoder().decode(enc) }.getOrNull() ?: return null
        if (cipherText.isEmpty() || cipherText.size % 16 != 0) return null
        val plain = runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(padBytes(MEGA_AES_KEY, 32), "AES"),
                    IvParameterSpec(padBytes(MEGA_AES_IV, 16))
                )
                doFinal(cipherText)
            }
        }.getOrNull() ?: return null
        return String(plain, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /** Zero-pads the key material to the AES key/iv size, as the player does. */
    private fun padBytes(value: String, size: Int): ByteArray = ByteArray(size) { index ->
        if (index < value.length) (value[index].code and 0xff).toByte() else 0
    }

    // ------------------------------------------------------------- scraping

    private fun parseCards(chunks: List<String>): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (chunk in chunks.drop(1)) {
            val slug = slugFromWatch(chunk) ?: continue
            if (!seen.add(slug)) continue
            val title = (find(CARD_NAME_TITLE, chunk) ?: find(CARD_JP, chunk))
                ?.htmlToText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            out += Card(
                slug = slug,
                title = title,
                cover = find(IMG_DATA_SRC, chunk) ?: find(IMG_SRC, chunk)
            )
        }
        return out
    }

    private fun parseSpotlight(segment: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (chunk in segment.split(SPOTLIGHT_SPLIT).drop(1)) {
            val slug = slugFromWatch(chunk) ?: continue
            if (!seen.add(slug)) continue
            val title = (find(SPOT_TITLE, chunk) ?: find(CARD_JP, chunk))
                ?.htmlToText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val image = (find(SPOT_BG_IMAGE, chunk) ?: find(IMG_DATA_SRC, chunk) ?: find(IMG_SRC, chunk))
                ?.trim('\'', '"')
            out += Card(
                slug = slug,
                title = title,
                cover = image
            )
        }
        return out
    }

    private fun parseGrid(segment: String): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (chunk in segment.split(GRID_SPLIT).drop(1)) {
            val slug = slugFromWatch(chunk) ?: continue
            if (!seen.add(slug)) continue
            val title = (find(GRID_TITLE, chunk) ?: find(GRID_NAME, chunk) ?: find(CARD_JP, chunk))
                ?.htmlToText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            out += Card(
                slug = slug,
                title = title,
                cover = find(IMG_DATA_SRC, chunk) ?: find(IMG_SRC, chunk)
            )
        }
        return out
    }

    /** Slice the /home page into its section containers, bounded by the next one. */
    private fun sections(html: String): Map<String, String> {
        val marks = SECTION_IDS
            .map { id -> id to html.indexOf("id=\"$id\"") }
            .filter { it.second >= 0 }
            .sortedBy { it.second }
        val out = LinkedHashMap<String, String>()
        marks.forEachIndexed { index, (id, at) ->
            val end = marks.getOrNull(index + 1)?.second ?: (at + 30000)
            out[id] = html.substring(at, minOf(end, html.length))
        }
        return out
    }

    private fun parseGenres(html: String): List<String> {
        val out = ArrayList<String>()
        for (match in GENRE.findAll(html)) {
            if (out.size >= 8) break
            match.groupValues[1].htmlToText().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /** Strips the site-name/marketing fluff the og:title carries. */
    private fun String.cleanTitle(): String {
        var out = this
        out = SITE_NAME_SUFFIX.replace(out, "")     // trailing site name
        out = LEADING_FLUFF.replace(out, "")          // leading "Watch "/"Anime "
        out = ONLINE_SUFFIX.replace(out, "")          // "X Anime Online …"
        out = WATCH_SUFFIX.replace(out, "")           // "X Watch Online Free"
        out = ONLINE_WITH_SUFFIX.replace(out, "")     // "X Online with SUB/DUB"
        return out.trim()
    }

    private fun String.htmlToText(): String = HTML_TAG
        .replace(this, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    // ----------------------------------------------------------------- http

    /** GET a page as text. `xhr` sets the ajax header the /ajax routes expect. */
    private suspend fun get(
        url: String,
        referer: String = "$SITE/",
        xhr: Boolean = false
    ): String? {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Referer"] = referer
        if (xhr) headers["X-Requested-With"] = "XMLHttpRequest"
        val response = runCatching { app.get(url = url, headers = headers) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        return response.text
    }

    /** The site's /ajax routes wrap their payload in `{ status, result }`. */
    private suspend fun ajax(path: String): AjaxPayload? {
        val body = get("$SITE$path", xhr = true) ?: return null
        return runCatching { AppUtils.parseJson<AjaxPayload>(body) }.getOrNull()
    }

    /** `result` is an HTML string for the list routes, an object for `server?get`. */
    private suspend fun ajaxHtml(path: String): String? =
        ajax(path)?.let { strOf(it.result) }

    // ----------------------------------------------------------------- urls

    /** `https://anikototv.to/watch/<slug>` -> `<slug>` */
    private fun slugFromUrl(url: String): String? {
        if (!url.contains("$SITE/watch/")) return null
        val slug = url.substringAfter("$SITE/watch/")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return slug.takeIf { it.isNotBlank() }
    }

    private fun slugFromWatch(chunk: String): String? =
        WATCH_HREF.find(chunk)?.groupValues?.get(1)?.let { WATCH_SLUG.find(it)?.groupValues?.get(1) }

    private fun find(pattern: Regex, input: String): String? =
        pattern.find(input)?.groupValues?.get(1)

    /**
     * Percent-encoding that is guaranteed to escape the `~` separator, unlike
     * ktor's parameter encoder.
     */
    private fun String.encodeField(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun String.decodeField(): String =
        runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    // ---------------------------------------------------------------- models

    private data class Card(val slug: String, val title: String, val cover: String?)

    private fun Card.toSearchResponse(): SearchResponse =
        newAnimeSearchResponse(title, "$SITE/watch/$slug", TvType.Anime, fix = false) {
            posterUrl = cover
        }

    private data class EpisodeInfo(
        val cat: String,
        val num: Int,
        val sub: Boolean,
        val dub: Boolean,
        val serverIds: String,
        val token: String?,
        val malId: Int?,
        val title: String?
    )

    private data class Server(val type: String, val linkId: String, val name: String)

    private data class PlayerSub(val url: String, val label: String)

    private data class Playable(
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
        val subs: List<PlayerSub>
    ) {
        val isM3u8: Boolean get() = M3U8_URL.containsMatchIn(url)
    }

    @Serializable
    private data class AjaxPayload(
        val status: Int? = null,
        val result: JsonElement? = null
    )

    @Serializable
    private data class SourcesPayload(
        val file: String? = null,
        val sources: JsonElement? = null,
        val tracks: List<Track>? = null,
        val enc: String? = null
    )

    @Serializable
    private data class Track(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        @SerialName("default") val isDefault: Boolean? = null
    )

    @Serializable
    private data class ApiRecent(val data: List<ApiAnime>? = null)

    @Serializable
    private data class ApiAnime(
        val slug: String? = null,
        val title: String? = null,
        val alternative: String? = null,
        val poster: String? = null,
        @SerialName("is_sub") val isSub: Int? = null
    )

    private companion object {
        const val SITE = "https://anikototv.to"
        const val HOME_URL = "$SITE/home"
        const val API = "https://anikotoapi.site"

        /** Extra servers the episode's own list doesn't offer, keyed <mal>/<ep>/<ts>. */
        const val MAPPER = "https://mapper.nekostream.site/api/mal/"

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        const val DATA_SPOTLIGHT = "spotlight"
        const val DATA_TOP = "top-anime"
        const val DATA_RECENT = "recent-update"

        const val SECTION_SPOTLIGHT = "hotest"
        const val SECTION_TOP = "top-anime"
        const val SECTION_RECENT = "recent-update"
        val SECTION_IDS = listOf(SECTION_SPOTLIGHT, SECTION_TOP, SECTION_RECENT)

        const val CAT_SUB = "sub"
        const val CAT_HSUB = "hsub"
        const val CAT_DUB = "dub"

        const val DEFAULT_PLAYER = "https://vidtube.site"

        const val EPISODE_PREFIX = "anikoto|"
        const val EPISODE_SEPARATOR = "~"

        /** MegaPlay player defaults (newclient.min.js TRUST_AES_KEY / TRUST_AES_IV). */
        const val MEGA_AES_KEY = "i?LMTAx0Q6,:}50U"
        const val MEGA_AES_IV = "W0;27ToaUpl_P%'c"

        val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Embed hosts that hand back a plain m3u8 (via getSources or getSourcesNew). */
        val PLAYER_RE = Regex(
            "^https?://(?:[a-z0-9-]+\\.)?(?:vidtube\\.[a-z]+|megaplay\\.[a-z]+|vidwish\\.[a-z]+)",
            RegexOption.IGNORE_CASE
        )
        val MEGA_HOST = Regex("(?:megaplay|vidwish)\\.[a-z]+", RegexOption.IGNORE_CASE)
        val ORIGIN = Regex("^(https?://[^/]+)")
        val CUT_OF = Regex("/(sub|hsub|dub)/?(?:[?#]|$)", RegexOption.IGNORE_CASE)
        val M3U8_URL = Regex("\\.m3u8(?:\\?|$)", RegexOption.IGNORE_CASE)

        val SEARCH_CARD_SPLIT = "<div class=\"item"
        val GRID_SPLIT = "class=\"item"
        val SPOTLIGHT_SPLIT = "swiper-slide item"

        val WATCH_HREF = Regex("href=\"([^\"]*/watch/[^\"]+)\"")
        val WATCH_SLUG = Regex("/watch/([^/\"?#]+)")
        val DATA_ID = Regex("data-id=\"(\\d+)\"")

        val CARD_NAME_TITLE = Regex("class=\"name d-title\"[^>]*>([^<]+)<")
        val CARD_JP = Regex("data-jp=\"([^\"]+)\"")
        val SPOT_TITLE = Regex("class=\"title d-title\"[^>]*>\\s*([^<]+?)\\s*<")
        val SPOT_BG_IMAGE = Regex("background-image:\\s*url\\(([^)]+)\\)")
        val GRID_TITLE = Regex("class=\"(?:name|title)[^\"]*d-title\"[^>]*>\\s*([^<]+?)\\s*<")
        val GRID_NAME = Regex("class=\"name\"[^>]*>\\s*([^<]+?)\\s*<")
        val IMG_DATA_SRC = Regex("<img[^>]+data-src=\"([^\"]+)\"")
        val IMG_SRC = Regex("<img[^>]+src=\"([^\"]+)\"")

        val H1_TITLE = Regex("<h1[^>]*class=\"[^\"]*d-title[^\"]*\"[^>]*>([^<]+)</h1>")
        val OG_TITLE = Regex("og:title\"\\s+content=\"([^\"]+)\"")
        val OG_IMAGE = Regex("og:image\"\\s+content=\"([^\"]+)\"")
        val SYNOPSIS = Regex("class=\"synopsis[^\"]*\"[^>]*>([\\s\\S]*?)</div>")
        val GENRE = Regex("href=\"[^\"]*/genre/[^\"]*\"[^>]*>([^<]+)<")
        val YEAR_PATTERN = Regex("(19|20)\\d{2}")

        val SITE_NAME_SUFFIX = Regex("\\s*[|-]\\s*Anikoto.*$", RegexOption.IGNORE_CASE)
        val LEADING_FLUFF = Regex("^(?:Watch|Anime)\\s+", RegexOption.IGNORE_CASE)
        val ONLINE_SUFFIX = Regex("\\s+Anime\\s+Online.*$", RegexOption.IGNORE_CASE)
        val WATCH_SUFFIX = Regex("\\s+Watch\\s+Online.*$", RegexOption.IGNORE_CASE)
        val ONLINE_WITH_SUFFIX = Regex("\\s+Online\\s+(?:with|free)\\b.*$", RegexOption.IGNORE_CASE)
        val HTML_TAG = Regex("<[^>]*>")

        val EPISODE_ANCHOR = Regex("<a\\b([^>]*\\bdata-id=\"\\d+\"[^>]*)>")
        val ATTR_SERVER_IDS = Regex("data-ids=\"([^\"]+)\"")
        val ATTR_NUM = Regex("data-num=\"(\\d+)\"")
        val ATTR_SUB = Regex("data-sub=\"(\\d+)\"")
        val ATTR_DUB = Regex("data-dub=\"(\\d+)\"")
        val ATTR_MAL = Regex("data-mal=\"(\\d+)\"")
        val ATTR_SLUG = Regex("data-slug=\"([^\"]+)\"")
        val ATTR_TIMESTAMP = Regex("data-timestamp=\"(\\d+)\"")
        val ATTR_TITLE = Regex("title=\"([^\"]+)\"")

        val SERVER_BLOCK = Regex("data-type=\"(\\w+)\"([\\s\\S]*?)(?=data-type=\"|$)")
        val SERVER_LINK = Regex("data-link-id=\"([^\"]+)\"[^>]*>([^<]*)<")
    }
}