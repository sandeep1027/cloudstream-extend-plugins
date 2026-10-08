package com.ott.cs3

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

private const val TAG = "OttAggregator"

/**
 * Dispatches already-unlocked mirror URLs to the matching extractor.
 *
 * The unlock happens at load time (in [OttAggregatorProvider]), so by the time
 * this is called every URL is a direct page on the mirror host. One branch per
 * mirror, and that is the maintenance cost of doing this instead of using a
 * torrent index: the site names its mirrors, so a rename or a new mirror is a
 * code change. Anything unmatched is handed to the app's generic extractor
 * loader, so an unknown mirror still has a chance of working.
 */
suspend fun emitOttSources(
    urls: List<OttSource>,
    subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    Log.d(TAG, "emitOttSources: ${urls.size} URLs to process")
    for ((i, source) in urls.withIndex()) {
        val url = source.url
        Log.d(TAG, "emitOttSources[$i]: $url")
        when {
            url.contains("hubcloud", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to HubCloud")
                fromHubCloud(url, source.label, callback)
            }

            url.contains("gdflix", ignoreCase = true) ||
                url.contains("gdlink", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to GdFlix")
                fromGdFlix(url, source.label, callback)
            }

            url.contains("fastdlserver", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to FastDlServer")
                fromFastDlServer(url, source.label, callback)
            }

            url.contains("linksmod", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to LinksMod")
                fromLinksMod(url, source.label, subtitleCallback, callback)
            }

            url.contains("sidexfee", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to sidexfee bypass")
                val id = url.substringAfterLast("id=")
                val unlocked = unlockSidexfee(id)
                if (unlocked != null) {
                    emitOttSources(listOf(OttSource(unlocked, source.label, source.quality)), subtitleCallback, callback)
                }
            }

            url.contains("howblogs", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to HowBlogs bypass")
                fromHowBlogs(url, source.label, callback)
            }

            url.contains("tpead", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to Tpead video host")
                fromTpead(url, source.label, callback)
            }

            url.contains("skybap", ignoreCase = true) -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to SkyBap bypass")
                fromSkyBap(url, source.label, callback)
            }

            else -> {
                Log.d(TAG, "emitOttSources[$i]: dispatching to loadExtractor")
                loadExtractor(url, "", subtitleCallback, callback)
            }
        }
    }
}

/**
 * sidexfee redirect bypass.
 *
 * The site's mirror buttons carry `?id=<n>`, not the real URL. This function
 * GETs the sidexfee redirector, extracts the base64-encoded link from the JSON
 * response, and decodes it.
 *
 * Isolated in its own function so if the service goes away it is one function
 * to replace.
 */
suspend fun unlockSidexfee(id: String): String? {
    return try {
        val page = app.get("https://web.sidexfee.com/?id=$id").text
        val encoded = Regex("""link":"([^"]+)"""")
            .find(page)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return null
        base64Decode(encoded)
    } catch (t: Throwable) {
        Log.w(TAG, "unlockSidexfee failed for id=$id: ${t.message}")
        null
    }
}

/**
 * HubCloud bypass (simplified from hdhub4u).
 *
 * Fetches the HubCloud page, extracts download button links, and dispatches to
 * the appropriate server type (direct file, pixeldrain, etc.).
 */
private suspend fun fromHubCloud(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromHubCloud: fetching $url")
        val doc = app.get(url).document

        // Extract file info from the page
        val title = doc.selectFirst("h1, h2, .title")?.text().orEmpty()
        val quality = ottQuality(title)

        // Find download buttons
        val buttons = doc.select("a.btn, a.download, div.download a")
        Log.d(TAG, "fromHubCloud: found ${buttons.size} buttons")

        for (button in buttons) {
            val href = button.attr("href")
            val buttonText = button.text().lowercase()

            if (href.isBlank()) continue

            // Direct file links
            if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.contains("/download")) {
                Log.d(TAG, "fromHubCloud: direct link $href")
                callback(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "$label [HubCloud]",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = url
                    }
                )
            }
            // Pixeldrain links
            else if (href.contains("pixeldrain", ignoreCase = true)) {
                Log.d(TAG, "fromHubCloud: pixeldrain link $href")
                val base = OttDomains.baseOf(href)
                val file = if (href.contains("download")) href else {
                    "$base/api/file/${href.substringAfterLast("/")}?download"
                }
                callback(
                    newExtractorLink(
                        source = "HubCloud [Pixeldrain]",
                        name = "$label [Pixeldrain]",
                        url = file,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = base
                    }
                )
            }
            // Other server types (FSL, S3, etc.) - direct links
            else if (buttonText.contains("fsl") || buttonText.contains("download") ||
                     buttonText.contains("s3") || buttonText.contains("direct")) {
                Log.d(TAG, "fromHubCloud: server link $href")
                callback(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "$label [HubCloud ${buttonText.take(20)}]",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = url
                    }
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fromHubCloud failed: ${e.message}")
    }
}

/**
 * GdFlix bypass (ported from bollyflix).
 *
 * Scrapes the GdFlix index page for download buttons and extracts direct video
 * links from each button type (FSL, DIRECT, CLOUD, R2, GD Index, FAST,
 * Pixeldrain, Instant, GoFile).
 */
private suspend fun fromGdFlix(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromGdFlix: fetching $url")
        var baseUrl = OttDomains.baseOf(url)

        // Try to get latest base URL from dynamic map
        val latestBaseUrl = try {
            val dynamicUrls = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                .parsedSafe<Map<String, String>>()
            dynamicUrls?.get("gdflix")?.takeIf { it.isNotBlank() } ?: baseUrl
        } catch (e: Exception) {
            baseUrl
        }

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
            Log.d(TAG, "fromGdFlix rewritten URL: $newUrl")
        }

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ").orEmpty().trim()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ").orEmpty().trim()
        val quality = ottQuality(fileName)

        Log.d(TAG, "fromGdFlix: fileName=$fileName, fileSize=$fileSize")

        suspend fun offer(link: String?, server: String) {
            if (link.isNullOrBlank()) return
            callback(
                newExtractorLink(
                    source = "GdFlix$server",
                    name = "$label ${fileName.ifBlank { "download" }} [$fileSize]",
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = newUrl
                }
            )
        }
        for (anchor in document.select("div.text-center a")) {
            val text = anchor.text()
            val href = anchor.attr("href") ?: continue

            when {
                text.contains("FSL", ignoreCase = true) -> offer(href, " [FSL]")
                text.contains("DIRECT", ignoreCase = true) -> offer(href, " [Direct]")
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) ||
                    text.contains("R2", ignoreCase = true) -> offer(href, " [Cloud]")
                text.contains("GD Index", ignoreCase = true) -> {
                    for (type in listOf(1, 2)) {
                        try {
                            val indexDoc = app.get("$baseUrl$href?type=$type").document
                            indexDoc.select("a.btn-success").forEach {
                                offer(it.attr("href"), " [CF]")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "fromGdFlix GD Index failed: ${e.message}")
                        }
                    }
                }
                text.contains("FAST", ignoreCase = true) -> {
                    try {
                        val fastDoc = app.get(baseUrl + href).document
                        offer(fastDoc.select("div.card-body a").attr("href"), " [Fast]")
                    } catch (e: Exception) {
                        Log.w(TAG, "fromGdFlix FAST failed: ${e.message}")
                    }
                }
                href.contains("pixeldra", ignoreCase = true) -> {
                    val direct = if (href.contains("download", true)) href else {
                        val base = OttDomains.baseOf(href)
                        "$base/api/file/${href.substringAfterLast("/")}?download"
                    }
                    offer(direct, " [Pixeldrain]")
                }
                text.contains("Instant", ignoreCase = true) -> {
                    try {
                        val location = app.get(href, allowRedirects = false).headers["location"]
                        offer(location?.substringAfter("url="), " [Instant]")
                    } catch (e: Exception) {
                        Log.w(TAG, "fromGdFlix Instant failed: ${e.message}")
                    }
                }
                text.contains("GoFile", ignoreCase = true) -> {
                    try {
                        val gofileDoc = app.get(href).document
                        gofileDoc.select(".row .row a").forEach {
                            val gofile = it.attr("href")
                            if (gofile?.contains("gofile") == true) {
                                loadExtractor(gofile, "", { }, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "fromGdFlix GoFile failed: ${e.message}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fromGdFlix failed: ${e.message}")
    }
}

/**
 * FastDlServer redirector (ported from bollyflix).
 *
 * Follows the redirect from fastdlserver and dispatches to the appropriate
 * extractor based on the final destination.
 */
private suspend fun fromFastDlServer(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromFastDlServer: $url")
        val response = app.get(url, allowRedirects = false)
        val location = response.headers["location"]
        Log.d(TAG, "fromFastDlServer location: $location")

        if (location != null) {
            if (location.contains("gdflix", ignoreCase = true) || location.contains("gdlink", ignoreCase = true)) {
                Log.d(TAG, "fromFastDlServer: redirecting to GdFlix")
                fromGdFlix(location, label, callback)
            } else {
                loadExtractor(location, "", { }, callback)
            }
        } else {
            Log.w(TAG, "fromFastDlServer: no location header found")
        }
    } catch (e: Exception) {
        Log.e(TAG, "fromFastDlServer failed: ${e.message}")
    }
}

/**
 * LinksMod redirector (ported from bollyflix).
 *
 * Extracts the redirect URL from meta refresh or JavaScript, then dispatches to
 * the generic loader.
 */
private suspend fun fromLinksMod(
    url: String,
    label: String,
    subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromLinksMod: $url")
        val document = app.get(url).document

        // Look for redirect URL in meta refresh
        val metaRefresh = document.select("meta[http-equiv=refresh]").attr("content")
        if (metaRefresh.contains("url=")) {
            val redirectUrl = metaRefresh.substringAfter("url=").trim()
            Log.d(TAG, "fromLinksMod redirect: $redirectUrl")
            loadExtractor(redirectUrl, "", subtitleCallback, callback)
            return
        }

        // Try to find link in script tags
        val scripts = document.select("script").joinToString("\n")
        val urlPattern = Regex("""(?:window\.location|location\.href)\s*=\s*['"]([^'"]+)['"]""")
        val match = urlPattern.find(scripts)
        if (match != null) {
            val redirectUrl = match.groupValues[1]
            Log.d(TAG, "fromLinksMod JS redirect: $redirectUrl")
            loadExtractor(redirectUrl, "", subtitleCallback, callback)
            return
        }

        Log.w(TAG, "fromLinksMod: no redirect found")
    } catch (e: Exception) {
        Log.e(TAG, "fromLinksMod failed: ${e.message}")
    }
}

/**
 * HowBlogs redirector bypass.
 *
 * howblogs.xyz is a link shortener used by SkyMoviesHD that redirects to actual
 * file hosts (Google Drive, HubCloud, GdFlix, GoFile, StreamTape, etc.). This
 * function extracts all file host links and dispatches them appropriately.
 */
private suspend fun fromHowBlogs(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromHowBlogs: $url")
        val doc = app.get(url).document

        // Look for redirect links or direct download links
        val links = doc.select("a[href]")
        val foundLinks = mutableSetOf<String>()

        for (link in links) {
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http") && !foundLinks.contains(href)) {
                Log.d(TAG, "fromHowBlogs: found link $href")

                // Check if it's a known file host that needs special handling
                when {
                    href.contains("hubcloud", ignoreCase = true) -> {
                        foundLinks.add(href)
                        fromHubCloud(href, label, callback)
                    }
                    href.contains("gdflix", ignoreCase = true) || href.contains("gdlink", ignoreCase = true) -> {
                        foundLinks.add(href)
                        fromGdFlix(href, label, callback)
                    }
                    // Skip navigation links to the same site
                    href.contains("howblogs.xyz", ignoreCase = true) -> {
                        // Skip internal navigation
                    }
                    // For all other http links, use the generic extractor loader
                    else -> {
                        foundLinks.add(href)
                        Log.d(TAG, "fromHowBlogs: dispatching to loadExtractor: $href")
                        loadExtractor(href, url, { }, callback)
                    }
                }
            }
        }

        // Try to find link in meta refresh or JavaScript
        if (foundLinks.isEmpty()) {
            val metaRefresh = doc.select("meta[http-equiv=refresh]").attr("content")
            if (metaRefresh.contains("url=")) {
                val redirectUrl = metaRefresh.substringAfter("url=").trim()
                Log.d(TAG, "fromHowBlogs redirect: $redirectUrl")
                loadExtractor(redirectUrl, url, { }, callback)
                return
            }

            val scripts = doc.select("script").joinToString("\n")
            val urlPattern = Regex("""(?:window\.location|location\.href)\s*=\s*['"]([^'"]+)['"]""")
            val match = urlPattern.find(scripts)
            if (match != null) {
                val redirectUrl = match.groupValues[1]
                Log.d(TAG, "fromHowBlogs JS redirect: $redirectUrl")
                loadExtractor(redirectUrl, url, { }, callback)
                return
            }
        }

        if (foundLinks.isEmpty()) {
            Log.w(TAG, "fromHowBlogs: no redirect found")
        } else {
            Log.d(TAG, "fromHowBlogs: processed ${foundLinks.size} links")
        }
    } catch (e: Exception) {
        Log.e(TAG, "fromHowBlogs failed: ${e.message}")
    }
}

/**
 * Tpead video host extractor.
 *
 * tpead.net is a video hosting service used by SkyMoviesHD for "WATCH ONLINE"
 * links. The URL often contains the direct video file path. We need to visit
 * the page first to get session cookies, then use the video URL with those cookies.
 */
private suspend fun fromTpead(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromTpead: $url")

        // Check if URL already contains a direct video file path
        val isDirectVideo = url.matches(Regex(".*\\.(mp4|mkv|avi|webm)(\\?.*)?$", RegexOption.IGNORE_CASE))

        if (isDirectVideo) {
            Log.d(TAG, "fromTpead: URL is direct video file, fetching page for cookies")

            // Visit the page to get session cookies
            val baseUrl = OttDomains.baseOf(url)
            val pageUrl = url.substringBeforeLast("/")

            try {
                // Get the page to establish session
                val pageResponse = app.get(pageUrl)
                val cookies = pageResponse.headers.toMultimap()
                    .filter { it.key.equals("set-cookie", ignoreCase = true) }
                    .flatMap { it.value }
                    .joinToString("; ") { it.substringBefore(";") }

                Log.d(TAG, "fromTpead: got cookies: $cookies")

                // Create ExtractorLink with cookies as headers
                callback(
                    newExtractorLink(
                        source = "Tpead",
                        name = "$label [Tpead]",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = ottQuality(label)
                        this.referer = baseUrl
                        this.headers = mapOf(
                            "Cookie" to cookies,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "fromTpead: failed to get cookies, trying without: ${e.message}")
                // Fallback: use without cookies
                callback(
                    newExtractorLink(
                        source = "Tpead",
                        name = "$label [Tpead]",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = ottQuality(label)
                        this.referer = baseUrl
                    }
                )
            }
            return
        }

        val doc = app.get(url).document

        // Look for video source in the page
        val videoSources = doc.select("source[src], video[src], iframe[src]")
        for (source in videoSources) {
            val src = source.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                Log.d(TAG, "fromTpead: found video source $src")
                if (src.contains(".m3u8")) {
                    // HLS stream
                    callback(
                        newExtractorLink(
                            source = "Tpead",
                            name = "$label [Tpead]",
                            url = src,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = ottQuality(label)
                            this.referer = url
                        }
                    )
                } else {
                    loadExtractor(src, url, { }, callback)
                }
                return
            }
        }

        // Try to find video URL in scripts
        val scripts = doc.select("script").joinToString("\n")
        val videoPattern = Regex("""(?:file|source|url|src)\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4|mkv))['"]""")
        val match = videoPattern.find(scripts)
        if (match != null) {
            val videoUrl = match.groupValues[1]
            Log.d(TAG, "fromTpead: found video URL in script $videoUrl")
            if (videoUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = "Tpead",
                        name = "$label [Tpead]",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = ottQuality(label)
                        this.referer = url
                    }
                )
            } else {
                loadExtractor(videoUrl, url, { }, callback)
            }
            return
        }

        Log.w(TAG, "fromTpead: no video source found")
    } catch (e: Exception) {
        Log.e(TAG, "fromTpead failed: ${e.message}")
    }
}

/**
 * SkyBap redirector bypass.
 *
 * skybap.site is the main domain redirector for SkyMoviesHD. It may contain
 * links to actual file hosts or further redirects. Dispatch all found links
 * to loadExtractor.
 */
private suspend fun fromSkyBap(
    url: String,
    label: String,
    callback: (ExtractorLink) -> Unit
) {
    try {
        Log.d(TAG, "fromSkyBap: $url")
        val doc = app.get(url).document

        // Look for download links
        val links = doc.select("a[href]")
        val foundLinks = mutableSetOf<String>()

        for (link in links) {
            val href = link.attr("href")
            if (href.isNotBlank() && href.startsWith("http") && !foundLinks.contains(href)) {
                Log.d(TAG, "fromSkyBap: found link $href")

                // Skip navigation links to the same site
                if (href.contains("skymovieshd", ignoreCase = true) ||
                    href.contains("skybap", ignoreCase = true) ||
                    href.contains("supercounters", ignoreCase = true)) {
                    continue
                }

                foundLinks.add(href)

                // Dispatch based on domain
                when {
                    href.contains("hubcloud", ignoreCase = true) -> {
                        fromHubCloud(href, label, callback)
                    }
                    href.contains("gdflix", ignoreCase = true) || href.contains("gdlink", ignoreCase = true) -> {
                        fromGdFlix(href, label, callback)
                    }
                    href.contains("howblogs", ignoreCase = true) -> {
                        fromHowBlogs(href, label, callback)
                    }
                    else -> {
                        Log.d(TAG, "fromSkyBap: dispatching to loadExtractor: $href")
                        loadExtractor(href, url, { }, callback)
                    }
                }
            }
        }

        if (foundLinks.isEmpty()) {
            Log.w(TAG, "fromSkyBap: no download links found")
        } else {
            Log.d(TAG, "fromSkyBap: processed ${foundLinks.size} links")
        }
    } catch (e: Exception) {
        Log.e(TAG, "fromSkyBap failed: ${e.message}")
    }
}
