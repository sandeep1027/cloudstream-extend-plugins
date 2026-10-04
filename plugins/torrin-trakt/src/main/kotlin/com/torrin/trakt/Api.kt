package com.torrin.trakt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** IMDB public suggestion API — https://v2.sg.media-imdb.com/suggestion/{letter}/{query}.json */
@Serializable
data class ImdbSuggestResponse(
    val d: List<ImdbSuggestItem>
)

@Serializable
data class ImdbSuggestItem(
    val id: String?,
    val qid: String?,
    val l: String?,
    val y: String?,
    val s: String? = null,
    val i: ImdbSuggestImage?
)

@Serializable
data class ImdbSuggestImage(
    val url: String? = null
) {
    val imageUrl: String?
        get() = url?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://media.imdb.com/m/$it"
        }
}

/** Torrentio — https://torrentio.strem.fun/stream/{movie|series}/{ttId}.json */
@Serializable
data class TorrentioResponse(
    val streams: List<TorrentioStream> = emptyList()
)

@Serializable
data class TorrentioStream(
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val behaviorHints: TorrentioBehaviorHints? = null
)

@Serializable
data class TorrentioBehaviorHints(
    val filename: String? = null
)

/** TMDB Find — resolves an IMDb id to a TMDB id (external_source=imdb_id). */
@Serializable
data class TmdbFindResponse(
    val movie_results: List<TmdbMedia> = emptyList(),
    val tv_results: List<TmdbMedia> = emptyList()
)

/** TMDB media object — movie or tv. */
@Serializable
data class TmdbMedia(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

/**
 * Trakt public calendar API — https://trakt.tv/api (public, no auth).
 *
 *  - `GET /calendars/movies/this-week`  -> array of `{released, movie}` entries
 *  - `GET /calendars/shows/this-week`   -> array of `{first_aired, episode, show}` entries
 *
 * Shapes verified against Trakt's official contract schemas
 * (github.com/trakt/trakt-api, projects/api/src/contracts/calendars).
 * `images` is a map of image type -> array of urls (e.g.
 * `{"poster": ["https://..."]}`); the poster is extracted defensively.
 */
@Serializable
data class TraktMovieEntry(
    val released: String? = null,
    val movie: TraktMovie = TraktMovie()
)

@Serializable
data class TraktMovie(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: JsonElement? = null
)

@Serializable
data class TraktShowEntry(
    val first_aired: String? = null,
    val episode: TraktEpisode? = null,
    val show: TraktShow? = null
)

@Serializable
data class TraktEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null
)

@Serializable
data class TraktShow(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: JsonElement? = null
)

@Serializable
data class TraktIds(
    val imdb: String? = null
)

/**
 * Trakt `images` blocks are maps of image type -> url array, e.g.
 * `{"poster": ["https://..."], "fanart": [...]}`. Tolerates a plain string
 * value as well.
 */
fun posterOf(images: JsonElement?): String? {
    val obj = images as? JsonObject ?: return null
    val poster = obj["poster"] ?: return null
    return when (poster) {
        is JsonPrimitive -> poster.content.takeIf { poster.isString }
        is JsonArray -> poster.firstOrNull { it is JsonPrimitive && it.isString }
            ?.let { (it as JsonPrimitive).content }
        else -> null
    }
}
