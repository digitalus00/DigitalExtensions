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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?page=$page"
        val document = app.get(url, referer = mainUrl).document
        val items = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/?s=$query&page=$page"
        val document = app.get(url, referer = mainUrl).document
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
        val document = app.get(url, referer = mainUrl).document
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
        val document = app.get(data, referer = mainUrl).document
        var found = false

        // 1. Force Scan the Main Document for direct provider embed keys before they load
        val pageHtml = document.toString()
        
        // Scan for loose StreamRuby links
        Regex("""https?://(?:streamruby|rubystream|rubyembed)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value
            try {
                val rubyResponse = app.get(cleanUrl, referer = data).text
                val streamUrlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                val directStreamUrl = streamUrlRegex.find(rubyResponse)?.groupValues?.get(1)

                if (directStreamUrl != null) {
                    val isM3u8 = directStreamUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "StreamRuby",
                            name = "StreamRuby Mirror",
                            url = directStreamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    found = true
                }
            } catch (_: Exception) {}
        }

        // Scan for loose Doodstream links
        Regex("""https?://(?:doodstream\.com|dood\.[^\s"'><]+|ds2play\.[^\s"'><]+)/[efd]/[a-zA-Z0-9]+""").findAll(pageHtml).forEach { match ->
            val embedUrl = match.value.replace("/d/", "/e/").replace("/f/", "/e/")
            callback(
                newExtractorLink(
                    source = "DoodStream",
                    name = "DoodStream Mirror",
                    url = embedUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
            found = true
        }

        // Scan for loose LuluStream links
        Regex("""https?://(?:lulustream|lulu)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            callback(
                newExtractorLink(
                    source = "LuluStream",
                    name = "LuluStream Mirror",
                    url = match.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            found = true
        }

        // 2. Fallback: Fall back to parsing structural server action buttons explicitly
        val playerButtons = document.select("a.btn-dark[href*='&s=']")
        for (playerBtn in playerButtons) {
            val playerUrl = fixUrl(playerBtn.attr("href"))
            val btnName = playerBtn.text().trim()

            try {
                // If the link directly states it's our provider, skip iframe parsing entirely
                if (playerUrl.contains("ruby") || playerUrl.contains("dood") || playerUrl.contains("lulu")) {
                    if (playerUrl.contains("dood")) {
                        callback(newExtractorLink("DoodStream", "$btnName (DoodStream)", playerUrl.replace("/d/", "/e/"), ExtractorLinkType.VIDEO))
                        found = true
                        continue
                    }
                    if (playerUrl.contains("lulu")) {
                        callback(newExtractorLink("LuluStream", "$btnName (LuluStream)", playerUrl, ExtractorLinkType.VIDEO))
                        found = true
                        continue
                    }
                }

                val playerDoc = app.get(playerUrl, referer = data).document
                val collectedUrls = mutableListOf<String>()
                playerDoc.select("iframe").forEach { el -> el.attr("src").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }
                playerDoc.select("a").forEach { el -> el.attr("href").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }

                for (rawUrl in collectedUrls.distinct()) {
                    val cleanUrl = fixUrlNull(rawUrl) ?: continue

                    if (cleanUrl.contains("ruby") || cleanUrl.contains("streamruby")) {
                        val rubyResponse = app.get(cleanUrl, referer = playerUrl).text
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

        // 3. Vidara Direct Fix Block
        Regex("""https?://vidaarax\.net/e/[\w-]+""").find(pageHtml)?.value?.let { embedUrl ->
            try {
                val embedDoc = app.get(embedUrl, referer = mainUrl).text
                val streamUrl = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedDoc)?.groupValues?.get(1)

                if (streamUrl != null) {
                    callback(newExtractorLink(name, "Vidara Direct", streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                    found = true
                }
            } catch (_: Exception) {}
        }

        return found
    }
}