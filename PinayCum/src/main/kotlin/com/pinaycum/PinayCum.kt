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

        // 1. Text Sweep StreamRuby Extraction
        Regex("""https?://(?:streamruby|rubystream|rubyembed|rubystr|struby|streamr)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value
            if (processedUrls.add(cleanUrl)) {
                try {
                    val rubyResponse = app.get(cleanUrl, headers = defHeaders, referer = data).text
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(rubyResponse)?.groupValues?.get(1)?.let { directStreamUrl ->
                        callback(newExtractorLink("StreamRuby", "StreamRuby Mirror", directStreamUrl, if (directStreamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Direct Fallback Sweep for Primary Hosts (Dood / Lulu)
        Regex("""https?://(?:doodstream\.com|dood\.[^\s"'><]+|ds2play\.[^\s"'><]+)/[efd]/[a-zA-Z0-9]+""").findAll(pageHtml).forEach { match ->
            val embedUrl = match.value.replace("/d/", "/e/").replace("/f/", "/e/")
            if (processedUrls.add(embedUrl)) {
                callback(newExtractorLink("DoodStream", "DoodStream Mirror", embedUrl, ExtractorLinkType.VIDEO))
                found = true
            }
        }

        Regex("""https?://(?:lulustream|lulu)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            val luluUrl = match.value
            if (processedUrls.add(luluUrl)) {
                callback(newExtractorLink("LuluStream", "LuluStream Mirror", luluUrl, ExtractorLinkType.VIDEO))
                found = true
            }
        }

        // 3. Vidara Extraction Sweep
        Regex("""https?://(?:vidaara|vidaarax)[\w-]*\.[a-z]+/e/[\w-]+""").findAll(pageHtml).forEach { match ->
            val embedUrl = match.value
            if (processedUrls.add(embedUrl)) {
                try {
                    val embedDoc = app.get(embedUrl, headers = defHeaders, referer = mainUrl).text
                    Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedDoc)?.groupValues?.get(1)?.let { streamUrl ->
                        callback(newExtractorLink(name, "Vidara Direct", streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Structural Verification of External Source Redirect Targets
        if (document != null) {
            val playerButtons = document.select("a.btn-dark[href*='&s='], a.btn-primary[href*='&s=']")
            for (playerBtn in playerButtons) {
                val playerUrl = fixUrl(playerBtn.attr("href"))
                val btnName = playerBtn.text().trim()

                if ((playerUrl.contains("dood") || playerUrl.contains("ds2play")) && processedUrls.add(playerUrl.replace("/d/", "/e/"))) {
                    callback(newExtractorLink("DoodStream", "$btnName (DoodStream)", playerUrl.replace("/d/", "/e/"), ExtractorLinkType.VIDEO))
                    found = true
                    continue
                }
                if (playerUrl.contains("lulu") && processedUrls.add(playerUrl)) {
                    callback(newExtractorLink("LuluStream", "$btnName (LuluStream)", playerUrl, ExtractorLinkType.VIDEO))
                    found = true
                    continue
                }

                try {
                    val playerDoc = app.get(playerUrl, headers = defHeaders, referer = data).document
                    val collectedUrls = mutableListOf<String>()
                    playerDoc.select("iframe").forEach { el -> el.attr("src").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }
                    playerDoc.select("a").forEach { el -> el.attr("href").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }

                    for (rawUrl in collectedUrls.distinct()) {
                        val cleanUrl = fixUrlNull(rawUrl) ?: continue
                        if (!processedUrls.add(cleanUrl)) continue

                        if (cleanUrl.contains("ruby") || cleanUrl.contains("streamruby") || cleanUrl.contains("struby")) {
                            val rubyResponse = app.get(cleanUrl, headers = defHeaders, referer = playerUrl).text
                            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(rubyResponse)?.groupValues?.get(1)?.let { directUrl ->
                                callback(newExtractorLink("StreamRuby", "$btnName (StreamRuby)", directUrl, if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                                found = true
                            }
                        } else if (cleanUrl.contains("dood") || cleanUrl.contains("ds2play")) {
                            callback(newExtractorLink("DoodStream", "$btnName (DoodStream)", cleanUrl.replace("/d/", "/e/"), ExtractorLinkType.VIDEO))
                            found = true
                        } else if (cleanUrl.contains("lulu")) {
                            callback(newExtractorLink("LuluStream", "$btnName (LuluStream)", cleanUrl, ExtractorLinkType.VIDEO))
                            found = true
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }
}