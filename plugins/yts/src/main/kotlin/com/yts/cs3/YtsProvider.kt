package com.yts.cs3

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * YTS — a torrent index served from a JS driven SPA.
 *
 * Discovery:
 *  - Five browse rows (latest / popular / 4K movies, latest / popular TV)
 *  - Search across movies *and* TV through the same JSON API
 *
 * The site itself serves nothing but magnets, so this provider emits
 * ExtractorLinkType.MAGNET links and the app resolves them through the user's
 * Torrin / TorBox / Real-Debrid account (Settings → Player → Debrid). The exact
 * same contract plugins/torrin uses — no interceptor, no debrid key, no
 * download logic on our side.
 *
 * Everything comes from one JSON API on the site's own path
 * (`?api=search|discover|torrents|tv_details|season_details`); results are plain
 * TMDB objects, which is why posters are fetched from image.tmdb.org.
 *
 * Torrent lookups are keyed by TMDB title + year and not by an IMDb id, so
 * supportedSyncNames stays empty on purpose — there is nothing to sync against.
 */
class YtsProvider : MainAPI() {

    override var name = "YTS"
    override var mainUrl = "https://en.yts.lu"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    /** The API answers 404 / empty without the browse page as a referer. */
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Referer" to "$mainUrl/browse-movies"
    )

    /**
     * Row data is "{api}|{mode}|{extra query}", see [getMainPage].
     *
     * `api` is the endpoint the site's own sidebar uses: `discover` for filtered
     * rows (provider / origin / genres / company / year), and `popular`,
     * `top_rated`, `trending` for the curated ones. Verified live — an unknown
     * `api` answers `{"error":"Unknown action"}`.
     */
    override val mainPage = listOf(
        // Where people watch it
        MainPageData("Netflix", ROW_NETFLIX_MOVIES),
        MainPageData("Prime Video", ROW_PRIME_MOVIES),
        MainPageData("Disney+", ROW_DISNEY_MOVIES),
        MainPageData("Max", ROW_MAX_MOVIES),
        MainPageData("Hulu", ROW_HULU_MOVIES),
        // Fresh and regional
        MainPageData("This Week", ROW_MOVIES_WEEK),
        MainPageData("Today", ROW_MOVIES_TODAY),
        MainPageData("Indian Movies", ROW_INDIAN_MOVIES),
        MainPageData("Indian TV Shows", ROW_INDIAN_TV),
        // The usual shelves
        MainPageData("Popular Movies", ROW_MOVIES_POPULAR),
        MainPageData("Top Rated Movies", ROW_MOVIES_TOP),
        MainPageData("Popular TV Shows", ROW_TV_POPULAR),
        MainPageData("Anime", ROW_ANIME),
        // Streaming services as TV networks (different TMDB ids than the
        // movie "watch provider" ids above)
        MainPageData("Netflix TV Shows", ROW_NETFLIX_TV),
        MainPageData("Prime Video TV Shows", ROW_PRIME_TV)
    )

    // Torrent hits and TMDB blobs live a few minutes so paging through an episode
    // list (or re-opening a page) does not re-hit the API for every row.
    private val jsonCache = Cache<String, String>(CACHE_TTL_MS)
    private val torrentCache = Cache<String, List<YtsHit>>(CACHE_TTL_MS)
    private val tvCache = Cache<Int, YtsTvDetails>(CACHE_TTL_MS)
    private val seasonCache = Cache<String, YtsSeasonDetails>(CACHE_TTL_MS)

    // ----------------------------------------------------------------- pages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        /**
         * data = "{api}|{mode}|{extra query}" -> /?api=..&mode=..&sort=..&<extra>
         *
         * The site ignores anything it does not recognise, so the extra fragment
         * is passed through verbatim: `provider=<id>` filters movies by watch
         * provider, `network=<id>` filters shows by network, `origin=<iso>`
         * filters by country, `time=week|day` is the trending window.
         */
        val spec = request.data.split('|')
        val api = spec.getOrNull(0).orEmpty()
        val mode = spec.getOrNull(1).orEmpty()
        if (api.isEmpty() || (mode != MODE_MOVIE && mode != MODE_TV)) return null
        val extra = spec.getOrNull(2).orEmpty()

        val response = fetchPage(
            "$mainUrl/?api=$api&mode=$mode&page=$page&sort=$SORT_POPULARITY&genre=all&year=0" +
                if (extra.isEmpty()) "" else "&$extra"
        ) ?: return null
        val items = response.results.mapNotNull { it.toSearchResponse(this, mode == MODE_TV) }
        if (items.isEmpty()) return null
        return newHomePageResponse(request, items, page < response.total_pages)
    }

    // ---------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // The API rejects an empty q with {"error":"Missing query"}.
        val q = query.trim()
        if (q.isEmpty()) return null
        val encoded = encode(q)
        // Movies and shows live behind two different modes; ask both at once so a
        // single query returns both kinds.
        val (movies, shows) = coroutineScope {
            listOf(
                async { fetchPage(searchUrl(MODE_MOVIE, encoded, page)) },
                async { fetchPage(searchUrl(MODE_TV, encoded, page)) }
            ).awaitAll().let { (movies, shows) -> movies to shows }
        }
        val items = movies?.results.orEmpty().mapNotNull { it.toSearchResponse(this, false) } +
            shows?.results.orEmpty().mapNotNull { it.toSearchResponse(this, true) }
        if (items.isEmpty()) return null
        val hasNext = page < (movies?.total_pages ?: 1) || page < (shows?.total_pages ?: 1)
        return newSearchResponseList(items, hasNext)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseUrl(url) ?: return null
        return if (ref.isTv) loadSeries(url, ref) else loadMovie(url, ref)
    }

    private suspend fun loadMovie(url: String, ref: MediaRef): LoadResponse? {
        // There is no by-id movie endpoint: the site's movie objects only come out
        // of search/discover, so the slug is the lookup key. A url stripped of its
        // slug cannot be resolved and is rejected rather than guessed at.
        val titleHint = ref.titleHint?.trim().orEmpty()
        if (titleHint.isEmpty()) return null
        val media = fetchMovieDetails(ref.tmdbId, titleHint)
        val title = media?.name?.takeIf { it.isNotBlank() } ?: titleHint
        return newMovieLoadResponse(title, url, TvType.Movie, titleData(MODE_MOVIE, title, media?.year)) {
            posterUrl = media?.poster
            backgroundPosterUrl = media?.backdrop ?: media?.poster
            this.year = media?.year
            this.plot = media?.plot
            score = Score.from(media?.voteAverage, 10)
        }
    }

    private suspend fun loadSeries(url: String, ref: MediaRef): LoadResponse? {
        val details = fetchTvDetails(ref.tmdbId)
        val title = details?.name?.trim().orEmpty().ifEmpty { ref.titleHint?.trim().orEmpty() }
        if (title.isEmpty()) return null
        val year = yearOf(details?.first_air_date)
        val poster = imageUrl(details?.poster_path)
        val plot = details?.overview?.takeIf { it.isNotBlank() }
        val episodes = buildEpisodes(fetchTorrents(MODE_TV, title, year), details, title, year)

        if (episodes.isEmpty()) {
            // Only packs matched (COMPLETE.SERIES, "S01", "Season 2", ...): those
            // carry no SxxEyy and we will not invent episode numbers for them, so
            // the best pack is surfaced as one movie-style entry. Returning a
            // series with an empty episode list would be a dead end instead.
            return newMovieLoadResponse(title, url, TvType.Movie, titleData(MODE_TV, title, year)) {
                posterUrl = poster
                backgroundPosterUrl = imageUrl(details?.backdrop_path) ?: poster
                this.year = year
                this.plot = plot ?: "No per-episode release found — offering the best season pack."
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = imageUrl(details?.backdrop_path) ?: poster
            this.year = year
            this.plot = plot
            score = Score.from(details?.vote_average, 10)
            showStatus = details?.status.toShowStatus()
            // Only seasons a release actually exists for — the index has no entry
            // for the rest, and an empty season row is just dead space.
            seasonNames = details?.seasons.orEmpty()
                .filter { season -> episodes.any { it.season == season.season_number } }
                .map { SeasonData(it.season_number, it.name) }
                .takeIf { it.isNotEmpty() }
        }
    }

    /**
     * One row per (season, episode) parsed out of `SxxEyy`, best seeded release
     * wins, then enriched with the TMDB episode name/still when the site has one.
     */
    private suspend fun buildEpisodes(
        hits: List<YtsHit>,
        details: YtsTvDetails?,
        showName: String,
        year: Int?
    ): List<Episode> {
        data class Candidate(val hit: YtsHit, val rank: Long)

        val best = HashMap<String, Candidate>()
        for (hit in hits) {
            if (hit.hash.isNullOrBlank()) continue
            val key = hit.episodeKey() ?: continue
            if ((key.substringBefore(':').toIntOrNull() ?: 1) <= 0) continue // S00 = specials
            val rank = qualityValue(qualityLabel(hit)).toLong() * RANK_QUALITY + hit.seeds
            val current = best[key]
            if (current == null || rank > current.rank) best[key] = Candidate(hit, rank)
        }
        if (best.isEmpty()) return emptyList()

        // Season metadata (episode names, stills, runtimes) for every season the
        // hits actually mention. Cached, so this is at most one call per season.
        val seasons = HashMap<Int, YtsSeasonDetails?>()
        for (season in best.keys.mapNotNull { it.substringBefore(':').toIntOrNull() }.distinct()) {
            seasons[season] = details?.id?.takeIf { it > 0 }?.let { fetchSeasonDetails(it, season) }
        }

        return best.entries
            .mapNotNull { (key, candidate) ->
                val season = key.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val episode = key.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
                val hit = candidate.hit
                val seasonMeta = seasons[season]
                val meta = seasonMeta?.episodes?.firstOrNull { it.episode_number == episode }
                newEpisode(
                    episodeData(season, episode, showName, year, hit),
                    {
                        name = meta?.name?.takeIf { it.isNotBlank() }
                            ?: cleanEpisodeName(hit.title.orEmpty(), showName)
                        this.season = season
                        this.episode = episode
                        posterUrl = imageUrl(meta?.still_path, STILL_SIZE)
                            ?: imageUrl(seasonMeta?.poster_path)
                            ?: imageUrl(details?.poster_path)
                        description = meta?.overview?.takeIf { it.isNotBlank() } ?: releaseLine(hit)
                        runTime = meta?.runtime?.times(60)
                        score = Score.from(meta?.vote_average, 10)
                    },
                    fix = false
                )
            }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .take(MAX_EPISODES)
    }

    // ------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        return when (parts.firstOrNull()) {
            // Whole title -> best release per quality tier.
            DATA_TITLE -> {
                val mode = parts.getOrNull(1)?.takeIf { it == MODE_MOVIE || it == MODE_TV }
                    ?: return false
                val title = parts.getOrNull(2)?.trim().orEmpty()
                if (title.isEmpty()) return false
                emitBestPerQuality(fetchTorrents(mode, title, parts.getOrNull(3)?.toIntOrNull()), MAX_MOVIE_LINKS, callback)
            }
            // One episode -> every live release carrying the same SxxEyy.
            DATA_EPISODE -> {
                val season = parts.getOrNull(1)?.toIntOrNull() ?: return false
                val episode = parts.getOrNull(2)?.toIntOrNull() ?: return false
                val title = parts.getOrNull(3)?.trim().orEmpty()
                if (title.isEmpty()) return false
                val key = "$season:$episode"
                val hits = fetchTorrents(MODE_TV, title, parts.getOrNull(4)?.toIntOrNull())
                    .filter { it.episodeKey() == key }
                if (emitBestPerQuality(hits, MAX_EPISODE_LINKS, callback)) return true
                // The index moved on since load(); still offer what we found there.
                linkFromEpisodeData(parts)?.let { callback(it) } ?: return false
                true
            }
            else -> false
        }
    }

    private suspend fun emitBestPerQuality(
        hits: List<YtsHit>,
        limit: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bestPerQuality = LinkedHashMap<String, YtsHit>()
        for (hit in hits) {
            if (hit.hash.isNullOrBlank()) continue
            val label = qualityLabel(hit)
            val current = bestPerQuality[label]
            if (current == null || hit.seeds > current.seeds) bestPerQuality[label] = hit
        }
        bestPerQuality.entries
            .sortedByDescending { (label, hit) -> qualityValue(label).toLong() * RANK_QUALITY + hit.seeds }
            .take(limit)
            .forEach { (_, hit) -> callback(magnetLink(hit)) }
        return bestPerQuality.isNotEmpty()
    }

    private suspend fun magnetLink(hit: YtsHit): ExtractorLink {
        val label = qualityLabel(hit)
        return newExtractorLink(
            source = name,
            name = releaseLine(hit).ifBlank { "Torrent stream" },
            url = magnet(hit.hash.orEmpty()),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    /** Last resort: rebuild the link load() saw from the episode's data string. */
    private suspend fun linkFromEpisodeData(parts: List<String>): ExtractorLink? {
        val hash = parts.getOrNull(5)?.trim().orEmpty()
        if (hash.length !in 32..64) return null
        val label = parts.getOrNull(6).orEmpty()
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            parts.getOrNull(7)?.takeIf { it.isNotBlank() },
            parts.getOrNull(8)?.takeIf { it.isNotBlank() && it != "0" }?.let { "$it seeders" },
            parts.getOrNull(9)?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        return newExtractorLink(
            source = name,
            name = details.ifBlank { "Torrent stream" },
            url = magnet(hash),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    // ------------------------------------------------------------ deep links

    /** A parsed content url: {mainUrl}/{movie|tv}/{tmdbId}/{slug}. */
    private data class MediaRef(
        val isTv: Boolean,
        val tmdbId: Int,
        val titleHint: String?
    )

    private fun contentUrl(kind: String, tmdbId: Int, title: String): String =
        "$mainUrl/$kind/$tmdbId/${encode(title)}"

    /**
     * Inverse of [contentUrl]. The slug is optional so truncated / hand-made urls
     * still resolve; returns null for anything that is not a yts content url.
     */
    private fun parseUrl(url: String): MediaRef? {
        val tail = url.substringAfter("$mainUrl/", url).substringBefore('?')
        val parts = tail.split('/').filter { it.isNotEmpty() }
        val kind = parts.firstOrNull()?.lowercase() ?: return null
        if (kind != PATH_MOVIE && kind != PATH_TV) return null
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val slug = parts.drop(2).joinToString("/")
        return MediaRef(kind == PATH_TV, tmdbId, decode(slug)?.takeIf { it.isNotBlank() })
    }

    // -------------------------------------------------------------- helpers

    private data class MovieMeta(
        val name: String,
        val year: Int?,
        val poster: String?,
        val backdrop: String?,
        val plot: String?,
        val voteAverage: Double?
    )

    private suspend fun getJson(url: String): String? {
        jsonCache.get(url)?.let { return it }
        val response = runCatching { app.get(url = url, headers = headers) }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        val text = response.text
        if (text.isBlank()) return null
        jsonCache.put(url, text)
        return text
    }

    private suspend fun fetchPage(url: String): YtsPage? {
        val text = getJson(url) ?: return null
        return runCatching { AppUtils.parseJson<YtsPage>(text) }.getOrNull()
    }

    private fun searchUrl(mode: String, query: String, page: Int): String =
        "$mainUrl/?api=search&mode=$mode&q=$query&page=$page"

    /** Torrent hits for "{mode}|{title}|{year}"; the lists are re-read at play time. */
    private suspend fun fetchTorrents(mode: String, title: String, year: Int?): List<YtsHit> {
        if (title.isBlank()) return emptyList()
        val key = "$mode|$title|${year ?: ""}"
        torrentCache.get(key)?.let { return it }
        val url = "$mainUrl/?api=torrents&mode=$mode&name=${encode(title)}&year=${year ?: ""}&quality=all"
        val text = getJson(url) ?: return emptyList()
        val hits = runCatching { AppUtils.parseJson<YtsTorrents>(text) }.getOrNull()?.hits.orEmpty()
            .filter { !it.hash.isNullOrBlank() }
        torrentCache.put(key, hits)
        return hits
    }

    private suspend fun fetchTvDetails(tmdbId: Int): YtsTvDetails? {
        if (tmdbId <= 0) return null
        tvCache.get(tmdbId)?.let { return it }
        val text = getJson("$mainUrl/?api=tv_details&id=$tmdbId") ?: return null
        val details = runCatching { AppUtils.parseJson<YtsTvDetails>(text) }.getOrNull() ?: return null
        tvCache.put(tmdbId, details)
        return details
    }

    private suspend fun fetchSeasonDetails(tmdbId: Int, season: Int): YtsSeasonDetails? {
        val key = "$tmdbId|$season"
        seasonCache.get(key)?.let { return it }
        val text = getJson("$mainUrl/?api=season_details&id=$tmdbId&season=$season") ?: return null
        val details = runCatching { AppUtils.parseJson<YtsSeasonDetails>(text) }.getOrNull()
            ?: return null
        seasonCache.put(key, details)
        return details
    }

    /** Movie objects only come out of search/discover, so re-query and match the id. */
    private suspend fun fetchMovieDetails(tmdbId: Int, title: String): MovieMeta? {
        val page = fetchPage(searchUrl(MODE_MOVIE, encode(title), 1)) ?: return null
        val media = page.results.firstOrNull { it.id == tmdbId }
            ?: page.results.firstOrNull { it.title.equals(title, ignoreCase = true) }
            ?: return null
        val name = media.title?.trim().orEmpty().ifEmpty { title }
        return MovieMeta(
            name = name,
            year = yearOf(media.release_date),
            poster = imageUrl(media.poster_path),
            backdrop = imageUrl(media.backdrop_path),
            plot = media.overview?.takeIf { it.isNotBlank() },
            voteAverage = media.vote_average
        )
    }

    private fun YtsMedia.toSearchResponse(api: MainAPI, isTv: Boolean): SearchResponse? {
        val title = (if (isTv) name else title)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val url = contentUrl(if (isTv) PATH_TV else PATH_MOVIE, id, title)
        val releaseYear = yearOf(if (isTv) first_air_date else release_date)
        val poster = imageUrl(poster_path)
        return if (isTv) {
            api.newTvSeriesSearchResponse(title, url) {
                posterUrl = poster
                year = releaseYear
            }
        } else {
            api.newMovieSearchResponse(title, url) {
                posterUrl = poster
                year = releaseYear
            }
        }
    }

    /** Data handed to loadLinks for a whole-title listing. */
    private fun titleData(mode: String, title: String, year: Int?): String =
        listOf(DATA_TITLE, mode, sanitize(title), year?.toString().orEmpty()).joinToString("|")

    /**
     * Data handed to loadLinks per episode: kind|season|episode|title|year|hash|
     * quality|size|seeders|source. The title/year re-run the live lookup at play
     * time; the rest is only a fallback if that lookup finds nothing anymore.
     */
    private fun episodeData(season: Int, episode: Int, title: String, year: Int?, hit: YtsHit): String =
        listOf(
            DATA_EPISODE, season, episode, sanitize(title), year?.toString().orEmpty(),
            hit.hash.orEmpty(), qualityLabel(hit), humanSize(hit.bytes),
            hit.seeds.toString(), hit.source?.trim().orEmpty()
        ).joinToString("|")

    private fun sanitize(value: String): String =
        WHITESPACE_PATTERN.replace(value.replace('|', ' '), " ").trim()

    /** "S02E07" / "2x07" -> "2:7", null when the release is a film or a pack. */
    private fun YtsHit.episodeKey(): String? {
        val release = title.orEmpty()
        // "S01E01-E09", "S1E1-73 of 73", "S01E01E02" are packs of several
        // episodes; hanging one of them off a single episode number would be a
        // lie, so they are treated as packs and left out of the episode list.
        if (EPISODE_PACK_PATTERN.containsMatchIn(release)) return null
        val match = EPISODE_PATTERN.find(release) ?: X_EPISODE_PATTERN.find(release) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return "$season:$episode"
    }

    /**
     * The site's magnet carries a dozen tracker params; debrid resolves by info
     * hash, so only the hash is kept. `cs_file` is added for multi-file torrents
     * and YTS never exposes a file list, so it does not apply here.
     */
    private fun magnet(infoHash: String): String = "magnet:?xt=urn:btih:$infoHash"

    /** "1080p" out of "The Batman (2022) [1080p] [WEBRip] [5.1]". */
    private fun qualityLabel(hit: YtsHit): String {
        val title = hit.title.orEmpty()
        return RESOLUTION_PATTERN.find(title)?.value?.lowercase()
            ?: if (UHD_PATTERN.containsMatchIn(title)) "2160p" else ""
    }

    /** Maps a quality label to a player resolution value (2160 / 1080 / ...). */
    private fun qualityValue(label: String): Int {
        if (label.contains("2160", ignoreCase = true) ||
            label.contains("4k", ignoreCase = true) ||
            label.contains("uhd", ignoreCase = true)
        ) {
            return 2160
        }
        return RESOLUTION_PATTERN.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** "<quality> • <size> • <seeders> seeders • <source>" */
    private fun releaseLine(hit: YtsHit): String = listOfNotNull(
        qualityLabel(hit).takeIf { it.isNotBlank() },
        humanSize(hit.bytes).takeIf { it.isNotBlank() },
        hit.seeds.takeIf { it > 0 }?.toString()?.let { "$it seeders" },
        hit.source?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString(" • ")

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < SIZE_UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${SIZE_UNITS[unit]}"
        else String.format(Locale.US, "%.2f %s", value, SIZE_UNITS[unit])
    }

    private fun cleanEpisodeName(raw: String, showName: String): String {
        var out = EXTENSION_PATTERN.replace(raw.trim(), "")
        if (showName.isNotBlank()) {
            out = out.replace(Regex("(?i)^${Regex.escape(showName)}\\s+"), "")
        }
        return out.replace(WHITESPACE_PATTERN, " ").trim()
    }

    private fun String?.toShowStatus(): ShowStatus? = when (this?.lowercase()) {
        null, "" -> null
        "ended", "canceled", "cancelled" -> ShowStatus.Completed
        else -> ShowStatus.Ongoing
    }

    /** Posters are plain TMDB paths. */
    private fun imageUrl(path: String?, size: String = IMAGE_SIZE): String? =
        path?.takeIf { it.startsWith("/") }?.let { "$TMDB_IMAGE_BASE/$size$it" }

    private fun yearOf(date: String?): Int? =
        date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String? =
        if (value.isEmpty()) null
        else runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value

    /**
     * Tiny in-memory TTL cache. Torrent hits are re-read whenever they expire so a
     * stale magnet list can never pile up, but scrolling an episode list stays at
     * zero requests.
     */
    private class Cache<K : Any, V : Any>(private val ttlMs: Long) {
        private data class Entry<V>(val value: V, val at: Long)

        private val entries = HashMap<K, Entry<V>>()

        fun get(key: K): V? = synchronized(entries) {
            val entry = entries[key]
            when {
                entry == null -> null
                APIHolder.unixTimeMS - entry.at > ttlMs -> {
                    entries.remove(key)
                    null
                }
                else -> entry.value
            }
        }

        fun put(key: K, value: V) = synchronized(entries) {
            if (entries.size >= MAX_CACHE_ENTRIES) {
                entries.entries.minByOrNull { it.value.at }?.let { entries.remove(it.key) }
            }
            entries[key] = Entry(value, APIHolder.unixTimeMS)
        }
    }

    private companion object {
        // Home row data: "{api mode}|{sort}|{quality}"
        // Row specs: "{api}|{mode}|{extra}". The provider / network ids are TMDB's.
        const val ROW_NETFLIX_MOVIES = "discover|movie|provider=8"
        const val ROW_PRIME_MOVIES = "discover|movie|provider=9"
        const val ROW_DISNEY_MOVIES = "discover|movie|provider=337"
        const val ROW_MAX_MOVIES = "discover|movie|provider=1899"
        const val ROW_HULU_MOVIES = "discover|movie|provider=15"
        const val ROW_MOVIES_WEEK = "trending|movie|time=week"
        const val ROW_MOVIES_TODAY = "trending|movie|time=day"
        const val ROW_INDIAN_MOVIES = "discover|movie|origin=IN"
        const val ROW_INDIAN_TV = "discover|movies|origin=IN"
        const val ROW_MOVIES_POPULAR = "popular|movie|"
        const val ROW_MOVIES_TOP = "top_rated|movie|"
        const val ROW_TV_POPULAR = "popular|movies|"
        const val ROW_ANIME = "discover|movies|genres=16&lang=ja"
        const val ROW_NETFLIX_TV = "discover|movies|network=213"
        const val ROW_PRIME_TV = "discover|movies|network=1024"

        /** TMDB sort strings — the site ignores short forms like "latest". */
        const val SORT_POPULARITY = "popularity.desc"

        // api=discover / api=search / api=torrents mode for a kind of title
        const val MODE_MOVIE = "movie"
        const val MODE_TV = "movies"
        // path segment of the content urls load() understands
        const val PATH_MOVIE = "movie"
        const val PATH_TV = "tv"

        /** loadLinks data markers. */
        const val DATA_TITLE = "m"
        const val DATA_EPISODE = "e"

        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
        const val IMAGE_SIZE = "w500"
        const val STILL_SIZE = "w300"

        const val MAX_MOVIE_LINKS = 12
        const val MAX_EPISODE_LINKS = 6
        const val MAX_EPISODES = 500
        const val MAX_CACHE_ENTRIES = 128
        const val CACHE_TTL_MS = 4 * 60 * 1000L

        /** Seeders only break ties inside one quality tier. */
        const val RANK_QUALITY = 100_000L

        val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
        val EPISODE_PATTERN = Regex("S(\\d{1,2})E(\\d{1,4})", RegexOption.IGNORE_CASE)
        /** Older scene form ("Breaking.Bad.2x06.2008"); the lookarounds keep it
         *  from eating the resolution out of "1920x1080" / "720x576". */
        val X_EPISODE_PATTERN = Regex("(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)")
        /** A single torrent holding more than one episode. */
        val EPISODE_PACK_PATTERN = Regex(
            "S\\d{1,2}E\\d{1,4}\\s*[-\u2013\u2014~]\\s*E?\\d" +
                    "|S\\d{1,2}E\\d{1,4}\\s*of\\s*\\d" +
                    "|S\\d{1,2}E\\d{1,4}E\\d",
            RegexOption.IGNORE_CASE
        )
        val RESOLUTION_PATTERN = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        val UHD_PATTERN = Regex("(4k|uhd)", RegexOption.IGNORE_CASE)
        val EXTENSION_PATTERN =
            Regex("\\.(mkv|mp4|avi|mov|m4v|webm|mpg|mpeg|ts|m2ts)$", RegexOption.IGNORE_CASE)
        val WHITESPACE_PATTERN = Regex("\\s+")
    }
}

/** /?api=search|discover — a page of plain TMDB movie/tv objects. */
@Serializable
private data class YtsPage(
    val page: Int = 1,
    val results: List<YtsMedia> = emptyList(),
    val total_pages: Int = 1,
    val total_results: Int = 0
)

/**
 * TMDB movie/tv shape. `title`/`release_date` for films, `name`/`first_air_date`
 * for shows — both modes share the object.
 */
@Serializable
private data class YtsMedia(
    val id: Int = 0,
    val title: String? = null,
    val original_title: String? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null
)

/** /?api=torrents — the index's releases for one title. */
@Serializable
private data class YtsTorrents(
    val hits: List<YtsHit> = emptyList(),
    val total: Int = 0
)

@Serializable
private data class YtsHit(
    val title: String? = null,
    val seeds: Int = 0,
    val peers: Int = 0,
    val bytes: Long = 0,
    val magnetUrl: String? = null,
    val hash: String? = null,
    val source: String? = null
)

/** /?api=tv_details — full TMDB tv object. */
@Serializable
private data class YtsTvDetails(
    val id: Int = 0,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val status: String? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val genres: List<YtsNamed> = emptyList(),
    val networks: List<YtsNamed> = emptyList(),
    val seasons: List<YtsSeason> = emptyList()
)

@Serializable
private data class YtsNamed(
    val id: Int = 0,
    val name: String? = null,
    val logo_path: String? = null
)

@Serializable
private data class YtsSeason(
    val season_number: Int = 0,
    val name: String? = null,
    val episode_count: Int? = null,
    val air_date: String? = null,
    val poster_path: String? = null,
    val overview: String? = null,
    val vote_average: Double? = null
)

/** /?api=season_details — episode names/stills for one season. */
@Serializable
private data class YtsSeasonDetails(
    val id: Int = 0,
    val name: String? = null,
    val season_number: Int = 0,
    val air_date: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val episodes: List<YtsEpisode> = emptyList()
)

@Serializable
private data class YtsEpisode(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null
)