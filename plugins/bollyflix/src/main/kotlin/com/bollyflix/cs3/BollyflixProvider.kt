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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * BollyFlix — https://new.bollyflix.vote
 *
 * BollyFlix is a WordPress catalogue of *download* posts rather than a streaming
 * site: every post is a release card holding a quality table
 * (`2160p HEVC [12GB]`) plus download mirrors.
 *
 * Discovery reads the site's open `wp-json/wp/v2` REST API, which needs no key.
 * Playback resolves the post's own download mirrors, so **no torrent index and no
 * debrid account are involved** — every link is a real file served over HTTP.
 *
 * The mirror buttons live inside the post's `content.rendered` from wp-json, so
 * the plugin never has to fetch the bollyflix page itself — those pages sit
 * behind Cloudflare and this plugin cannot use CloudflareKiller (it lives in the
 * library's androidMain, which a `kotlin.jvm` plugin cannot compile against).
 * Pack pages for series episodes and the mirror index pages themselves are on
 * other hosts (gdflix, fastdlserver, ...) and are fetched directly.
 */
class BollyflixProvider : MainAPI() {

    override var name = "BollyFlix"
    override var mainUrl = BollyflixDomains.FALLBACK

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

    /** slug -> post id, filled while walking search results and home rows. */
    private val postIdBySlug = ConcurrentHashMap<String, Int>()

    init {
        // Read the domain map once at load so a rotated address is picked up. If
        // the map is unreachable the compiled-in fallback is used, which is the
        // same address this build was tested against.
        kotlinx.coroutines.runBlocking {
            mainUrl = BollyflixDomains.current()
        }
    }

    // --------------------------------------------------------------- home rows

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data.trim()
        if (slug.isEmpty()) return null
        val posts = fetchPosts("slug-based=1&search=${encodeQuery(slug)}", page) ?: return null
        val items = posts.mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items, items.size >= PER_PAGE)
    }

    // ---------------------------------------------------------------- search

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

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val post = fetchPost(url) ?: return null
        val content = post.content?.rendered.orEmpty()
        // Parse the mirror buttons out of the wp-json body so we never have to
        // hit the bollyflix page itself (Cloudflare would block it).
        val bodyDocument = org.jsoup.Jsoup.parse(content)

        val imdbId = IMDB_TITLE_PATTERN.find(content)?.groupValues?.get(1)
        val title = cleanTitle(post.title?.rendered).ifBlank { post.slug.orEmpty() }
        if (title.isBlank()) return null

        val year = yearFromContent(content)
            ?: YEAR_IN_PARENTHESES.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: post.date?.substringBefore('-')?.toIntOrNull()
        val poster = post.posterUrl()
        val plot = valueAfterLabel(content, "Summary:")
            ?: valueAfterLabel(content, "Storyline:")
            ?: post.excerpt?.rendered?.stripHtml()
        val tags = valueAfterLabel(content, "Genres:")?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }

        // Two signals that do not need the page itself: a "web-series" slug and
        // an explicit "Seasons:" label in the body. The page's #movie_title
        // marker would be a third, but reading the page is exactly what we are
        // avoiding here.
        val isSeries = url.contains("web-series", ignoreCase = true) ||
            valueAfterLabel(content, "Seasons:") != null

        if (!isSeries) {
            // Unlock every mirror at load time and pass the unlocked list as data.
            // loadLinks then just dispatches each URL to the matching extractor,
            // without re-fetching anything on the bollyflix host.
            val sources = unlockMovieMirrors(bodyDocument)
            return newMovieLoadResponse(title, url, TvType.Movie, sources) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addImdbId(imdbId)
            }
        }

        val episodes = buildSeriesEpisodes(bodyDocument, title, poster, imdbId)
        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, unlockMovieMirrors(bodyDocument)) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addImdbId(imdbId)
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addImdbId(imdbId)
        }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Both movies and series episodes carry their sources as a JSON-encoded
        // List<BollyflixSource>. For movies the list has one entry per mirror;
        // for episodes it is a single-element list holding the file URL.
        val sources = runCatching { parseJson<List<BollyflixSource>>(data) }.getOrNull()
            ?: return false
        if (sources.isEmpty()) return false
        emitBollyflixSources(sources.map { it.url }, subtitleCallback, callback)
        return true
    }

    // ----------------------------------------------------------- wordpress api

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun api(path: String): String? =
        runCatching { app.get("$mainUrl/wp-json/wp/v2/$path").text }.getOrNull()

    private suspend fun fetchPosts(query: String, page: Int): List<WpPost>? {
        val body = api("posts?per_page=$PER_PAGE&page=$page&$query") ?: return null
        val posts = runCatching { parseJson<List<WpPost>>(body) }.getOrNull() ?: return null
        for (post in posts) {
            post.slug?.let { postIdBySlug[it] = post.id }
        }
        return posts
    }

    /**
     * The post behind a result url. The REST API is tried first because it is
     * cheap and reliable; the page is the fallback for a url that never came
     * from this plugin's own search.
     */
    private suspend fun fetchPost(url: String): WpPost? {
        val slug = url.substringAfterLast('/').substringBefore('?')
        if (slug.isEmpty()) return null
        val id = postIdBySlug[slug]

        val body = if (id != null) {
            api("posts/$id")
        } else {
            api("posts?slug=${encodeQuery(slug)}")
        }
        if (body != null) {
            runCatching { parseJson<List<WpPost>>(body) }.getOrNull()
                ?.firstOrNull()
                ?.also { postIdBySlug[slug] = it.id }
                ?.let { return it }
        }

        // Fall back to scraping the page itself.
        val document = runCatching {
            app.get(url).document
        }.getOrNull() ?: return null
        return WpPost(
            slug = slug,
            link = url,
            title = WpRendered(document.selectFirst("h1")?.text()),
            content = WpRendered(document.selectFirst("article, .entry-content, body")?.html()),
        )
    }

    // ---------------------------------------------------------------- mapping

    private fun WpPost.toSearchResult(): SearchResponse? {
        val href = link?.takeIf { it.startsWith("http") } ?: return null
        val rawTitle = title?.rendered?.replace("Download ", "").orEmpty()
        val name = cleanTitle(rawTitle)
        if (name.isBlank()) return null
        return newMovieSearchResponse(name, href, TvType.Movie) {
            posterUrl = posterUrl()
            year = yearFromContent(content?.rendered.orEmpty())
                ?: YEAR_IN_PARENTHESES.find(name)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * The post's artwork, from the og:image Yoast puts in the head block.
     *
     * Not from content.rendered: that is the post body and carries no meta tags,
     * so looking there finds nothing and every row renders as a placeholder.
     */
    private fun WpPost.posterUrl(): String? =
        yoast_head?.let { head ->
            Regex("""<meta property="og:image" content="([^"]+)"""")
                .find(head)?.groupValues?.get(1)
        }?.takeIf { it.startsWith("http") }
            ?: content?.rendered?.let { html ->
                Regex("""<meta property="og:image" content="([^"]+)"""")
                    .find(html)?.groupValues?.get(1)
            }

    // --------------------------------------------------------- mirror unlock

    /**
     * A movie's mirror buttons, unlocked and ready to dispatch. The unlock is
     * done here at load time so that loadLinks only has to hand each URL to the
     * matching extractor — no further bollyflix fetches, no re-unlocking.
     */
    private suspend fun unlockMovieMirrors(bodyDocument: org.jsoup.nodes.Document): List<BollyflixSource> =
        supervisorScope {
            bodyDocument.select("a.maxbutton-download-links, a.dl, a.btnn")
                .map { it.attr("href").orEmpty() }
                .filter { it.startsWith("http") }
                .map { href ->
                    async {
                        val unlocked = if (!href.contains("fastdlserver", true) && href.contains("?id=")) {
                            unlock(href.substringAfterLast("id=")) ?: return@async null
                        } else href
                        BollyflixSource(unlocked)
                    }
                }.awaitAll().filterNotNull()
        }

    /**
     * Series posts link a pack per season, so the episode list is read from the
     * mirror pages: each one lists its files, and their order in the page is the
     * episode order. The pack pages are on the mirror hosts themselves
     * (gdflix, fastdlserver, ...) and do not sit behind bollyflix's Cloudflare.
     */
    private suspend fun buildSeriesEpisodes(
        bodyDocument: org.jsoup.nodes.Document,
        showName: String,
        poster: String?,
        imdbId: String?,
    ): List<Episode> = supervisorScope {
        val episodes = mutableListOf<Episode>()

        val packLinks = bodyDocument.select("a.maxbutton-download-links, a.dl, a.btnn")
            .map { it.attr("href").orEmpty() }
            .filter { it.startsWith("http") }

        // Find the season label closest to each pack button in the body, so a
        // post with multiple seasons produces the right S01/S02 split.
        val buttons = bodyDocument.select("a.maxbutton-download-links, a.dl, a.btnn")
        buttons.forEachIndexed { index, button ->
            val href = packLinks.getOrNull(index) ?: return@forEachIndexed
            var packUrl = href
            if (!packUrl.contains("fastdlserver", true) && packUrl.contains("?id=")) {
                packUrl = unlock(packUrl.substringAfterLast("id=")) ?: return@forEachIndexed
            }

            // The season marker usually lives in the heading immediately before
            // the button; walk backwards through a few previous siblings until
            // one mentions "Season", else default to season 1.
            val seasonText = buildList {
                var sibling: org.jsoup.nodes.Element? = button.previousElementSibling()
                repeat(4) {
                    val el = sibling ?: return@repeat
                    add(el.text())
                    sibling = el.previousElementSibling()
                }
            }.joinToString(" ")
            val season = Regex("""(?:Season |S)(\d{1,2})""").find(seasonText)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            val packDocument = runCatching { app.get(packUrl).document }.getOrNull()
                ?: return@forEachIndexed
            val files = packDocument.select("h3 > a")
                .filterNot { it.text().contains("zip", ignoreCase = true) }

            files.forEachIndexed { epIndex, anchor ->
                val episode = epIndex + 1
                val fileUrl = anchor.attr("href").orEmpty()
                if (!fileUrl.startsWith("http")) return@forEachIndexed
                // One source per episode, wrapped in a list so loadLinks can use
                // the same parser for movies and series alike.
                val data = listOf(BollyflixSource(fileUrl))
                episodes += newEpisode(data) {
                    this.name = "Episode $episode"
                    this.season = season
                    this.episode = episode
                    posterUrl = poster
                }
            }
        }
        episodes
    }

    // ---------------------------------------------------------------- parsing

    /** "Download Pooja Meri Jaan (2026) Hindi Movie 480p | 720p" -> a readable name. */
    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace("Download ", "")
            .replace(Regex("""\d{3,4}[pP]\s*\|\s*\d{3,4}[pP].*"""), "")
            .replace(Regex("""\b\d{3,4}[pP]\b"""), "")
            .replace("Hindi Movie", "")
            .replace("WEB-DL", "")
            .replace("ESub", "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '|', '~')
            .trim()
    }

    private fun String.stripHtml(): String =
        Regex("""<[^>]+>""").replace(this, "").replace("&amp;", "&")
            .replace("&nbsp;", " ").replace(Regex("""\s{2,}"""), " ").trim()

    private fun yearFromContent(content: String): Int? =
        Regex("""Released Year:</strong>\s*(\d{4})""").find(content)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun valueAfterLabel(content: String, label: String): String? =
        Regex("""$label\s*(?:</b>|</strong>)?\s*([^<]+)""")
            .find(content)?.groupValues?.getOrNull(1)?.stripHtml()?.takeIf { it.isNotBlank() }

    private companion object {
        const val PER_PAGE = 20

        /** "imdb.com/title/tt1234567" -> "tt1234567" */
        val IMDB_TITLE_PATTERN = Regex("""imdb\.com/title/(tt\d+)""")
        val YEAR_IN_PARENTHESES = Regex("""\((\d{4})\)""")
    }
}
