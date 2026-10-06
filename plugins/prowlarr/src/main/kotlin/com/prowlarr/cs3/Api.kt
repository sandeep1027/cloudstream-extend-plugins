package com.prowlarr.cs3

import kotlinx.serialization.Serializable

/**
 * One row from Prowlarr's JSON search API (`GET /api/v1/search`), shaped from the
 * `ReleaseResource` schema in Prowlarr's published OpenAPI document.
 *
 * The id fields (imdbId, tmdbId, tvdbId, tvMazeId) are deliberately absent: Prowlarr
 * types them as integers but indexers fill them with either a number or an IMDb
 * string, and a mismatch would fail deserialising the whole response. The JSON
 * endpoint takes no id parameter either, so nothing here uses them today. The
 * Torznab endpoint (`/api?t=tvsearch&imdbid=...`) is where id based search lives if
 * it is ever wanted.
 */
@Serializable
data class ProwlarrRelease(
    val guid: String? = null,
    val title: String? = null,
    val fileName: String? = null,
    val size: Long = 0,
    val files: Int? = null,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val publishDate: String? = null,
    val indexer: String? = null,
    val protocol: String? = null,
    /**
     * Prowlarr's own magnetUrl is a redirect through its web UI that carries the API
     * key, so it is never handed to a debrid or a player. The magnet is rebuilt from
     * [infoHash] instead, which is all a debrid needs.
     */
    val infoHash: String? = null,
    val magnetUrl: String? = null,
)

/** The endpoint answers with a bare array; failures answer with a detail string. */
@Serializable
data class ProwlarrError(
    val detail: String? = null,
    val error: String? = null,
)