package com.prowlarr.cs3

import org.json.JSONArray
import org.json.JSONObject

/**
 * One row from Prowlarr's JSON search API (`GET /api/v1/search`), shaped from the
 * `ReleaseResource` schema in Prowlarr's published OpenAPI document.
 *
 * Parsed by hand out of org.json rather than with a @Serializable data class on
 * purpose. A generated serializer is compiled against whichever kotlinx.serialization
 * the plugin builds with, but at runtime the classes come from the app's own
 * dependency graph; when those two generations differ, loading the DTO dies with
 * `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()` before a
 * single release is read. org.json ships in the Android framework, so there is no
 * version to keep in step with.
 *
 * The id fields (imdbId, tmdbId, tvdbId, tvMazeId) are deliberately absent: Prowlarr
 * types them as integers but indexers fill them with either a number or an IMDb
 * string. The JSON endpoint takes no id parameter either, so nothing here uses them
 * today. The Torznab endpoint (`/api?t=tvsearch&imdbid=...`) is where id based search
 * lives if it is ever wanted.
 *
 * @property infoHash all a debrid needs; Prowlarr's own magnetUrl is a redirect
 *   through its web UI that carries the API key and must never be passed on.
 */
class ProwlarrRelease private constructor(
    val title: String?,
    val fileName: String?,
    val size: Long,
    val seeders: Int,
    val leechers: Int,
    val publishDate: String?,
    val indexer: String?,
    val protocol: String?,
    val infoHash: String?,
    val categoryIds: Set<Int>,
) {
    /**
     * Whether this could plausibly be a film.
     *
     * Prowlarr's search is a text match across every configured indexer, so a Dune
     * query also returns "Dunebound Tactics" (PC/Games) and "Car S O S S13E03". The
     * indexers do tag categories faithfully, so this is a real filter rather than
     * guesswork. A release with no categories at all is kept: an untagged indexer
     * should not make the whole provider look empty.
     */
    fun isMovie(): Boolean =
        categoryIds.isEmpty() || categoryIds.any { it in MOVIE_CATEGORY_IDS }

    companion object {
        /**
         * Prowlarr's Torznab category tree.
         *
         * Only 2000 (Movies). This provider declares Movie, and when TV (5000) and
         * XXX (6000) were also accepted, single episodes of unrelated shows took over
         * the top of a film search - episodes carry more releases of the same title,
         * so they win the seeder-first ranking even though they are the wrong kind of
         * thing entirely.
         */
        private val MOVIE_CATEGORY_IDS = setOf(2000)

        /**
         * Reads the endpoint's bare JSON array. Returns an empty list for anything
         * that is not an array of objects, so a 401's `{"detail": ...}` body or an
         * HTML error page cannot throw its way out of the caller.
         *
         * Note the endpoint ignores `limit`: `limit=5` and `limit=100` both returned
         * every match, so the caller cannot page or cap the result set from here.
         */
        fun parseArray(text: String): List<ProwlarrRelease> {
            val trimmed = text.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val array = try {
                JSONArray(trimmed)
            } catch (_: Throwable) {
                return emptyList()
            }
            val out = ArrayList<ProwlarrRelease>(array.length())
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                out += ProwlarrRelease(
                    title = row.stringOrNull("title"),
                    fileName = row.stringOrNull("fileName"),
                    size = row.optLong("size", 0L),
                    seeders = row.optInt("seeders", 0),
                    leechers = row.optInt("leechers", 0),
                    publishDate = row.stringOrNull("publishDate"),
                    indexer = row.stringOrNull("indexer"),
                    protocol = row.stringOrNull("protocol"),
                    infoHash = row.stringOrNull("infoHash"),
                    categoryIds = row.categoryIds(),
                )
            }
            return out
        }

        private fun JSONObject.categoryIds(): Set<Int> {
            val array = optJSONArray("categories") ?: return emptySet()
            val ids = HashSet<Int>(array.length())
            for (i in 0 until array.length()) {
                // Each entry is {"id": 4050, "name": "PC/Games", "subCategories": []}
                array.optJSONObject(i)?.optInt("id", 0)?.takeIf { it != 0 }?.let(ids::add)
            }
            return ids
        }

        /** org.json's optString answers "" for absent keys; null is what we want. */
        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}