package com.bollyflix.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.awaitAll

private const val TAG = "Bollyflix"

/**
 * Unlocks a post's download id.
 *
 * BollyFlix does not put the real file url in the page: each mirror button
 * carries `?id=<n>` and the file is served from a separate host. That host is a
 * third-party service neither this plugin nor its users control, so a change or
 * shutdown there breaks every download link at once.
 *
 * It is isolated in [unlock] rather than inlined, so if that service goes away it
 * is one function to replace.
 */
suspend fun unlock(id: String): String? {
    val page = try {
        app.get("https://web.sidexfee.com/?id=$id").text
    } catch (t: Throwable) {
        Log.w(TAG, "unlock failed for id=$id: ${t.message}")
        return null
    }
    val encoded = Regex("""link":"([^"]+)"""")
        .find(page)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return null
    return try {
        base64Decode(encoded)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Dispatches already-unlocked mirror URLs to the matching extractor.
 *
 * The unlock happens at load time (in [com.bollyflix.cs3.BollyflixProvider]),
 * so by the time this is called every URL is a direct page on the mirror host.
 * One branch per mirror, and that is the maintenance cost of doing this instead
 * of using a torrent index: the site names its mirrors, so a rename or a new
 * mirror is a code change. Anything unmatched is handed to the app's generic
 * extractor loader, so an unknown mirror still has a chance of working.
 */
suspend fun emitBollyflixSources(
    urls: List<String>,
    subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    // Sequential: each branch can make several requests, and firing every mirror
    // at once hits rate limits on the mirror hosts. Order also matters - the
    // player picks the first usable link, so the site's own button order is
    // preserved.
    for (url in urls) {
        when {
            url.contains("gdflix", ignoreCase = true) ||
                url.contains("gdlink", ignoreCase = true) ->
                GdFlix().getUrl(url, "", subtitleCallback, callback)

            url.contains("fastdlserver", ignoreCase = true) ->
                FastDlServer().getUrl(url, "", subtitleCallback, callback)

            else -> loadExtractor(url, "", subtitleCallback, callback)
        }
    }
}

/**
 * A thin wrapper around the fastdlserver hop: the post links to a redirector on
 * fastdlserver, which answers with a Location to the real host. That host is
 * then handed to the generic loader so it can match whatever extractor knows it.
 */
private class FastDlServer : ExtractorApi() {
    override val name = "FastDl"
    override var mainUrl = "https://fastdlserver"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"] ?: return
        loadExtractor(location, referer, subtitleCallback, callback)
    }
}

/**
 * The Google Drive mirror family. The index pages are scraped for their
 * download buttons; the wildcard subclasses below cover the known hostnames
 * without duplicating the extraction.
 */
private open class GdFlix : ExtractorApi() {
    override val name = "GDFlix"
    override var mainUrl = "https://gdflix"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = BollyflixDomains.baseOf(url)
        val document = app.get(url).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ").orEmpty().trim()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ").orEmpty().trim()
        val quality = bollyflixQuality(fileName)

        suspend fun offer(link: String?, server: String) {
            if (link.isNullOrBlank()) return
            callback(
                newExtractorLink(
                    source = "$name$server",
                    name = "$name$server ${fileName.ifBlank { "download" }} [$fileSize]",
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        // One entry per button on the index page. Labels are matched loosely so a
        // cosmetic rename ("DIRECT DL" -> "DIRECT DOWNLOAD") still lands.
        for (anchor in document.select("div.text-center a")) {
            val text = anchor.text()
            val href = anchor.attr("href") ?: continue

            when {
                text.contains("FSL", ignoreCase = true) -> offer(href, " [FSL]")

                text.contains("DIRECT", ignoreCase = true) ->
                    offer(href, " [Direct]")

                text.contains("CLOUD DOWNLOAD", ignoreCase = true) ||
                    text.contains("R2", ignoreCase = true) -> offer(href, " [Cloud]")

                text.contains("GD Index", ignoreCase = true) ->
                    for (type in listOf(1, 2)) {
                        app.get("$host$href?type=$type").document
                            .select("a.btn-success").amap {
                                offer(it.attr("href"), " [CF]")
                            }
                    }

                text.contains("FAST", ignoreCase = true) ->
                    offer(
                        app.get(host + href).document.select("div.card-body a").attr("href"),
                        " [Fast]"
                    )

                href.contains("pixeldra", ignoreCase = true) -> {
                    val direct = if (href.contains("download", true)) href else {
                        val base = BollyflixDomains.baseOf(href)
                        "$base/api/file/${href.substringAfterLast("/")}?download"
                    }
                    offer(direct, " [Pixeldrain]")
                }

                text.contains("Instant", ignoreCase = true) ->
                    offer(
                        app.get(href, allowRedirects = false).headers["location"]
                            ?.substringAfter("url="),
                        " [Instant]"
                    )

                text.contains("GoFile", ignoreCase = true) ->
                    app.get(href).document.select(".row .row a").amap {
                        val gofile = it.attr("href")
                        if (gofile?.contains("gofile") == true) {
                            loadExtractor(gofile, referer, subtitleCallback, callback)
                        }
                    }
            }
        }
    }
}

private class GdFlixLive : GdFlix() {
    override var mainUrl = "https://new4.gdflix.io"
}

private class GdFlixMirror : GdFlix() {
    override var mainUrl = "https://gdlink"
}

/** The known GdFlix hostnames, so one case covers a rotated mirror. */
internal fun knownGdFlix(url: String): Boolean =
    url.contains("gdflix", true) || url.contains("gdlink", true)
