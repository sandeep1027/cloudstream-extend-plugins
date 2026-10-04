package com.torrin.cs3

import kotlinx.serialization.Serializable

/** IMDB public suggestion API — https://v2.sg.media-imdb.com/suggestion/{letter}/{query}.json */
@Serializable
data class ImdbSuggestResponse(
    val d: List<ImdbSuggest> = emptyList()
)

@Serializable
data class ImdbSuggest(
    val id: String? = null,   // "tt0816692"
    val l: String? = null,    // label / title
    val y: String? = null,    // year (string in the API)
    val qid: String? = null,  // "title" (movie) | "tvSeries" | "videoGame" | ...
    val s: String? = null,    // movies: synopsis; tv: cast
    val q: String? = null,    // "2014 • Christopher Nolan, ..."
    val i: ImdbImage? = null
)

@Serializable
data class ImdbImage(
    val imageUrl: String? = null
)

/**
 * Torrentio public instance — https://torrentio.strem.fun/stream/{movie|series}/{imdbId}.json
 *
 * Quality/size/seeders are not top-level fields; they are packed into the
 * `name` label, the multi-line `title` string (with 👤/💾/⚙️ markers) and
 * `behaviorHints.bingeGroup`.
 */
@Serializable
data class TorrentioResponse(
    val streams: List<TorrentioStream> = emptyList()
)

@Serializable
data class TorrentioStream(
    val name: String? = null,     // "Torrentio\n4k DV | HDR10+"
    val title: String? = null,    // "Release\nPerFile.mkv\n👤 238 💾 27.63 GB ⚙️ Source"
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: TorrentioHints? = null
)

@Serializable
data class TorrentioHints(
    val bingeGroup: String? = null,
    val filename: String? = null
)

/**
 * TMDB — https://developer.themoviedb.org
 *
 * The plugin resolves titles by their IMDb id (tt...). TMDB's Find API maps an
 * IMDb id to a TMDB id (+ cast), and the Details API returns the plot
 * (overview), poster and release date. Two calls per title, but the metadata
 * is authoritative where IMDB's public suggestion list is sparse.
 */
@Serializable
data class TmdbFindResponse(
    val movie_results: List<TmdbResult> = emptyList(),
    val tv_results: List<TmdbResult> = emptyList()
)

@Serializable
data class TmdbResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null
)

@Serializable
data class TmdbMedia(
    val overview: String? = null,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val poster_path: String? = null
)

/** TMDB Discover — latest releases (sorted by release/air date desc). */
@Serializable
data class TmdbDiscoverResponse(
    val results: List<TmdbDiscoverItem> = emptyList()
)

@Serializable
data class TmdbDiscoverItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val poster_path: String? = null,
    val vote_count: Int? = null
)

/** TMDB Watch Providers — which platforms carry a title (region-scoped). */
@Serializable
data class TmdbProvider(
    val provider_id: Int? = null,
    val provider_name: String? = null
)

/**
 * TMDB Watch Providers — which platforms carry a title.
 *
 * The API ignores `watch_region` and always returns a map of every region:
 * `results: { "IN": {flatrate: [...], free: [...]}, "US": {...}, ... }`.
 */
@Serializable
data class TmdbWatchProvidersRegion(
    val flatrate: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList()
)

@Serializable
data class TmdbWatchProvidersResponse(
    val results: Map<String, TmdbWatchProvidersRegion>? = null
)

/** TMDB External IDs — maps a TMDB id to its IMDb id (tt...). */
@Serializable
data class TmdbExternalIdsResponse(
    val imdb_id: String? = null
)
