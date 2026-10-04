package com.torrin.mdblist

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

/** TMDB External IDs — maps a TMDB id to its IMDb id (tt...). */
@Serializable
data class TmdbExternalIdsResponse(
    val imdb_id: String? = null
)

/**
 * MDBList — https://mdblist.com (user supplied free API key).
 *
 * `GET /catalog/movie|show?apikey=***&sort=released&sort_order=desc&released_to=<today>
 * &limit=N&append_to_response=poster,description`
 *
 * Response is wrapped: `{"movies": [...], "pagination": {...}, "quota": {...}}`
 * for the movie endpoint, `{"shows": [...]}` for the show endpoint.
 * `released_to=<today>` is required: without it, `sort=released desc` puts
 * *planned* titles (future release dates) first.
 *
 * Schema (verified 2026-09-29): item has top-level `imdb_id` plus an `ids`
 * object `{mdblist, imdb, tmdb, tvdb}`; `poster` is a TMDB url or null;
 * `status` is e.g. "released" / "Returning Series".
 */
@Serializable
data class MdblistCatalog(
    val movies: List<MdblistItem> = emptyList(),
    val shows: List<MdblistItem> = emptyList()
)

@Serializable
data class MdblistIds(
    val mdblist: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null
)

@Serializable
data class MdblistItem(
    val id: Long? = null,
    val mediatype: String? = null,
    val imdb_id: String? = null,
    val tvdb_id: Long? = null,
    val ids: MdblistIds? = null,
    val title: String? = null,
    val language: String? = null,
    val country: String? = null,
    val release_year: Int? = null,
    val release_date: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    val added_at: String? = null,
    val poster: String? = null,
    val description: String? = null
) {
    /** IMDb id (tt...): top-level `imdb_id` first, `ids.imdb` as fallback. */
    val imdbId: String?
        get() = (imdb_id?.takeIf { it.startsWith("tt") } ?: ids?.imdb?.takeIf { it.startsWith("tt") })
}
