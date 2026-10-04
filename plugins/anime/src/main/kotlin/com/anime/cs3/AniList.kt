package com.anime.cs3

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AniList GraphQL API models and queries.
 *
 * AniList (https://anilist.co) provides a comprehensive anime database
 * with metadata, search, and user tracking features.
 *
 * API Docs: https://anilist.github.io/ApiV2-GraphQL-Docs/
 */
object AniList {

    const val API_URL = "https://graphql.anilist.co"
    const val IMAGE_BASE = "https://img.anilist.co/anilist.co"

    /**
     * GraphQL query for searching anime by title.
     */
    val SEARCH_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                    total
                    currentPage
                    lastPage
                    hasNextPage
                }
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                    id
                    idMal
                    title {
                        romaji
                        english
                        native
                    }
                    description(asHtml: false)
                    seasonYear
                    episodes
                    status
                    coverImage {
                        large
                        extraLarge
                    }
                    bannerImage
                    genres
                    averageScore
                    format
                }
            }
        }
    """.trimIndent()

    /**
     * GraphQL query for getting trending anime.
     */
    val TRENDING_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                    total
                    currentPage
                    lastPage
                    hasNextPage
                }
                media(type: ANIME, sort: TRENDING_DESC) {
                    id
                    idMal
                    title {
                        romaji
                        english
                        native
                    }
                    description(asHtml: false)
                    seasonYear
                    episodes
                    status
                    coverImage {
                        large
                        extraLarge
                    }
                    bannerImage
                    genres
                    averageScore
                    format
                }
            }
        }
    """.trimIndent()

    /**
     * GraphQL query for getting popular anime.
     */
    val POPULAR_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                    total
                    currentPage
                    lastPage
                    hasNextPage
                }
                media(type: ANIME, sort: POPULARITY_DESC) {
                    id
                    idMal
                    title {
                        romaji
                        english
                        native
                    }
                    description(asHtml: false)
                    seasonYear
                    episodes
                    status
                    coverImage {
                        large
                        extraLarge
                    }
                    bannerImage
                    genres
                    averageScore
                    format
                }
            }
        }
    """.trimIndent()

    /**
     * GraphQL query for getting currently airing anime.
     */
    val AIRING_QUERY = """
        query (${'$'}page: Int, ${'$'}perPage: Int) {
            Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo {
                    total
                    currentPage
                    lastPage
                    hasNextPage
                }
                media(type: ANIME, status: RELEASING, sort: TRENDING_DESC) {
                    id
                    idMal
                    title {
                        romaji
                        english
                        native
                    }
                    description(asHtml: false)
                    seasonYear
                    episodes
                    status
                    coverImage {
                        large
                        extraLarge
                    }
                    bannerImage
                    genres
                    averageScore
                    format
                }
            }
        }
    """.trimIndent()

    /**
     * GraphQL query for getting anime by ID (for loading details).
     */
    val MEDIA_QUERY = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title {
                    romaji
                    english
                    native
                }
                description(asHtml: false)
                seasonYear
                episodes
                status
                coverImage {
                    large
                    extraLarge
                }
                bannerImage
                genres
                averageScore
                format
                studios(isMain: true) {
                    nodes {
                        name
                    }
                }
                relations {
                    edges {
                        relationType
                        node {
                            id
                            title {
                                romaji
                                english
                            }
                            type
                        }
                    }
                }
            }
        }
    """.trimIndent()

    // ─── Response Models ─────────────────────────────────────────────────────

    @Serializable
    data class AniListResponse(
        val data: Data? = null,
        val errors: List<Error>? = null
    )

    @Serializable
    data class Error(
        val message: String = "",
        val status: Int = 0
    )

    @Serializable
    data class Data(
        val Page: PageData? = null,
        val Media: MediaData? = null
    )

    @Serializable
    data class PageData(
        val pageInfo: PageInfo? = null,
        val media: List<MediaData>? = null
    )

    @Serializable
    data class PageInfo(
        val total: Int = 0,
        val currentPage: Int = 0,
        val lastPage: Int = 0,
        val hasNextPage: Boolean = false
    )

    @Serializable
    data class MediaData(
        val id: Int = 0,
        val idMal: Int? = null,
        val title: Title? = null,
        val description: String? = null,
        val seasonYear: Int? = null,
        val episodes: Int? = null,
        val status: String? = null,
        val coverImage: CoverImage? = null,
        val bannerImage: String? = null,
        val genres: List<String>? = null,
        val averageScore: Int? = null,
        val format: String? = null,
        val studios: StudiosData? = null,
        val relations: RelationsData? = null
    )

    @Serializable
    data class Title(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    ) {
        fun getPreferred(): String = english?.takeIf { it.isNotBlank() }
            ?: romaji?.takeIf { it.isNotBlank() }
            ?: native?.takeIf { it.isNotBlank() }
            ?: "Unknown"
    }

    @Serializable
    data class CoverImage(
        val large: String? = null,
        val extraLarge: String? = null
    )

    @Serializable
    data class StudiosData(
        val nodes: List<StudioNode>? = null
    )

    @Serializable
    data class StudioNode(
        val name: String = ""
    )

    @Serializable
    data class RelationsData(
        val edges: List<RelationEdge>? = null
    )

    @Serializable
    data class RelationEdge(
        val relationType: String? = null,
        val node: RelationNode? = null
    )

    @Serializable
    data class RelationNode(
        val id: Int = 0,
        val title: Title? = null,
        val type: String? = null
    )
}
