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
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTorrentSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.Json
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
     * Torrent only. Prowlarr has no season/episode endpoint on the JSON API (that is
     * what the Torznab route is for), so a series query would return a flat pile of
     * unrelated seasons with nothing to tell them apart. Movies are the honest scope
     * until an episode path is added.
     */
    override val supportedTypes = setOf(TvType.Movie)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isEmpty()) return null
        // Prowlarr's own paging is offset based and its result set is a flat text
        // match, so a "next page" would mostly re-offer what page 1 already showed.
        // Cap it rather than pretending the list pages cleanly.
        if (page > MAX_PAGES) return null

        val releases = fetchReleases(q).filter { it.hasHash() }
        if (releases.isEmpty()) return null

        val seen = HashSet<String>()
        val items = releases.mapNotNull { release ->
            val title = cleanTitle(release.title)
            if (title.isEmpty()) return@mapNotNull null
            // One row per release name: a title query returns many identical
            // releases from different indexers, which would otherwise flood the
            // results with copies of the same film.
            if (!seen.add(title.lowercase(Locale.ROOT))) return@mapNotNull null
            newTorrentSearchResponse(title, movieUrl(title, yearOf(release))) {
                quality = searchQuality(release)
            }
        }
        if (items.isEmpty()) return null
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
        val releases = fetchReleases(ref.title)
            .filter { matches(ref.title, it) }
            .filter { it.hasHash() }
        if (releases.isEmpty()) return false

        var emitted = 0
        for (hit in bestPerQuality(releases)) {
            callback(magnetLink(hit))
            if (++emitted >= MAX_LINKS) break
        }
        return emitted > 0
    }

    // ------------------------------------------------------------- releases

    /**
     * Ask Prowlarr for everything matching [query]. The key goes in the query string
     * because that is what the API documents for this route; the host here is the
     * user's own machine on their own network.
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
                "$mainUrl/api/v1/search?query=$encoded&limit=$SEARCH_LIMIT&apikey=$key"
            )
            if (!response.isSuccessful) {
                // 401 means the key is wrong; the body is a {"detail": ...} object,
                // not the array, so it must not be decoded as one.
                Log.w(TAG, "Prowlarr search failed: HTTP ${response.code}")
                return emptyList()
            }
            json.decodeFromString<List<ProwlarrRelease>>(response.text)
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
     * Keep the best seeded release per quality tier. Prowlarr's ordering is by
     * relevance, which across several indexers routinely puts a well seeded 1080p
     * below a single seeder 2160p.
     */
    private fun bestPerQuality(releases: List<ProwlarrRelease>): List<ProwlarrRelease> {
        val best = LinkedHashMap<String, ProwlarrRelease>()
        for (release in releases) {
            val label = qualityLabel(release)
            val current = best[label]
            if (current == null || release.seeders > current.seeders) best[label] = release
        }
        return best.entries
            .sortedByDescending { (_, release) ->
                qualityValue(qualityLabel(release)) * RANK_QUALITY + release.seeders
            }
            .map { it.value }
    }

    

    // ----------------------------------------------------------------- links

    private suspend fun magnetLink(release: ProwlarrRelease): ExtractorLink {
        val label = qualityLabel(release)
        // Rebuilt from the hash on purpose: Prowlarr's own magnetUrl is a redirect
        // through its web UI with the API key in the query string, and handing that
        // to a debrid service or a player would leak the key.
        return newExtractorLink(
            source = name,
            name = releaseLine(release).ifBlank { "Torrent stream" },
            url = magnet(release.infoHash.orEmpty()),
            type = ExtractorLinkType.MAGNET
        ) {
            quality = qualityValue(label)
        }
    }

    private fun magnet(infoHash: String): String = "magnet:?xt=urn:btih:$infoHash"

    // ----------------------------------------------------------------- urls

    /** "{marker}|{url encoded title}|{year}". The year is a hint for display only. */
    private fun movieUrl(title: String, year: Int?): String =
        "$URL_MARKER|${URLEncoder.encode(title, "UTF-8")}|${year ?: ""}"

    private fun parseUrl(url: String): MediaRef? {
        val parts = url.split('|')
        if (parts.firstOrNull() != URL_MARKER) return null
        val title = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            .orEmpty()
        if (title.isEmpty()) return null
        return MediaRef(title, parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 })
    }

    private class MediaRef(val title: String, val year: Int?)

    /** Picks up an address entered after the provider was constructed. */
    private fun syncBaseUrl() {
        prowlarrBaseUrl()?.takeIf { it.isNotBlank() }?.let { mainUrl = it }
    }

    // --------------------------------------------------------------- parsing

    /** "KGF Chapter 2 (2024) [1080p]" -> "KGF Chapter 2". */
    private fun cleanTitle(raw: String?): String = raw
        ?.substringBefore('(')
        ?.substringBefore('[')
        ?.trim()
        .orEmpty()

    private fun yearOf(release: ProwlarrRelease): Int? =
        release.publishDate?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }

    private fun ProwlarrRelease.hasHash(): Boolean =
        infoHash?.trim()?.length in 32..40

    private fun releaseLine(release: ProwlarrRelease): String {
        val label = qualityLabel(release).ifBlank { "" }
        val size = formatSize(release.size).ifBlank { "" }
        val seeders = "${release.seeders} seeders"
        val indexer = release.indexer?.trim().orEmpty()
        return listOf(label, size, seeders, indexer)
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        else -> "%.0f kB".format(bytes / 1_000.0)
    }

    private fun qualityLabel(release: ProwlarrRelease): String {
        val title = release.title.orEmpty().lowercase(Locale.ROOT)
        return when {
            RESOLUTION_PATTERN.containsMatchIn(title) -> RESOLUTION_PATTERN.find(title)!!
                .value.lowercase(Locale.ROOT)
            UHD_PATTERN.containsMatchIn(title) -> "2160p"
            else -> ""
        }
    }

    /** The app's own quality enum, so search results sort by resolution. */
    private fun searchQuality(release: ProwlarrRelease): SearchQuality? =
        when (qualityLabel(release)) {
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

        /** Prowlarr caps `limit` at 250; 100 is ample for one text query. */
        const val SEARCH_LIMIT = 100
        const val MAX_LINKS = 5
        const val MAX_PAGES = 2

        /** Matches "1080p" but not "21080p" or the year "1080". */
        val RESOLUTION_PATTERN = Regex("""\b(2160|1080|720|480)[pi]\b""")

        val UHD_PATTERN = Regex("""\b(4k|uhd)\b""")
        val STOP_WORDS = setOf("the", "and", "of", "a", "an", "to", "in")
        const val MIN_WORD_LENGTH = 3

        /** Lets a 2160p release outrank a 1080p one only past this seed gap. */
        const val RANK_QUALITY = 1000L
    }
}