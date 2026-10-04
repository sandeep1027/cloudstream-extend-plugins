package com.torrin.cs3

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.metaproviders.tmdbApiKeyOverride
import com.lagradost.cloudstream3.metaproviders.tmdbRegionOverride
import com.lagradost.cloudstream3.metaproviders.tmdbLanguageOverride
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Torrin — a torrent-powered content dashboard with debrid playback.
 *
 * Discovery:
 *  - Curated "Trending" dashboard rows (movies & TV) baked into the plugin
 *  - Live search through IMDB's public suggestion API (no key)
 *
 * Streams:
 *  - Resolves any IMDb id into magnet links through the public Torrentio
 *    instance (no key)
 *
 * Playback:
 *  - Emits MAGNET extractor links. The app's Torrin debrid integration
 *    (Settings → Player → Torrin) transparently resolves them into signed
 *    direct stream URLs before the player starts. Multi-file releases carry
 *    a `cs_file=<index>` hint so the exact episode file is selected.
 */
class TorrinProvider : MainAPI() {

    override var name = "Torrin"
    override var mainUrl = "https://torrin.app"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val supportedSyncNames = setOf(SyncIdName.Imdb)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Trending Movies", DATA_MOVIES),
        MainPageData("Trending TV Shows", DATA_TV),
        MainPageData("Latest on Netflix", DATA_NETFLIX),
        MainPageData("Latest on Hotstar", DATA_HOTSTAR),
        MainPageData("Latest on ZEE5", DATA_ZEE5),
        MainPageData("Latest on SonyLIV", DATA_SONYLIV)
    )

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items: List<SearchResponse> = when (request.data) {
            DATA_MOVIES -> Curated.MOVIES.map { it.toSearchResult(this) }
            DATA_TV -> Curated.TV.map { it.toSearchResult(this) }
            DATA_NETFLIX, DATA_HOTSTAR, DATA_ZEE5, DATA_SONYLIV ->
                latestOnPlatform(request.data, page).map { it.toSearchResult(this) }
            else -> return null
        }
        if (items.isEmpty()) return null
        // Platform rows support pagination
        val hasNext = request.data in listOf(DATA_NETFLIX, DATA_HOTSTAR, DATA_ZEE5, DATA_SONYLIV)
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------- latest-per-platform rows
    //
    // TMDB discover (latest releases, region from user settings) + a Watch Providers check per
    // title tells us which platforms carry it. Fetches the requested page dynamically.

    private data class PlatformItem(
        val tmdbId: Int,
        val type: String, // "movie" | "tv"
        val name: String,
        val year: Int?,
        val poster: String?
    )

    private suspend fun latestOnPlatform(key: String, page: Int): List<PlatformItem> {
        val platformFilter = when (key) {
            DATA_NETFLIX -> "8"
            DATA_HOTSTAR -> "1899"
            DATA_ZEE5 -> "2329"
            DATA_SONYLIV -> "2836"
            else -> return emptyList()
        }

        val movieItems = fetchPlatformItems("movie", platformFilter, page)
        val tvItems = fetchPlatformItems("tv", platformFilter, page)
        return movieItems + tvItems
    }

    private suspend fun fetchPlatformItems(kind: String, providerId: String, page: Int): List<PlatformItem> {
        val key = TMDB_API_KEY ?: return emptyList()
        val region = tmdbRegionOverride?.takeIf { it.isNotBlank() } ?: "IN"
        val language = tmdbLanguageOverride?.takeIf { it.isNotBlank() } ?: "en-US"
        val dateField = if (kind == "tv") "first_air_date" else "primary_release_date"

        val url = "$TMDB_BASE/discover/$kind?api_key=$key" +
            "&language=$language&region=$region&sort_by=$dateField.desc" +
            "&vote_count.gte=10&with_watch_providers=$providerId&watch_region=$region&page=$page"

        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
            ?: return emptyList()
        if (!response.isSuccessful) return emptyList()

        val discover = runCatching { AppUtils.parseJson<TmdbDiscoverResponse>(response.text) }.getOrNull()
            ?: return emptyList()

        return discover.results.mapNotNull { r ->
            val title = (if (kind == "tv") r.name else r.title)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            PlatformItem(
                r.id, kind, title,
                if (kind == "tv") r.first_air_date?.substringBefore('-')?.toIntOrNull()
                else r.release_date?.substringBefore('-')?.toIntOrNull(),
                posterUrl(r.poster_path)
            )
        }
    }

    private fun posterUrl(path: String?): String? =
        path?.takeIf { it.startsWith("/") }?.let { "$TMDB_IMAGE_BASE$it" }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val letter = q.first().lowercaseChar().toString()
        val encoded = encodePath(q)
        val response = imdbSuggest(letter, encoded) ?: return null
        val seen = HashSet<String>()
        return response.d.asSequence()
            .filter {
                it.id?.startsWith("tt") == true &&
                    (it.qid == "title" || it.qid == "tvSeries")
            }
            .take(MAX_SEARCH_RESULTS)
            .mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                val title = item.l?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                if (item.qid == "tvSeries") {
                    newTvSeriesSearchResponse(title, contentUrl(id, title)) {
                        posterUrl = item.i?.imageUrl
                        year = item.y?.toIntOrNull()
                    }
                } else {
                    newMovieSearchResponse(title, contentUrl(id, title)) {
                        posterUrl = item.i?.imageUrl
                        year = item.y?.toIntOrNull()
                    }
                }
            }
            .toList()
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val parsed = parseUrl(url) ?: return null
        var tt = parsed.first
        val nameHint = parsed.second

        // "tm:movie/12345" (TMDB-sourced dashboard card) -> resolve its IMDb id
        // first; everything downstream (Torrentio) is IMDb-id based.
        if (tt.startsWith("tm:")) {
            val tmType = tt.removePrefix("tm:").substringBefore('/')
            val tmId = tt.substringAfter('/', "").toIntOrNull() ?: return null
            tt = fetchTmdbImdbId(tmType, tmId) ?: return null
        }

        // Probe the movie endpoint first; if it returns per-episode files the
        // id is a series, so fetch the dedicated series endpoint.
        val movieStreams = runCatching { fetchTorrentio("movie", tt) }.getOrNull()
        val isSeries = movieStreams?.any { looksLikeEpisode(it) } == true
        val seriesStreams =
            if (isSeries) runCatching { fetchTorrentio("series", tt) }.getOrNull() else null

        val meta = resolveMeta(tt, nameHint, movieStreams, seriesStreams)

        if (isSeries) {
            val episodes = buildEpisodes(seriesStreams ?: movieStreams.orEmpty(), meta)
            return newTvSeriesLoadResponse(meta.name, url, TvType.TvSeries, episodes) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.poster
                year = meta.year
                plot = meta.synopsis
                addImdbId(tt)
            }
        }

        return newMovieLoadResponse(meta.name, url, TvType.Movie, "m:$tt") {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.poster
            year = meta.year
            plot = meta.synopsis
            addImdbId(tt)
        }
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("m:")) {
            // Full title -> resolve the best magnet per quality tier.
            val tt = data.removePrefix("m:")
            val streams = runCatching { fetchTorrentio("movie", tt) }.getOrNull().orEmpty()
            val bestPerQuality = LinkedHashMap<String, TorrentioStream>()
            for (s in streams) {
                if (s.infoHash.isNullOrBlank()) continue
                val label = qualityLabel(s)
                val current = bestPerQuality[label]
                if (current == null || seeders(s) > seeders(current)) {
                    bestPerQuality[label] = s
                }
            }
            bestPerQuality.entries
                .sortedByDescending { (label, s) -> qualityValue(label) * 100_000L + seeders(s) }
                .take(MAX_MOVIE_LINKS)
                .forEach { (label, s) ->
                    callback(magnetLink(s, label))
                }
            return bestPerQuality.isNotEmpty()
        }

        // Episode data: "infoHash|fileIdx|quality|size|seeders|source"
        val parts = data.split("|")
        val infoHash = parts.getOrNull(0).orEmpty()
        val fileIdx = parts.getOrNull(1)?.toIntOrNull()
        val label = parts.getOrNull(2).orEmpty()
        val size = parts.getOrNull(3).orEmpty()
        val seedCount = parts.getOrNull(4).orEmpty()
        val source = parts.getOrNull(5).orEmpty()
        if (infoHash.length !in 32..64) return false
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            size.takeIf { it.isNotBlank() },
            seedCount.takeIf { it.isNotBlank() && it != "0" }?.let { "$it seeders" },
            source.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        callback(
            newExtractorLink(
                source = name,
                name = details.ifBlank { "Torrent stream" },
                url = magnet(infoHash, fileIdx),
                type = ExtractorLinkType.MAGNET
            ) {
                quality = qualityValue(label)
            }
        )
        return true
    }

    // --------------------------------------------------------- deep linking

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || !id.startsWith("tt")) return null
        return "$mainUrl/$id"
    }

    // -------------------------------------------------------------- helpers

    private data class Meta(
        val name: String,
        val year: Int?,
        val poster: String?,
        val synopsis: String?
    )

    private fun CuratedItem.toSearchResult(api: MainAPI): SearchResponse {
        val item = this
        return if (item.type == TYPE_TV) {
            api.newTvSeriesSearchResponse(item.name, contentUrl(item.id, item.name)) {
                posterUrl = item.poster
                year = item.year
            }
        } else {
            api.newMovieSearchResponse(item.name, contentUrl(item.id, item.name)) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    /** TMDB-sourced dashboard card -> TMDB content url. */
    private fun PlatformItem.toSearchResult(api: MainAPI): SearchResponse {
        val url = "$mainUrl/tm/$type/$tmdbId/${encodePath(name)}"
        return if (type == "tv") {
            api.newTvSeriesSearchResponse(name, url) {
                posterUrl = poster
                year = year
            }
        } else {
            api.newMovieSearchResponse(name, url) {
                posterUrl = poster
                year = year
            }
        }
    }

    /** Absolute content url: {mainUrl}/{tt}/{title} */
    private fun contentUrl(id: String, title: String): String =
        "$mainUrl/$id/${encodePath(title)}"

    /**
     * Extracts the id key and (optionally) the title back out of a content url.
     * Returns null when the url does not point at a known title.
     *
     * Two shapes:
     *  - `torrin.app/{tt}/{title}`        -> ("tt...", title)
     *  - `torrin.app/tm/{type}/{id}/{title}` -> ("tm:{type}/{id}", title)
     */
    private fun parseUrl(url: String): Pair<String, String?>? {
        val tail = url.substringAfter("torrin.app/", url)
        val parts = tail.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() == "tm" && parts.size >= 3 && parts[2].toIntOrNull() != null) {
            val key = "tm:${parts[1]}/${parts[2]}"
            val encoded = parts.drop(3).joinToString("/")
            return key to decodeEncoded(encoded)
        }
        val tt = parts.firstOrNull { it.startsWith("tt") } ?: return null
        val encoded = parts.drop(1).joinToString("/")
        return tt to decodeEncoded(encoded)
    }

    private fun decodeEncoded(encoded: String): String? =
        if (encoded.isEmpty()) null
        else runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: encoded

    private suspend fun imdbSuggest(letter: String, encodedQuery: String): ImdbSuggestResponse? {
        val response = runCatching {
            app.get(
                url = IMDB_SUGGEST.format(letter, encodedQuery),
                headers = HEADERS
            )
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<ImdbSuggestResponse>(response.text) }
            .getOrNull()
    }

    private suspend fun fetchTorrentio(kind: String, tt: String): List<TorrentioStream> {
        val response = runCatching {
            app.get(
                url = TORRENTIO_STREAM.format(kind, tt),
                headers = HEADERS
            )
        }.getOrNull()
        if (response == null || !response.isSuccessful) return emptyList()
        return runCatching { AppUtils.parseJson<TorrentioResponse>(response.text) }
            .getOrNull()?.streams
            .orEmpty()
    }

    private suspend fun fetchTmdbFind(tt: String): TmdbFindResponse? {
        // Maps an IMDb id to a TMDB id (+ cast). external_source=imdb_id.
        val key = TMDB_API_KEY ?: return null
        val url = "$TMDB_BASE/find/$tt?api_key=$key&external_source=imdb_id"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbFindResponse>(response.text) }.getOrNull()
    }

    private suspend fun fetchTmdbMedia(kind: String, id: Int): TmdbMedia? {
        // kind = "movie" | "tv". Returns overview, poster_path, release date.
        val key = TMDB_API_KEY ?: return null
        val url = "$TMDB_BASE/$kind/$id?api_key=$key"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbMedia>(response.text) }.getOrNull()
    }

    /** TMDB id -> IMDb id (tt...). */
    private suspend fun fetchTmdbImdbId(kind: String, id: Int): String? {
        val key = TMDB_API_KEY ?: return null
        val url = "$TMDB_BASE/$kind/$id/external_ids?api_key=$key"
        val response = runCatching { app.get(url = url, headers = HEADERS) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { AppUtils.parseJson<TmdbExternalIdsResponse>(response.text) }.getOrNull()
            ?.imdb_id?.takeIf { it.startsWith("tt") }
    }

    /**
     * Metadata for a title. Prefers TMDB (authoritative plot/poster/year,
     * resolved from the IMDb id); falls back to the IMDB suggestion entry when
     * the url carried the title, then to the torrent catalog's own title line.
     */
    private suspend fun resolveMeta(
        tt: String,
        nameHint: String?,
        movieStreams: List<TorrentioStream>?,
        seriesStreams: List<TorrentioStream>?
    ): Meta {
        // 1. TMDB — plot (overview), poster, year. Two calls (Find → Details).
        val find = runCatching { fetchTmdbFind(tt) }.getOrNull()
        val tmdbId = when {
            seriesStreams != null -> find?.tv_results?.firstOrNull()
            find?.tv_results?.isNotEmpty() == true && find.movie_results.isEmpty() -> find.tv_results.firstOrNull()
            else -> find?.movie_results?.firstOrNull()
        }
        if (tmdbId != null) {
            val isSeries = seriesStreams != null ||
                (find?.tv_results?.isNotEmpty() == true && find.movie_results.isEmpty())
            val media = runCatching {
                fetchTmdbMedia(if (isSeries) "tv" else "movie", tmdbId.id)
            }.getOrNull()
            val title = media?.title ?: media?.name
            val yearRaw = media?.release_date ?: media?.first_air_date
            val poster = media?.poster_path?.let { if (it.startsWith("/")) "$TMDB_IMAGE_BASE$it" else it }
            if (!title.isNullOrBlank()) {
                return Meta(
                    name = title.trim(),
                    year = yearRaw?.substringBefore('-')?.toIntOrNull(),
                    poster = poster,
                    synopsis = media?.overview?.takeIf { it.isNotBlank() }
                )
            }
        }

        // 2. IMDB suggestion — poster, year, synopsis, when the url carried the
        //    title (cheap single call).
        if (!nameHint.isNullOrBlank()) {
            val letter = nameHint.trim().first().lowercaseChar().toString()
            val match = imdbSuggest(letter, encodePath(nameHint.trim()))
                ?.d?.firstOrNull { it.id == tt }
            if (match != null && !match.l.isNullOrBlank()) {
                return Meta(
                    name = match.l.trim(),
                    year = match.y?.toIntOrNull(),
                    poster = match.i?.imageUrl,
                    synopsis = if (match.qid == "title") match.s?.takeIf { it.isNotBlank() } else null
                )
            }
        }

        // 3. Torrent catalog's own title line.
        val first = (movieStreams ?: seriesStreams)?.firstOrNull()
        val fallbackTitle = first?.title?.lineSequence()?.firstOrNull()?.trim()
        return Meta(fallbackTitle ?: "Torrent $tt", null, null, null)
    }

    private fun looksLikeEpisode(stream: TorrentioStream): Boolean =
        EPISODE_PATTERN.containsMatchIn(perFileLine(stream)) ||
            EPISODE_PATTERN.containsMatchIn(stream.behaviorHints?.filename.orEmpty())

    /**
     * One row per (season, episode), keeping the best available release
     * (resolution first, then seeders). Multi-file season packs are expanded
     * through their per-file names.
     */
    private fun buildEpisodes(
        streams: List<TorrentioStream>,
        meta: Meta
    ): List<Episode> {
        data class Candidate(val stream: TorrentioStream, val rank: Long)

        val best = HashMap<String, Candidate>()
        val order = ArrayList<String>()
        for (s in streams) {
            if (s.infoHash.isNullOrBlank()) continue
            val source = episodeSourceLine(s) ?: continue
            val match = EPISODE_PATTERN.find(source) ?: continue
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            val key = "$season:$episode"
            val rank = qualityValue(qualityLabel(s)).toLong() * 100_000 + seeders(s)
            val current = best[key]
            if (current == null || rank > current.rank) {
                if (current == null) order.add(key)
                best[key] = Candidate(s, rank)
            }
        }

        return order.mapNotNull { key ->
            val (seasonNum, episodeNum) = key.split(":").map { it.toInt() }
            val candidate = best[key] ?: return@mapNotNull null
            val s = candidate.stream
            val perFile = perFileLine(s)
            val raw = if (EPISODE_PATTERN.containsMatchIn(perFile)) perFile else
                s.behaviorHints?.filename.orEmpty()
            val details = listOfNotNull(
                qualityLabel(s).takeIf { it.isNotBlank() },
                sizeOf(s).takeIf { it.isNotBlank() },
                seeders(s).takeIf { it > 0 }?.toString()?.let { "$it seeders" },
                sourceOf(s).takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            newEpisode(episodeData(s), {
                name = cleanEpisodeName(raw, meta.name)
                season = seasonNum
                episode = episodeNum
                posterUrl = meta.poster
                description = details
            }, fix = false)
        }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .take(MAX_EPISODES)
    }

    private fun episodeData(s: TorrentioStream): String = listOf(
        s.infoHash.orEmpty(),
        (s.fileIdx ?: 0).toString(),
        qualityLabel(s),
        sizeOf(s),
        seeders(s).toString(),
        sourceOf(s)
    ).joinToString("|")

    private suspend fun magnetLink(
        s: TorrentioStream,
        label: String
    ): com.lagradost.cloudstream3.utils.ExtractorLink {
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            sizeOf(s).takeIf { it.isNotBlank() },
            seeders(s).takeIf { it > 0 }?.toString()?.let { "$it seeders" },
            sourceOf(s).takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        return newExtractorLink(
            source = name,
            name = details.ifBlank { "Torrent stream" },
            url = magnet(s.infoHash.orEmpty(), s.fileIdx),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    private fun magnet(infoHash: String, fileIdx: Int?): String =
        "magnet:?xt=urn:btih:$infoHash" + (fileIdx?.let { "&cs_file=$it" } ?: "")

    /** Second line of torrentio's `name` field: "4k DV | HDR10+", "1080p HEVC", ... */
    private fun qualityLabel(s: TorrentioStream): String {
        val label = s.name?.lineSequence()?.drop(1)?.firstOrNull()?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val file = s.behaviorHints?.filename.orEmpty()
        val match = RESOLUTION_PATTERN.find(file)
        return match?.groupValues?.get(1)?.lowercase() ?: ""
    }

    /** Map a quality label to a player resolution value (2160/1080/...). */
    private fun qualityValue(label: String): Int {
        if (label.contains("4k", ignoreCase = true) || label.contains("2160", ignoreCase = true)) {
            return 2160
        }
        val match = RESOLUTION_PATTERN.find(label)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun seeders(s: TorrentioStream): Int =
        SEEDERS_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun sizeOf(s: TorrentioStream): String =
        SIZE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1).orEmpty()

    private fun sourceOf(s: TorrentioStream): String =
        SOURCE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.trim().orEmpty()

    /** Per-file name inside a multi-line torrentio title, when present. */
    private fun perFileLine(s: TorrentioStream): String {
        val lines = s.title?.lineSequence()?.toList().orEmpty()
        return lines.getOrNull(1)?.trim().orEmpty()
    }

    /** The line an episode number should be parsed from. */
    private fun episodeSourceLine(s: TorrentioStream): String? {
        val perFile = perFileLine(s)
        if (EPISODE_PATTERN.containsMatchIn(perFile)) return perFile
        val file = s.behaviorHints?.filename
        if (!file.isNullOrBlank() && EPISODE_PATTERN.containsMatchIn(file)) return file
        return null
    }

    private fun cleanEpisodeName(raw: String, showName: String): String {
        var out = EXTENSION_PATTERN.replace(raw.trim(), "")
        if (showName.isNotBlank()) {
            out = out.replace(
                Regex("(?i)^${Regex.escape(showName)}\\s+"),
                ""
            )
        }
        return out.replace(WHITESPACE_PATTERN, " ").trim()
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val DATA_MOVIES = "trending-movies"
        const val DATA_TV = "trending-tv"
        const val DATA_NETFLIX = "latest-netflix"
        const val DATA_HOTSTAR = "latest-hotstar"
        const val DATA_ZEE5 = "latest-zee5"
        const val DATA_SONYLIV = "latest-sonyliv"

        /** Platform rows live this long in memory before re-fetching. */
        const val PLATFORM_CACHE_TTL = 6 * 60 * 60 * 1000L // 6 h
        /** Failed/empty builds are retried after this. */
        const val PLATFORM_CACHE_TTL_EMPTY = 10 * 60 * 1000L // 10 min
        const val MAX_PLATFORM_ROW_SIZE = 15
        const val TYPE_TV = "TvSeries"

        const val IMDB_SUGGEST = "https://v2.sg.media-imdb.com/suggestion/%s/%s.json"
        const val TORRENTIO_STREAM = "https://torrentio.strem.fun/stream/%s/%s.json"

        // TMDB — metadata (plot/poster/year) source. Requires the user to
        // supply their own key in Settings -> Player -> Metadata. Without a
        // key all TMDB fetches return null and the plugin gracefully skips
        // metadata enrichment.
        val TMDB_API_KEY: String?
            get() = tmdbApiKeyOverride?.takeIf { it.isNotBlank() }
        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        const val MAX_SEARCH_RESULTS = 15
        const val MAX_MOVIE_LINKS = 12
        const val MAX_EPISODES = 500

        val HEADERS = mapOf("User-Agent" to "Mozilla/5.0")

        val EPISODE_PATTERN = Regex("S(\\d{1,2})E(\\d{1,4})")
        val RESOLUTION_PATTERN = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        val SEEDERS_PATTERN = Regex("👤\\s*(\\d+)")
        val SIZE_PATTERN = Regex("💾\\s*([\\d.,]+\\s*\\w+)")
        val SOURCE_PATTERN = Regex("⚙️?\\s*([^\\n|]+)$")
        val EXTENSION_PATTERN =
            Regex("\\.(mkv|mp4|avi|mov|m4v|webm|mpg|mpeg|ts|m2ts)$", RegexOption.IGNORE_CASE)
        val WHITESPACE_PATTERN = Regex("\\s+")
    }
}
