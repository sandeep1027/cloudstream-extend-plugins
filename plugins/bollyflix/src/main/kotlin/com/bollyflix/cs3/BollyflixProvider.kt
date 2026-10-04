package com.bollyflix.cs3

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.metaproviders.tmdbApiKeyOverride
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * BollyFlix — https://new.bollyflix.vote
 *
 * BollyFlix is a WordPress catalogue of *download* posts rather than a streaming
 * site: every post is a release card holding a quality table
 * (`2160p HEVC [12GB]`) plus download mirrors that sit behind a Cloudflare
 * challenge and are not playable inside CloudStream.
 *
 * Discovery therefore comes from BollyFlix (its open `wp-json/wp/v2` REST API:
 * search, category rows, per-post metadata) while *playback* comes from the
 * public Torrentio index, addressed through the IMDb id that every post's
 * `content.rendered` block links to.
 *
 * `loadLinks` emits `ExtractorLinkType.MAGNET` links; the app's debrid layer
 * (Settings -> Player -> Debrid: Torrin / TorBox / Real-Debrid) resolves them
 * into signed direct URLs before the player starts — the same pipeline the
 * `torrin` plugin uses. Multi-file season packs carry a `cs_file=<index>` hint so
 * the exact episode file is picked.
 *
 * Series are published as season packs and have no per-episode page on this site,
 * so the episode list is reconstructed from Torrentio's per-file names.
 */
class BollyflixProvider : MainAPI() {

    override var name = "BollyFlix"
    override var mainUrl = "https://new.bollyflix.vote"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    override val mainPage = listOf(
        MainPageData("Bollywood", "bollywood"),
        MainPageData("Hollywood", "hollywood"),
        MainPageData("Dual Audio", "dual-audio-movies"),
        MainPageData("Hindi Dubbed", "hindi-dubbed-movies-480p-720p"),
        MainPageData("Korean Drama", "korean-drama")
    )

    // --------------------------------------------------------------- caches

    /** slug -> post id, filled while walking search results and home rows. */
    private val postIdBySlug = ConcurrentHashMap<String, Int>()

    private val categoryLock = Mutex()
    private var categoryIdBySlug: Map<String, Int> = emptyMap()
    private var categoryNameById: Map<Int, String> = emptyMap()
    private var categoriesFetchedAt: Long = 0L

    // ----------------------------------------------------------- home rows

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data.trim()
        if (slug.isEmpty()) return null
        val categoryId = resolveCategoryId(slug) ?: return null
        val posts = fetchPosts("categories=$categoryId", page) ?: return null
        val items = posts.mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items, items.size >= PER_PAGE)
    }

    // -------------------------------------------------------------- search

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? =
        search(query, 1)?.items?.takeIf { it.isNotEmpty() }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val posts = fetchPosts("search=${encodeQuery(q)}", page) ?: return null
        val items = posts.mapNotNull { it.toSearchResult() }
        return newSearchResponseList(items, items.size >= PER_PAGE)
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromUrl(url) ?: return null
        val post = resolvePost(slug) ?: return null

        val content = post.content?.rendered.orEmpty()
        val imdbId = IMDB_TITLE_PATTERN.find(content)?.groupValues?.get(1)
        val title = cleanTitle(post.title?.rendered).ifBlank { post.slug.orEmpty() }
        if (title.isBlank()) return null

        val siteYear = yearFromContent(content)
            ?: YEAR_IN_PARENTHESES.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: post.date?.substringBefore('-')?.toIntOrNull()
        val sitePoster = post.posterUrl()

        // Torrentio probe: if the movie endpoint hands back per-episode files the
        // id belongs to a series, so pull the dedicated series endpoint as well.
        val probe = imdbId?.let { id -> runCatching { probeTorrentio(id) }.getOrNull() }
        val isSeries = probe?.isSeries == true
        val streams = when {
            probe == null -> emptyList()
            isSeries -> probe.seriesStreams
            else -> probe.movieStreams
        }

        // Optional TMDB enrichment for the gaps BollyFlix itself leaves empty.
        val tmdb = imdbId?.let { runCatching { fetchTmdbMeta(it, isSeries) }.getOrNull() }

        val quality = fetchQualityTable(post.link)
        val plot = buildPlot(
            summary = valueAfterLabel(content, "Summary:")
                ?: valueAfterLabel(content, "Storyline:"),
            tmdbPlot = tmdb?.plot,
            quality = quality
        )
        val tags = categoryNames(post.categories)
            ?: valueAfterLabel(content, "Genres:")?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }

        if (isSeries) {
            val episodes = buildEpisodes(
                streams = streams,
                showName = title,
                poster = sitePoster ?: tmdb?.poster
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = sitePoster ?: tmdb?.poster
                backgroundPosterUrl = tmdb?.poster ?: sitePoster
                year = siteYear ?: tmdb?.year
                this.plot = plot
                this.tags = tags
                addImdbId(imdbId)
            }
        }

        // A post with no IMDb id has no torrent layer at all; an empty data url
        // makes the app show it as "coming soon" rather than fail to play.
        return newMovieLoadResponse(title, url, TvType.Movie, imdbId?.let { "m:$it" } ?: "") {
            posterUrl = sitePoster ?: tmdb?.poster
            backgroundPosterUrl = tmdb?.poster ?: sitePoster
            year = siteYear ?: tmdb?.year
            this.plot = plot
            this.tags = tags
            addImdbId(imdbId)
        }
    }

    // ----------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith(MOVIE_PREFIX)) {
            // Whole title -> the best magnet per quality tier.
            val imdbId = data.removePrefix(MOVIE_PREFIX)
            val streams = runCatching { fetchTorrentio("movie", imdbId) }.getOrNull().orEmpty()
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
                .forEach { (label, s) -> callback(magnetLink(s, label)) }
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

    // ------------------------------------------------------ wordpress api

    /**
     * The host sits behind a CDN that resets bursts, and the app fires every
     * home row at once (APIRepository.getMainPage awaits them concurrently).
     * Serialising the list requests keeps one home load from turning into five
     * simultaneous multi-megabyte requests that all get dropped.
     */
    private val postsLock = Mutex()

    /** query|page -> (fetched at, posts), so a rate-limited host is hit once. */
    private val listCache = ConcurrentHashMap<String, Pair<Long, List<WpPost>>>()

    /**
     * GET with retries and real spacing. This CDN resets a large share of
     * requests from a busy client no matter how small they are, so a row that
     * comes back empty once is what makes a provider look permanently dead.
     */
    private suspend fun getText(url: String, attempts: Int = LIST_ATTEMPTS): String? {
        repeat(attempts) { attempt ->
            val response = runCatching {
                postsLock.withLock { app.get(url = url, headers = HEADERS) }
            }.getOrNull()
            if (response != null && response.isSuccessful) {
                val body = runCatching { response.text }.getOrNull()
                if (!body.isNullOrBlank()) return body
            }
            if (attempt < attempts - 1) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        return null
    }

    /**
     * One page of posts for an already-built query fragment, or null on failure.
     *
     * Results are cached briefly: the home screen asks for the same five rows
     * on every launch, and each miss costs another request into a rate limiter
     * that is already the flakiest part of this host.
     */
    private suspend fun fetchPosts(query: String, page: Int): List<WpPost>? {
        val key = "$query|$page"
        val now = System.currentTimeMillis()
        listCache[key]?.let { (at, posts) ->
            if (now - at < LIST_CACHE_TTL_MS) {
                posts.forEach { remember(it) }
                return posts
            }
        }

        val url = "$WP_POSTS?$query&per_page=$PER_PAGE&page=$page&$LIST_FIELDS"
        val body = getText(url) ?: return null
        val posts = runCatching { parseJson<List<WpPost>>(body) }.getOrNull() ?: return null
        posts.forEach { remember(it) }
        if (listCache.size < MAX_LIST_CACHE) listCache[key] = now to posts
        return posts
    }

    private suspend fun resolvePost(slug: String): WpPost? {
        postIdBySlug[slug]?.let { cached -> fetchPostById(cached)?.let { return it } }
        val response = runCatching {
            app.get(url = "$WP_POSTS?slug=$slug&_embed=1", headers = HEADERS)
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        val posts = runCatching { parseJson<List<WpPost>>(response.text) }.getOrNull()
            ?: return null
        return posts.firstOrNull()?.also { remember(it) }
    }

    private suspend fun fetchPostById(id: Int): WpPost? {
        val response = runCatching {
            app.get(url = "$WP_POSTS/$id?_embed=1", headers = HEADERS)
        }.getOrNull()
        if (response == null || !response.isSuccessful) return null
        return runCatching { parseJson<WpPost>(response.text) }.getOrNull()?.also { remember(it) }
    }

    private fun remember(post: WpPost) {
        val slug = post.slug ?: return
        if (postIdBySlug.size < MAX_SLUG_CACHE) postIdBySlug[slug] = post.id
    }

    /**
     * The category taxonomy is fetched lazily and cached: it feeds both the
     * home-row slug -> id lookups and the id -> name lookups used for genres.
     * Categories get renamed on this site, so slugs win at runtime and the
     * measured ids are only a fallback.
     */
    private suspend fun ensureCategories() {
        categoryLock.withLock {
            val now = System.currentTimeMillis()
            if (categoryIdBySlug.isNotEmpty() && now - categoriesFetchedAt < CATEGORY_CACHE_TTL) {
                return@withLock
            }
            val response = runCatching {
                postsLock.withLock {
                    app.get(url = "$WP_CATEGORIES?per_page=100&hide_empty=1", headers = HEADERS)
                }
            }.getOrNull()
            if (response == null || !response.isSuccessful) return@withLock
            val categories = runCatching { parseJson<List<WpCategory>>(response.text) }.getOrNull()
                ?: return@withLock
            if (categories.isEmpty()) return@withLock
            categoryIdBySlug = categories.mapNotNull { c ->
                val slug = c.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                slug to c.id
            }.toMap()
            categoryNameById = categories.mapNotNull { c ->
                val categoryName = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                c.id to categoryName
            }.toMap()
            categoriesFetchedAt = now
        }
    }

    private suspend fun resolveCategoryId(slug: String): Int? {
        ensureCategories()
        categoryIdBySlug[slug]?.let { return it }
        return FALLBACK_CATEGORY_IDS[slug]
    }

    private suspend fun categoryNames(ids: List<Int>): List<String>? {
        if (ids.isEmpty()) return null
        ensureCategories()
        return ids.mapNotNull { categoryNameById[it] }
            .distinct()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * The quality table lives only in the rendered post page, never in the REST
     * content. Each row is an `<h5>` label such as
     * `Kantara (2022) {Hindi-Kannada} 1080p (10bit) [3GB]`. The `class="dl"`
     * anchors are counted as a mirror indicator but never resolved — they sit
     * behind a Cloudflare challenge.
     */
    private suspend fun fetchQualityTable(link: String?): QualityTable? {
        val page = link?.takeIf { it.isNotBlank() } ?: return null
        val response = runCatching { app.get(url = page, headers = BROWSER_HEADERS) }.getOrNull()
        val html = response?.takeIf { it.isSuccessful }?.text ?: return null

        val rows = QUALITY_ROW_PATTERN.findAll(html)
            .map { htmlToText(it.groupValues[1]) }
            .filter { it.isNotBlank() && !it.contains("trailer", ignoreCase = true) }
            .mapNotNull { parseQualityRow(it) }
            .distinctBy { it.display() }
            .sortedByDescending { it.resolution?.toIntOrNull() ?: 0 }
            .take(MAX_QUALITY_ROWS)
            .toList()
        if (rows.isEmpty()) return null

        return QualityTable(rows, DL_ANCHOR_PATTERN.findAll(html).count())
    }

    private fun parseQualityRow(label: String): QualityRow? {
        val resolution = RESOLUTION_PATTERN.find(label) ?: return null
        val sizeMatch = SIZE_BRACKET_PATTERN.find(label)
        val extraEnd = sizeMatch?.range?.first?.coerceAtLeast(resolution.range.last + 1)
            ?: label.length
        val extra = label
            .substring(resolution.range.last + 1, extraEnd)
            .replace(BRACKET_PATTERN, " ")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
            .takeIf { it.isNotBlank() }
        return QualityRow(
            label = label,
            resolution = resolution.value.lowercase(),
            extra = extra,
            size = sizeMatch?.groupValues?.get(1)?.trim()
        )
    }

    private fun QualityRow.display(): String = listOfNotNull(
        listOfNotNull(resolution, extra).joinToString(" ").takeIf { it.isNotBlank() },
        size
    ).joinToString(" • ").ifBlank { label }

    private fun buildPlot(summary: String?, tmdbPlot: String?, quality: QualityTable?): String? {
        val head = summary?.takeIf { it.isNotBlank() }
            ?: tmdbPlot?.takeIf { it.isNotBlank() }
            ?: return null
        val table = quality ?: return head
        val rows = table.rows.joinToString("\n") { "• ${it.display()}" }
        val mirrors = if (table.mirrors > 0) {
            "\n${table.mirrors} download mirror${if (table.mirrors == 1) "" else "s"} on BollyFlix"
        } else {
            ""
        }
        return "$head\n\nAvailable on BollyFlix:\n$rows$mirrors"
    }

    // -------------------------------------------------------- torrent layer

    /** Which Torrentio feed answered, and the streams it returned. */
    private data class TorrentProbe(
        val isSeries: Boolean,
        val movieStreams: List<TorrentioStream>,
        val seriesStreams: List<TorrentioStream>
    )

    /**
     * Torrentio is probed on the movie endpoint first. A series id answered there
     * returns per-episode files, which is the signal to also pull the dedicated
     * series endpoint (this site publishes series as season packs only).
     */
    private suspend fun probeTorrentio(imdbId: String): TorrentProbe {
        val movieStreams = runCatching { fetchTorrentio("movie", imdbId) }.getOrNull().orEmpty()
        val isSeries = movieStreams.any { looksLikeEpisode(it) }
        val seriesStreams =
            if (isSeries) runCatching { fetchTorrentio("series", imdbId) }.getOrNull().orEmpty()
            else emptyList()
        return TorrentProbe(isSeries, movieStreams, seriesStreams)
    }

    private suspend fun fetchTorrentio(kind: String, imdbId: String): List<TorrentioStream> {
        val response = runCatching {
            app.get(url = TORRENTIO_STREAM.format(kind, imdbId), headers = HEADERS)
        }.getOrNull()
        if (response == null || !response.isSuccessful) return emptyList()
        return runCatching { parseJson<TorrentioResponse>(response.text) }
            .getOrNull()?.streams
            .orEmpty()
    }

    private suspend fun magnetLink(s: TorrentioStream, label: String): ExtractorLink {
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

    private fun looksLikeEpisode(stream: TorrentioStream): Boolean =
        EPISODE_PATTERN.containsMatchIn(perFileLine(stream)) ||
            EPISODE_PATTERN.containsMatchIn(stream.behaviorHints?.filename.orEmpty())

    /**
     * One row per (season, episode), keeping the best available release
     * (resolution first, then seeders). Season packs are expanded through their
     * per-file names.
     */
    private fun buildEpisodes(
        streams: List<TorrentioStream>,
        showName: String,
        poster: String?
    ): List<Episode> {
        val best = HashMap<String, TorrentioStream>()
        val bestRank = HashMap<String, Long>()
        val order = ArrayList<String>()

        for (s in streams) {
            if (s.infoHash.isNullOrBlank()) continue
            val source = episodeSourceLine(s) ?: continue
            val match = EPISODE_PATTERN.find(source) ?: continue
            val key = "${match.groupValues[1]}:${match.groupValues[2]}"
            val rank = qualityValue(qualityLabel(s)).toLong() * 100_000 + seeders(s)
            val current = bestRank[key]
            if (current == null || rank > current) {
                if (current == null) order.add(key)
                best[key] = s
                bestRank[key] = rank
            }
        }

        return order.mapNotNull { key ->
            val (seasonNum, episodeNum) = key.split(":").map { it.toInt() }
            val s = best[key] ?: return@mapNotNull null
            val perFile = perFileLine(s)
            val raw = if (EPISODE_PATTERN.containsMatchIn(perFile)) perFile
            else s.behaviorHints?.filename.orEmpty()
            val details = listOfNotNull(
                qualityLabel(s).takeIf { it.isNotBlank() },
                sizeOf(s).takeIf { it.isNotBlank() },
                seeders(s).takeIf { it > 0 }?.toString()?.let { "$it seeders" },
                sourceOf(s).takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            newEpisode(episodeData(s), {
                name = cleanEpisodeName(raw, showName)
                season = seasonNum
                episode = episodeNum
                posterUrl = poster
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

    /** Second line of torrentio's `name` field: "4k DV | HDR10+", "1080p HEVC", ... */
    private fun qualityLabel(s: TorrentioStream): String {
        val label = s.name?.lineSequence()?.drop(1)?.firstOrNull()?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val file = s.behaviorHints?.filename.orEmpty()
        return RESOLUTION_PATTERN.find(file)?.value?.lowercase().orEmpty()
    }

    /** Map a quality label to a player resolution value (2160/1080/...). */
    private fun qualityValue(label: String): Int {
        if (label.contains("4k", ignoreCase = true) || label.contains("2160", ignoreCase = true)) {
            return 2160
        }
        return RESOLUTION_PATTERN.find(label)?.value?.dropLast(1)?.toIntOrNull() ?: 0
    }

    private fun seeders(s: TorrentioStream): Int =
        SEEDERS_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun sizeOf(s: TorrentioStream): String =
        SIZE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1).orEmpty()

    private fun sourceOf(s: TorrentioStream): String =
        SOURCE_PATTERN.find(s.title.orEmpty())?.groupValues?.get(1)?.trim().orEmpty()

    /** Per-file name inside a multi-line torrentio title, when present. */
    private fun perFileLine(s: TorrentioStream): String =
        s.title?.lineSequence()?.toList().orEmpty().getOrNull(1)?.trim().orEmpty()

    /** The line an episode number should be parsed from. */
    private fun episodeSourceLine(s: TorrentioStream): String? {
        val perFile = perFileLine(s)
        if (EPISODE_PATTERN.containsMatchIn(perFile)) return perFile
        val file = s.behaviorHints?.filename
        if (!file.isNullOrBlank() && EPISODE_PATTERN.containsMatchIn(file)) return file
        return null
    }

    // ---------------------------------------------------------------- tmdb

    private data class TmdbMeta(val year: Int?, val poster: String?, val plot: String?)

    /**
     * Optional enrichment: maps the IMDb id to TMDB (Find -> Details) for a better
     * plot/poster/year. Silently skipped when the user has no TMDB key.
     */
    private suspend fun fetchTmdbMeta(imdbId: String, isSeries: Boolean): TmdbMeta? {
        val key = TMDB_API_KEY ?: return null
        val find = runCatching {
            app.get(
                url = "$TMDB_BASE/find/$imdbId?api_key=$key&external_source=imdb_id",
                headers = HEADERS
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.text
            ?.let { runCatching { parseJson<TmdbFindResponse>(it) }.getOrNull() }
            ?: return null

        val match = when {
            isSeries -> find.tv_results.firstOrNull()
            find.tv_results.isNotEmpty() && find.movie_results.isEmpty() ->
                find.tv_results.firstOrNull()
            else -> find.movie_results.firstOrNull() ?: find.tv_results.firstOrNull()
        } ?: return null

        val kind = if (isSeries) "tv" else "movie"
        val media = runCatching {
            app.get(url = "$TMDB_BASE/$kind/${match.id}?api_key=$key", headers = HEADERS)
        }.getOrNull()?.takeIf { it.isSuccessful }?.text
            ?.let { runCatching { parseJson<TmdbMedia>(it) }.getOrNull() }
            ?: return null

        val dateRaw = media.release_date ?: media.first_air_date
        return TmdbMeta(
            year = dateRaw?.substringBefore('-')?.toIntOrNull(),
            poster = media.poster_path?.let {
                if (it.startsWith("/")) "$TMDB_IMAGE_BASE$it" else it
            },
            plot = media.overview?.takeIf { it.isNotBlank() }
        )
    }

    // -------------------------------------------------------------- helpers

    private data class QualityRow(
        val label: String,
        val resolution: String?,
        val extra: String?,
        val size: String?
    )

    private data class QualityTable(val rows: List<QualityRow>, val mirrors: Int)

    private fun WpPost.posterUrl(): String? =
        _embedded?.featuredMedia?.firstOrNull()?.source_url?.takeIf { it.isNotBlank() }

    private fun WpPost.toSearchResult(): SearchResponse? {
        val display = cleanTitle(title?.rendered)
        if (display.isBlank()) return null
        remember(this)
        val searchUrl = link?.takeIf { it.isNotBlank() }
            ?: slug?.takeIf { it.isNotBlank() }?.let { "$mainUrl/$it/" }
            ?: return null
        val poster = posterUrl()
        val year = YEAR_IN_PARENTHESES.find(display)?.groupValues?.get(1)?.toIntOrNull()

        return if (SERIES_PATTERN.containsMatchIn(display)) {
            newTvSeriesSearchResponse(display, searchUrl) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(display, searchUrl) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    /** `https://new.bollyflix.vote/<slug>/` -> `<slug>` */
    private fun slugFromUrl(url: String): String? =
        url.substringAfter("$mainUrl/", url)
            .substringBefore('?')
            .substringBefore('#')
            .split('/')
            .firstOrNull { it.isNotBlank() && !it.startsWith("wp-") }

    /**
     * Post titles are noisy:
     * `Download Pathaan (2023) Hindi Movie 480p | 720p | 1080p | 2160p WEB-DL ESub`.
     * Keep `Name (Year) Language Movie/Web Series`.
     */
    private fun cleanTitle(raw: String?): String {
        val text = htmlToText(raw)
        if (text.isBlank()) return ""
        var out = text.replace(DOWNLOAD_PREFIX_PATTERN, "")
        QUALITY_TAIL_PATTERN.find(out)?.let { out = out.substring(0, it.range.first) }
        return out.replace(WHITESPACE_PATTERN, " ")
            .trim()
            .trim('-', '|', ',', ':', '\u2013', '\u2014')
            .trim()
    }

    private fun cleanEpisodeName(raw: String, showName: String): String {
        var out = EXTENSION_PATTERN.replace(raw.trim(), "")
        if (showName.isNotBlank()) {
            out = out.replace(Regex("(?i)^${Regex.escape(showName)}\\s+"), "")
        }
        return out.replace(WHITESPACE_PATTERN, " ").trim()
    }

    /** `Released Year:` from the details list, falling back to the imdb date line. */
    private fun yearFromContent(content: String): Int? {
        valueAfterLabel(content, "Released Year:")?.toIntOrNull()?.let { return it }
        return IMDB_DATE_PATTERN.find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Text between a `Label:` marker and the end of the html element holding it. */
    private fun valueAfterLabel(html: String, label: String): String? {
        val idx = html.indexOf(label, ignoreCase = true)
        if (idx < 0) return null
        val rest = html.substring(idx + label.length)
        val stop = LABEL_END_PATTERN.find(rest)?.range?.first ?: -1
        val end = if (stop in 0..MAX_LABEL_LENGTH) stop else minOf(rest.length, MAX_LABEL_LENGTH)
        var text = htmlToText(rest.substring(0, end))
        // The details list emits an empty value for labels the site has no data
        // for ("Released Year: " followed straight by the next <li>), so cut the
        // next sibling label off when one shows up right at the start.
        SIBLING_LABEL_PATTERN.find(text)?.let { sibling ->
            if (sibling.range.first <= MAX_SIBLING_OFFSET) {
                text = text.substring(0, sibling.range.first)
            }
        }
        return text
            .replace(SUMMARY_TAIL_PATTERN, "")
            .trim()
            .trimEnd('.', ',', ';')
            .trim()
            .ifBlank { null }
    }

    private fun htmlToText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return html
            .replace(BLOCK_BREAK_PATTERN, " ")
            .replace(TAG_PATTERN, "")
            .replace(ENTITY_PATTERN) { decodeEntity(it) }
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
    }

    private fun decodeEntity(match: MatchResult): String {
        val body = match.groupValues[1]
        if (body.startsWith("#")) {
            val code = body.substring(1)
            val value = if (code.startsWith("x", ignoreCase = true)) {
                code.substring(1).toIntOrNull(16)
            } else {
                code.toIntOrNull()
            }
            return value?.takeIf { it in 1..0x10FFFF }?.toChar()?.toString() ?: match.value
        }
        return NAMED_ENTITIES[body.lowercase()] ?: match.value
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val WP_BASE = "https://new.bollyflix.vote/wp-json/wp/v2"
        const val WP_POSTS = "$WP_BASE/posts"
        const val WP_CATEGORIES = "$WP_BASE/categories"
        const val TORRENTIO_STREAM = "https://torrentio.strem.fun/stream/%s/%s.json"

        // TMDB — optional metadata enrichment, requires the user's own key in
        // Settings -> Player -> Metadata. Without a key every fetch is skipped.
        val TMDB_API_KEY: String?
            get() = tmdbApiKeyOverride?.takeIf { it.isNotBlank() }
        const val TMDB_BASE = "https://api.themoviedb.org/3"
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

        const val MOVIE_PREFIX = "m:"
        const val PER_PAGE = 20

        /**
         * Home rows and search only need card fields. `_embed=1` inlines the
         * whole rendered post for every row — 2.4 MB for 20 results — while
         * embedding only the featured media and dropping the unused keys
         * returns the same data in a fraction of the size, which matters on a
         * CDN that resets large responses.
         */
        const val LIST_FIELDS =
            "_embed=wp:featuredmedia" +
                "&_fields=id,date,slug,link,title,categories,tags,featured_media,_embedded"

        /**
         * The CDN drops a share of requests outright, so retry — but keep the
         * spacing short: five home rows are fetched one after another, and the
         * home screen is on the launch path.
         */
        const val LIST_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L

        /** How long a fetched row/page is reused before asking again. */
        const val LIST_CACHE_TTL_MS = 10 * 60 * 1000L
        const val MAX_LIST_CACHE = 60
        const val MAX_MOVIE_LINKS = 12
        const val MAX_EPISODES = 500
        const val MAX_QUALITY_ROWS = 12
        const val MAX_SLUG_CACHE = 2000
        const val CATEGORY_CACHE_TTL = 6 * 60 * 60 * 1000L
        const val MAX_LABEL_LENGTH = 1200
        const val MAX_SIBLING_OFFSET = 40

        val HEADERS = mapOf("User-Agent" to "Mozilla/5.0")
        val BROWSER_HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        /** Measured ids, used only when the runtime slug lookup comes up empty. */
        val FALLBACK_CATEGORY_IDS = mapOf(
            "bollywood" to 2,
            "hollywood" to 15,
            "dual-audio-movies" to 14,
            "hindi-dubbed-movies-480p-720p" to 13,
            "korean-drama" to 37003,
            "netflix" to 38,
            "1080p-movies" to 51,
            "punjabi" to 16,
            "south-hindi-dubbed" to 48
        )

        val IMDB_TITLE_PATTERN = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
        val IMDB_DATE_PATTERN = Regex("""\|\s*[A-Za-z]+\.?\s+\d{1,2},\s*(\d{4})""")
        val YEAR_IN_PARENTHESES = Regex("""\((\d{4})\)""")
        val SERIES_PATTERN =
            Regex("""web\s*series|\bseason\s*\d""", RegexOption.IGNORE_CASE)

        val QUALITY_ROW_PATTERN =
            Regex("""<h5[^>]*>([\s\S]*?)</h5>""", RegexOption.IGNORE_CASE)
        val DL_ANCHOR_PATTERN = Regex("""class=["']dl["']""", RegexOption.IGNORE_CASE)
        val RESOLUTION_PATTERN = Regex("""\d{3,4}p\b""", RegexOption.IGNORE_CASE)
        val SIZE_BRACKET_PATTERN = Regex("""\[([^\]]{1,24})\]""")
        val BRACKET_PATTERN = Regex("""[(){}\[\]]""")

        val DOWNLOAD_PREFIX_PATTERN = Regex("""^\s*download\s+""", RegexOption.IGNORE_CASE)
        val QUALITY_TAIL_PATTERN = Regex(
            """\s+(?:\d{3,4}p\b|\d{3,4}x\d{3,4}\b|web-?dl\b|web-?rip\b|blu-?ray\b|""" +
                """hdtv\b|hdtsv\b|dual\s+audio\b).*$""",
            RegexOption.IGNORE_CASE
        )

        val EPISODE_PATTERN = Regex("""S(\d{1,2})E(\d{1,4})""")
        val SEEDERS_PATTERN = Regex("""\uD83D\uDC64\s*(\d+)""")
        val SIZE_PATTERN = Regex("""\uD83D\uDCBE\s*([\d.,]+\s*\w+)""")
        val SOURCE_PATTERN = Regex("""\u2699\uFE0F?\s*([^\n|]+)$""")
        val EXTENSION_PATTERN =
            Regex("""\.(mkv|mp4|avi|mov|m4v|webm|mpg|mpeg|ts|m2ts)$""", RegexOption.IGNORE_CASE)

        val BLOCK_BREAK_PATTERN =
            Regex("""</(?:p|div|li|ul|h[1-6]|tr|br)\b[^>]*>|<br[^>]*>""", RegexOption.IGNORE_CASE)
        val TAG_PATTERN = Regex("""<[^>]*>""")
        val ENTITY_PATTERN = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
        val LABEL_END_PATTERN = Regex("""</span>|</li>|</p>|<br""", RegexOption.IGNORE_CASE)
        val SIBLING_LABEL_PATTERN = Regex("""[A-Z][A-Za-z ]{2,15}:""")
        val SUMMARY_TAIL_PATTERN =
            Regex("""\s*[.…]{1,3}\s*(?:Read more|Read all)\s*$""", RegexOption.IGNORE_CASE)
        val WHITESPACE_PATTERN = Regex("""\s+""")

        val NAMED_ENTITIES = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "hellip" to "\u2026",
            "ndash" to "\u2013",
            "mdash" to "\u2014",
            "lsquo" to "\u2018",
            "rsquo" to "\u2019",
            "ldquo" to "\u201c",
            "rdquo" to "\u201d"
        )
    }
}

// ------------------------------------------------------------- api models

@Serializable
private data class WpRendered(val rendered: String? = null)

@Serializable
private data class WpFeaturedMedia(val source_url: String? = null)

@Serializable
private data class WpEmbedded(
    @SerialName("wp:featuredmedia") val featuredMedia: List<WpFeaturedMedia>? = null
)

/** `wp-json/wp/v2/posts` item — the API sends far more fields than these. */
@Serializable
private data class WpPost(
    val id: Int = 0,
    val date: String? = null,
    val slug: String? = null,
    val link: String? = null,
    val title: WpRendered? = null,
    val excerpt: WpRendered? = null,
    val content: WpRendered? = null,
    val categories: List<Int> = emptyList(),
    val tags: List<Int> = emptyList(),
    val featured_media: Int? = null,
    val _embedded: WpEmbedded? = null
)

/** `wp-json/wp/v2/categories` item. */
@Serializable
private data class WpCategory(
    val id: Int = 0,
    val count: Int = 0,
    val slug: String? = null,
    val name: String? = null
)

/**
 * Torrentio public instance — https://torrentio.strem.fun/stream/{movie|series}/{imdbId}.json
 *
 * Quality/size/seeders are not top-level fields; they are packed into the `name`
 * label, the multi-line `title` string (with 👤/💾/⚙️ markers) and
 * `behaviorHints.filename`.
 */
@Serializable
private data class TorrentioResponse(val streams: List<TorrentioStream> = emptyList())

@Serializable
private data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: TorrentioHints? = null
)

@Serializable
private data class TorrentioHints(
    val bingeGroup: String? = null,
    val filename: String? = null
)

/** TMDB Find — maps an IMDb id to a TMDB id. */
@Serializable
private data class TmdbFindResponse(
    val movie_results: List<TmdbResult> = emptyList(),
    val tv_results: List<TmdbResult> = emptyList()
)

@Serializable
private data class TmdbResult(val id: Int)

/** TMDB Details — plot, poster and release date. */
@Serializable
private data class TmdbMedia(
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val poster_path: String? = null
)