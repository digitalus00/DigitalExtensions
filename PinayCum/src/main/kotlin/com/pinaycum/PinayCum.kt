package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        // Load the page source directly using proper page verification rules
        val res = try { app.get(data, headers = defHeaders, referer = mainUrl) } catch(e: Exception) { null }
        val pageHtml = res?.text ?: ""
        val document = res?.document

        // Global sweep matches any link structural instances directly embedded inside text blocks
        val rawMatches = Regex("""https?://[^\s"'><]+""").findAll(pageHtml).map { it.value }.toList()
        
        // Target host collection pipeline
        for (rawUrl in rawMatches) {
            val cleanUrl = fixUrlNull(rawUrl) ?: continue
            if (!processedUrls.add(cleanUrl)) continue

            try {
                if (cleanUrl.contains("dood") || cleanUrl.contains("ds2play")) {
                    val embedUrl = cleanUrl.replace("/d/", "/e/").replace("/f/", "/e/")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                } else if (cleanUrl.contains("lulu") || cleanUrl.contains("lulustream")) {
                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) found = true
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
                        found = true
                    }
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
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        // Secondary Pass: Extract explicitly from any visual player components on screen
        if (document != null) {
            val playerButtons = document.select("a[href*='&s='], iframe[src]")
            for (element in playerButtons) {
                val targetUrl = fixUrlNull(element.attr("href").takeIf { it.isNotEmpty() } ?: element.attr("src")) ?: continue
                if (!processedUrls.add(targetUrl)) continue

                try {
                    if (targetUrl.contains("dood") || targetUrl.contains("ds2play") || targetUrl.contains("lulu")) {
                        val embedUrl = targetUrl.replace("/d/", "/e/").replace("/f/", "/e/")
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                    } else {
                        // Fallback processing for dynamic configurations
                        val subResponse = app.get(targetUrl, headers = defHeaders, referer = data).text
                        Regex("""https?://[^\s"'><]+""").findAll(subResponse).map { it.value }.forEach { subUrl ->
                            if ((subUrl.contains("dood") || subUrl.contains("ds2play") || subUrl.contains("lulu")) && processedUrls.add(subUrl)) {
                                val cleanSubUrl = subUrl.replace("/d/", "/e/").replace("/f/", "/e/")
                                loadExtractor(cleanSubUrl, targetUrl, subtitleCallback, callback)
                                found = true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }
}