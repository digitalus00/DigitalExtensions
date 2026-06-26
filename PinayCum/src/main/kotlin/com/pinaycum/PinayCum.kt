package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycumvid.xyz"
    override var name = "PinayCum"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "tl"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val defHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?page=$page"
        val document = app.get(url, headers = defHeaders, referer = mainUrl).document
        val items = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/?s=$query&page=$page"
        val document = app.get(url, headers = defHeaders, referer = mainUrl).document
        val results = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h6.vid-title strong, .vid-title, strong")?.text()?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null

        var poster = selectFirst("img")?.attr("src")
            ?: selectFirst("div[style*='background']")?.attr("style")?.let {
                Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
            }

        if (poster != null) {
            poster = fixUrl(poster)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defHeaders, referer = mainUrl).document
        val title = document.selectFirst("h4, h1, title")?.text()?.trim() ?: "Pinay Video"

        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div#preroll-overlay")?.attr("style")?.let {
                Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
            }
            ?: document.selectFirst("img")?.attr("src")

        if (poster != null) {
            poster = fixUrl(poster)
        }

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        val recommendations = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val processedUrls = mutableSetOf<String>()

        val res = try { app.get(data, headers = defHeaders, referer = mainUrl) } catch(e: Exception) { null }
        val pageHtml = res?.text ?: ""
        val document = res?.document

        // High performance link unpacking pipeline
        suspend fun parseAndAddUrl(rawUrl: String): Boolean {
            val cleanUrl = fixUrlNull(rawUrl) ?: return false
            if (!processedUrls.add(cleanUrl)) return false

            return try {
                if (cleanUrl.contains("dood") || cleanUrl.contains("ds2play") || cleanUrl.contains("lulu") || cleanUrl.contains("lulustream")) {
                    val embedUrl = cleanUrl.replace("/d/", "/e/").replace("/f/", "/e/")
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                } else if (cleanUrl.contains("ruby") || cleanUrl.contains("streamruby") || cleanUrl.contains("struby")) {
                    val rubyResponse = app.get(cleanUrl, headers = defHeaders, referer = data).text
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(rubyResponse)?.groupValues?.get(1)?.let { directStreamUrl ->
                        val isM3u8 = directStreamUrl.contains(".m3u8")
                        callback(
                            ExtractorLink(
                                source = "StreamRuby",
                                name = "StreamRuby Mirror",
                                url = directStreamUrl,
                                referer = cleanUrl,
                                quality = Qualities.Unknown.value,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                headers = mapOf("User-Agent" to defHeaders["User-Agent"]!!, "Referer" to cleanUrl)
                            )
                        )
                        true
                    } ?: false
                } else if (cleanUrl.contains("vidaara") || cleanUrl.contains("vidara")) {
                    val embedDoc = app.get(cleanUrl, headers = defHeaders, referer = data).text
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedDoc)?.groupValues?.get(1)?.let { streamUrl ->
                        val isM3u8 = streamUrl.contains(".m3u8")
                        callback(
                            ExtractorLink(
                                source = "Vidara",
                                name = "Vidara Direct",
                                url = streamUrl,
                                referer = cleanUrl,
                                quality = Qualities.Unknown.value,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                headers = mapOf("User-Agent" to defHeaders["User-Agent"]!!, "Referer" to cleanUrl, "Origin" to "https://vidaarax.net")
                            )
                        )
                        true
                    } ?: false
                } else false
            } catch (_: Exception) { false }
        }

        // Step 1: Broad Raw Plaintext Stream Discovery
        Regex("""https?://[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            if (parseAndAddUrl(match.value)) found = true
        }

        // Step 2: Base64 Obfuscation Layer Breakdown Checks
        Regex("""[a-zA-Z0-9+/]{40,}={0,2}""").findAll(pageHtml).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.value, Base64.DEFAULT))
                if (decoded.contains("http") && (decoded.contains("dood") || decoded.contains("lulu") || decoded.contains("ruby") || decoded.contains("vid"))) {
                    Regex("""https?://[^\s"'><]+""").findAll(decoded).forEach { subMatch ->
                        if (parseAndAddUrl(subMatch.value)) found = true
                    }
                }
            } catch (_: Exception) {}
        }

        // Step 3: Global Exhaustive Structural Attribute Verification Scan
        document?.allElements?.forEach { element ->
            listOf("href", "src", "data-src", "data-link", "data-video", "value").forEach { attr ->
                val value = element.attr(attr).trim()
                if (value.isNotEmpty()) {
                    if (value.startsWith("http")) {
                        if (parseAndAddUrl(value)) found = true
                    } else if (value.contains("&s=") || value.contains("watch.php")) {
                        try {
                            val buttonUrl = fixUrl(value)
                            val subPage = app.get(buttonUrl, headers = defHeaders, referer = data).text
                            Regex("""https?://[^\s"'><]+""").findAll(subPage).forEach { subMatch ->
                                if (parseAndAddUrl(subMatch.value)) found = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        return found
    }
}