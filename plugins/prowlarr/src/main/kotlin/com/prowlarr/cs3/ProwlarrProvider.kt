package com.prowlarr.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.prowlarrApiKey
import com.lagradost.cloudstream3.metaproviders.prowlarrBaseUrl
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A torrent source backed by the user's own Prowlarr instance.
 *
 * Prowlarr aggregates whatever indexers the user has configured behind one API, which
 * is the point: every other torrent source here leans on a single public endpoint, and
 * those go down, get geo blocked, or change their search behaviour. Here the indexers
 * are the user's choice and the API key stays on their own machine.
 *
 * The instance address and key come from Settings -> Player -> Prowlarr and reach us
 * as [prowlarrBaseUrl] and [prowlarrApiKey]; a plugin cannot read the app's own
 * preferences. Until they are set every entry point here returns null and the provider
 * stays quietly empty rather than failing loudly.
 *
 * Only `GET /api/v1/search` is called. Prowlarr's `/api/v1/indexer*` routes configure
 * Prowlarr itself and are none of this plugin's business.
 */
class ProwlarrProvider : MainAPI() {

    override var name = "Prowlarr"
    override var mainUrl = prowlarrBaseUrl() ?: PLACEHOLDER_MAIN_URL

    override val hasQuickSearch = true
    /**
     * Movies only. Prowlarr's JSON search has no season/episode parameters, so a
     * series query would return a flat pile of unrelated seasons with nothing to
     * tell them apart; the Torznab route is where that filtering lives. Movies are
     * the honest scope until an episode path is added.
     *
     * Search rows carry no poster on purpose. ReleaseResource advertises a posterUrl,
     * but a live instance returns it empty for every release, so the app drawing its
     * grey placeholder is correct rather than a defect to chase.
     */
    override val supportedTypes = setOf(TvType.Movie)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

        // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return null
        // Prowlarr's own paging is offset based and its result set is a flat text
        // match, so a "next page" would mostly re-offer what page 1 already showed.
        // Cap it rather than pretending the list pages cleanly.
        if (page > MAX_PAGES) return null

        val releases = fetchReleases(q).filter { it.hasHash() && it.isMovie() }
        if (releases.isEmpty()) return null

        // Prowlarr ignores `limit`, so a broad query can return far more than the
        // result list can usefully hold. Capping here keeps the top of the ranking -
        // the best seeded releases, which are the ones worth opening.
        val items = bestPerTitle(releases).take(MAX_RESULTS).map { release ->
            val title = cleanTitle(release.title)
            // A Movie response, not a Torrent one: the app's search list is built
            // from the provider's declared supportedTypes, and Torrent-typed results
            // are not rendered there. The magnet below is what makes it torrent
            // playback; the row type only says what the entry is.
            // fix = false: newMovieSearchResponse rewrites the url through the provider's
            // mainUrl by default, which would turn this internal token into
            // "http://<prowlarr>/prowlarr-movie|..." and make the app try to open it
            // in a browser. Only load() consumes it, and it is not an address.
            newMovieSearchResponse(title, movieUrl(title, yearOf(release), release.infoHash.orEmpty()), fix = false) {
                // Must be set on the row, not just baked into the url: the app's
                // year filter drops any result whose releaseYear() is null
                // (SearchFilter), and releaseYear() reads this field.
                year = yearOf(release)
                quality = searchQuality(release)
            }
        }
        if (items.isEmpty()) return null
        Log.i(TAG, "Prowlarr emitting ${items.size} row(s) for \"$q\" (cap $MAX_RESULTS)")
        return newSearchResponseList(items, page < MAX_PAGES)
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseUrl(url) ?: return null
        // There is no by-id endpoint: Prowlarr only exposes what the indexers hold,
        // reached through search, so the title is the lookup key and loadLinks() does
        // the matching against a fresh read of the indexers. No release is fetched
        // here, which keeps opening a film to a single request.
        return newMovieLoadResponse(ref.title, url, TvType.Movie, url) {
            year = ref.year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = parseUrl(data) ?: return false
        var emitted = 0

        // The release the row carries goes first: it is the one the user chose, and
        // rows are ranked so it is the best seeded match. Offered from the data string
        // alone, with no request, so it is available even if the search endpoint is
        // slow or unreachable at play time.
        if (ref.infoHash.length in 32..40) {
            callback(magnetLink(ref.title, ref.infoHash, size = 0L, seeders = 0, indexer = null))
            emitted++
        }

        // Then one release per remaining quality tier. Offering only the single
        // carried hash left no way past a dead torrent, and a dead first link is the
        // common case: seeding counts swing wildly between releases of the same film.
        if (emitted < MAX_LINKS) {
            val alternatives = fetchReleases(ref.title)
                .filter { matches(ref.title, it) }
                .filter { it.hasHash() && it.isMovie() }
                // Already offered above; a repeat would show the same file twice.
                .filterNot { it.infoHash.equals(ref.infoHash, ignoreCase = true) }
            for (hit in bestPerQuality(alternatives)) {
                callback(magnetLink(hit))
                if (++emitted >= MAX_LINKS) break
            }
        }
        return emitted > 0
    }

    // ------------------------------------------------------------- releases

    /**
     * Ask Prowlarr for everything matching [query]. The key goes in the X-Api-Key
     * header so it does not appear in the URL and leak through HTTP logs.
     */
    private suspend fun fetchReleases(query: String): List<ProwlarrRelease> {
        syncBaseUrl()
        val key = prowlarrApiKey?.trim().orEmpty()
        if (key.isEmpty()) {
            Log.i(TAG, "No Prowlarr key set; add one in Settings -> Player -> Prowlarr")
            return emptyList()
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            // Prowlarr answers a bare JSON array here, which AppUtils.parseJson
            // (which wraps its input in an envelope) cannot read, so it is decoded
            // directly.
            val response = app.get(
                url = "$mainUrl/api/v1/search?query=$encoded&limit=$SEARCH_LIMIT",
                headers = mapOf("X-Api-Key" to key)
            )
            if (!response.isSuccessful) {
                // 401 means the key is wrong; the body is a {"detail": ...} object,
                // not the array, so it must not be decoded as one.
                Log.w(TAG, "Prowlarr search failed: HTTP ${response.code}")
                return emptyList()
            }
            ProwlarrRelease.parseArray(response.text).also { hits ->
                Log.i(TAG, "Prowlarr returned ${hits.size} release(s) for \"$query\"")
            }
        } catch (c: CancellationException) {
            // The search was superseded (a new query, or the screen going away).
            // Swallowing this as a failure both hides the reason and turns an
            // ordinary cancel into a bogus "0 results".
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Prowlarr search failed for \"$query\": ${t.message}")
            emptyList()
        }
    }

    /** Prowlarr's search is a text match, so a title query also returns every other
     * film that shares a word with it ("Dune" -> "Dune: Prophecy"). Require the
     * significant words of the query to appear in the release name. */
    private fun matches(query: String, release: ProwlarrRelease): Boolean {
        val name = release.title?.lowercase(Locale.ROOT) ?: return false
        return query.trim().lowercase(Locale.ROOT)
            .split(' ', '-')
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .all { name.contains(it) }
    }

    /**
     * One row per distinct film: a title query returns many identical releases from
     * different indexers, which would otherwise flood the results with copies of the
     * same film. The surviving row is the one most likely to actually play, and the rows
     * come back best-first.
     *
     * Seeders decide, not resolution. A 2160p with a single seeder is a dead end - it
     * pre-buffers to nothing - while a 1080p with fifty streams immediately, and on
     * the same tier the larger file is the better pick. Resolution only breaks a tie,
     * because two releases with the same seed count are equally available.
     */
    private fun bestPerTitle(releases: List<ProwlarrRelease>): List<ProwlarrRelease> {
        val best = LinkedHashMap<String, ProwlarrRelease>()
        for (release in releases) {
            val title = cleanTitle(release.title)
            if (title.isEmpty()) continue
            val key = titleKey(title)
            val current = best[key]
            if (current == null || isBetter(release, current)) best[key] = release
        }
        return best.values.sortedByDescending { availabilityRank(it) }
    }

    /**
     * Higher is more likely to play. Seeders dominate; quality only orders releases
     * that are equally available, so a lone-seeder 4K never outranks a seeded 1080p.
     */
    private fun availabilityRank(release: ProwlarrRelease): Long =
        release.seeders.toLong() * RANK_QUALITY +
            qualityValue(qualityLabel(release.title.orEmpty()))

    private fun isBetter(a: ProwlarrRelease, b: ProwlarrRelease): Boolean =
        availabilityRank(a) > availabilityRank(b)

    /**
     * Keep the best seeded release per quality tier. Prowlarr's ordering is by
     * relevance, which across several indexers routinely puts a well seeded 1080p
     * below a single seeder 2160p.
     */
    private fun bestPerQuality(releases: List<ProwlarrRelease>): List<ProwlarrRelease> {
        val best = LinkedHashMap<String, ProwlarrRelease>()
        for (release in releases) {
            val label = qualityLabel(release.title.orEmpty())
            val current = best[label]
            if (current == null || release.seeders > current.seeders) best[label] = release
        }
        return best.entries
            .sortedByDescending { (_, release) ->
                qualityValue(qualityLabel(release.title.orEmpty())) * RANK_QUALITY + release.seeders
            }
            .map { it.value }
    }

    

    // ----------------------------------------------------------------- links

    private suspend fun magnetLink(
        releaseName: String,
        infoHash: String,
        size: Long,
        seeders: Int,
        indexer: String?,
    ): ExtractorLink {
        val label = qualityLabel(releaseName)
        // Rebuilt from the hash on purpose: Prowlarr's magnetUrl and downloadUrl are
        // both redirects through its web UI with the API key in the query string, so
        // handing either to a debrid service or a player would leak it.
        val details = listOfNotNull(
            label.takeIf { it.isNotBlank() },
            formatSize(size).takeIf { it.isNotBlank() },
            "${seeders} seeders".takeIf { seeders > 0 },
            indexer?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
        return newExtractorLink(
            source = name,
            name = details.ifBlank { "Torrent stream" },
            url = magnet(infoHash),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    private suspend fun magnetLink(release: ProwlarrRelease): ExtractorLink =
        magnetLink(
            releaseName = release.title.orEmpty(),
            infoHash = release.infoHash.orEmpty(),
            size = release.size,
            seeders = release.seeders,
            indexer = release.indexer,
        )

    private fun magnet(infoHash: String): String = "magnet:?xt=urn:btih:$infoHash"

    // ----------------------------------------------------------------- urls

    /**
 * "{mainUrl}/{marker}|{url encoded title}|{year}|{infoHash}".
 *
 * The mainUrl prefix is load bearing: the app routes a search result back to its
 * provider with `allProviders.firstOrNull { url.startsWith(it.mainUrl) }`, so a url
 * that does not start with this provider's mainUrl matches nothing and the result is
 * dead on arrival (the app offers to open it in a browser instead).
 *
 * The hash travels with the row. An earlier version re-ran the search at play time
 * and re-matched the words of the title, which can never succeed: the candidates are
 * *other* releases of the same film, so they share the film name but none of the
 * release-group, codec or tracker words in the row's own title. Carrying the hash
 * also means the link cannot go stale if the indexer re-seeds a torrent and Prowlarr
 * hands out a new hash.
 */
    private fun movieUrl(title: String, year: Int?, infoHash: String): String =
        "$mainUrl/$URL_MARKER|${URLEncoder.encode(title, "UTF-8")}|$year|${infoHash.orEmpty()}"

    private fun parseUrl(url: String): MediaRef? {
        // search() hands back "{mainUrl}/prowlarr-movie|title|year|hash"; strip the
        // mainUrl prefix to get back to the token. A bare token is accepted too, so a
        // data string saved by an older install still resolves.
        val trimmed = url.removePrefix(mainUrl).removePrefix("/")
        val parts = trimmed.split('|')
        if (parts.firstOrNull() != URL_MARKER) return null
        val title = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            .orEmpty()
        if (title.isEmpty()) return null
        return MediaRef(
            title = title,
            year = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 },
            infoHash = parts.getOrNull(3)?.trim().orEmpty(),
        )
    }

    private class MediaRef(
        val title: String,
        val year: Int?,
        /** Empty for rows saved before the hash was carried in the url. */
        val infoHash: String,
    )

    /** Picks up an address entered after the provider was constructed. */
    private fun syncBaseUrl() {
        prowlarrBaseUrl()?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
    }

    // --------------------------------------------------------------- parsing

    /**
     * Reduce an indexer release name to something a person would search for.
     *
     * Release names are noise-heavy: "Dune.Part.Two.2024.2160p.WEBRip.x265-GROUP"
     * must come out as "Dune Part Two", or every film appears under a different row
     * depending on the group that uploaded it.
     *
     * Handled, in order: a leading tracker tag ("[TR24] Dune"), the year in
     * parentheses, a trailing bracketed block, the resolution and everything after it,
     * an episode marker (the provider is films only), the alt-title after a slash,
     * and finally the separators themselves. A title that legitimately begins with a
     * bracket is kept - "(500) Days of Summer" is a film, not a tracker tag.
     */
    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var title = raw.trim()

        // "Дюна: Часть вторая / Dune: Part Two" - the first name is usually the one
        // the user searched for.
        title = title.substringBefore(" / ").trim()

        title = LEADING_TRACKER_TAG.replace(title, "")
        title = title.replace(TITLE_YEAR, "")
        title = title.replace(BRACKET_TAGS, "")
        // "Dune Part Two 2024 1080p BluRay x265" -> stop at the resolution.
        title = RESOLUTION_OR_TAG.find(title)?.let { title.substring(0, it.range.first) } ?: title
        // Films only: "Dune Prophecy S01E03 Sisterhood" is an episode.
        title = EPISODE_MARKER.replace(title, "")

        // Separators: dots and underscores are word breaks, not part of the name.
        return title.replace('.', ' ').replace('_', ' ')
            .replace(SEPARATOR_RUN, " ")
            .trim()
    }

    /**
     * The key [cleanTitle] output is grouped and titled under.
     *
     * Clean titles still differ for one film - "Dune Part Two", "Dune: Part Two",
     * "Dune Part Two 2024" - and grouping on them literally split that film across a
     * dozen rows, each holding a fraction of its seeders and none of them the number
     * the film actually has. Dropping the year and punctuation for grouping collapses
     * them into one row that keeps the best seeded release among them.
     *
     * The year stays in [cleanTitle] for display: the app filters on it and a blank
     * reads as unknown.
     */
    private fun titleKey(title: String): String = title
        .replace(YEAR_IN_TITLE, "")
        .replace(STANDALONE_YEAR, "")
        .replace(IGNORED_PUNCTUATION, " ")
        .replace(SEPARATOR_RUN, " ")
        .trim()
        .lowercase(Locale.ROOT)

    /**
     * The release's year, for the app's year filter and the search row.
     *
     * The "(2021)" group in the name is the film's own year and so the accurate
     * answer, but most indexer names omit it ("Dune.Awakening.REPACK-KaOs"), and the
     * app discards any row whose year it cannot resolve whenever a year filter is set
     * (SearchFilter drops `year == null` outright). publishDate is always present and
     * is the fallback, but note it is the date the release was *uploaded*, not the
     * film's year, so a 2021 film seeded this month reads as this year. That is
     * deliberate: a slightly wrong year keeps the row visible, whereas null hides
     * the release entirely.
     */
    private fun yearOf(release: ProwlarrRelease): Int? =
        YEAR_IN_TITLE.find(release.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1880..2100 }
            ?: release.publishDate?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }

    private fun ProwlarrRelease.hasHash(): Boolean =
        infoHash?.trim()?.length in 32..40

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        else -> "%.0f kB".format(bytes / 1_000.0)
    }

    /** The resolution named in a release title, e.g. "2160p" out of "... 1080p WEB-DL". */
    private fun qualityLabel(title: String): String {
        val name = title.lowercase(Locale.ROOT)
        return when {
            RESOLUTION_PATTERN.containsMatchIn(name) -> RESOLUTION_PATTERN.find(name)!!
                .value.lowercase(Locale.ROOT)
            UHD_PATTERN.containsMatchIn(name) -> "2160p"
            else -> ""
        }
    }

    /** The app's own quality enum, so search results sort by resolution. */
    private fun searchQuality(release: ProwlarrRelease): SearchQuality? =
        when (qualityLabel(release.title.orEmpty())) {
            "2160p" -> SearchQuality.FourK
            "1080p", "720p" -> SearchQuality.HD
            "480p" -> SearchQuality.SD
            else -> null
        }

    /** Numeric rank used to order links among themselves. */
    private fun qualityValue(label: String): Int = when (label) {
        "2160p" -> 4
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        else -> 0
    }

    private companion object {
        const val TAG = "Prowlarr"
        const val PLACEHOLDER_MAIN_URL = "http://prowlarr"
        const val URL_MARKER = "prowlarr-movie"

        /**
         * Sent for completeness, but Prowlarr ignores it: limit=5 and limit=100 both
         * return every match, so this is not a cap on the result set. [MAX_RESULTS] is
         * what actually bounds the list.
         */
        const val SEARCH_LIMIT = 100

        /** Rows kept from the ranking. The rest are progressively worse releases. */
        const val MAX_RESULTS = 100

        const val MAX_LINKS = 5

        /**
         * One page. The endpoint ignores `limit` and its `offset` is not documented,
         * so there is no reliable way to ask for a second page; the whole match set
         * arrives at once and is deduplicated locally.
         */
        const val MAX_PAGES = 1

        /** Matches "1080p" but not "21080p" or the year "1080". */
        val RESOLUTION_PATTERN = Regex("""\b(2160|1080|720|480)[pi]\b""")

        val UHD_PATTERN = Regex("""\b(4k|uhd)\b""")

        /** A four digit year in parentheses: "Dune (2021)" -> "2021". */
        val YEAR_IN_TITLE = Regex("""\((\d{4})\)""")

        /**
         * A year in parentheses that is not the title's own first character, so
         * "(500) Days of Summer" survives: the (500) there is the title.
         */
        val TITLE_YEAR = Regex("""(?<=.)\(\d{4}\).*""")

        /** Trailing [...] tags that are not the title's own leading character. */
        val BRACKET_TAGS = Regex("""(?<=.)\[.*""")

        /**
         * A leading tracker tag: "[TR24] Dune", "(ExSe) Dune". Only at the very start,
         * for the reason above - a leading (500) is a number, not digits in a year
         * group, so it cannot match here.
         */
        val LEADING_TRACKER_TAG = Regex("""^\[[^\]]{1,12}\]\s*""")

        /** Where the release name stops being a title: a resolution or a codec. */
        val RESOLUTION_OR_TAG = Regex(
            """\b\d{3,4}[pi]\b|\b(?:WEB-?DL|WEBRip|BluRay|BRRip|REMUX|DVDRip|HDTV)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Episode markers, for the films-only provider. */
        val EPISODE_MARKER = Regex("""\bS\d{1,2}E\d{1,3}\b.*""", RegexOption.IGNORE_CASE)

        /** Runs of separators left behind after dots and underscores become spaces. */
        val SEPARATOR_RUN = Regex("""\s{2,}""")

        /** A bare four digit year left in a cleaned title: "Dune Part Two 2024". */
        val STANDALONE_YEAR = Regex("""\b\d{4}\b""")

        /**
         * Punctuation that carries no meaning for grouping, so "Dune: Part Two" and
         * "Dune Part Two" land on the same key.
         */
        val IGNORED_PUNCTUATION = Regex("[^\\p{L}\\p{N}]+")

        val STOP_WORDS = setOf("the", "and", "of", "a", "an", "to", "in")
        const val MIN_WORD_LENGTH = 3

        /** Lets a 2160p release outrank a 1080p one only past this seed gap. */
        const val RANK_QUALITY = 1000L
    }
}