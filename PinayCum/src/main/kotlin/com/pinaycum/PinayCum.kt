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

        // 1. Core Extension: Extract standard player server buttons
        document.select("a.btn-dark[href*='&s=']").forEach { playerBtn ->
            val playerUrl = fixUrl(playerBtn.attr("href"))

            try {
                val playerDoc = app.get(playerUrl, referer = data).document

                // Check for standard iframes (This handles many generic third-party hosts)
                playerDoc.select("iframe").forEach { iframe ->
                    val iframeSrc = fixUrlNull(iframe.attr("src"))
                    if (iframeSrc != null) {
                        // loadExtractor automatically recognizes Lulustream, Doodstream, Streamruby, etc.
                        val loaded = loadExtractor(iframeSrc, playerUrl, subtitleCallback, callback)
                        if (loaded) found = true
                    }
                }

                // Look for direct mp4 fallback
                playerDoc.select("source[src*='.mp4'], a[href*='.mp4']").forEach { el ->
                    val src = fixUrlNull(el.attr("src") ?: el.attr("href"))
                    if (src != null) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${playerBtn.text().trim()} Direct",
                                url = src,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Global Page Scan: Catch any loose iframes or raw host links printed on the page
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src"))
            if (src != null) {
                if (loadExtractor(src, data, subtitleCallback, callback)) found = true
            }
        }

        // 3. Vidaara Direct Fix Block (Kept from your original code)
        Regex("""https?://vidaarax\.net/e/[\w-]+""").find(document.toString())?.value?.let { embedUrl ->
            try {
                val embedDoc = app.get(embedUrl, referer = mainUrl).text
                val streamUrlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                val streamUrl = streamUrlRegex.find(embedDoc)?.groupValues?.get(1)

                if (streamUrl != null) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Vidara Direct (Fixed)",
                            url = streamUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    found = true
                } else {
                    if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) found = true
                }
            } catch (e: Exception) {
                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) found = true
            }
        }

        return found
    }