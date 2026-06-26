package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PinayCum : MainAPI() {
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
        val titleElement = selectFirst("h6.vid-title, .vid-title, strong, h2, a")
        val title = titleElement?.text()?.trim() ?: return null

        val href = fixUrlNull(attr("href")) ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.attr("src")
                ?: selectFirst("img")?.attr("data-src")
                ?: selectFirst("div[style*='background']")?.attr("style")?.let { style ->
                    Regex("url\\([\"']?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
                }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h1, h2, h4, title")?.text()?.trim() ?: "Pinay Video"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img")?.attr("src")
        )

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
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        // === Player Buttons (vidara, lulustream, etc.) ===
        document.select("a[href*='?id='][href*='&s=']").forEach { playerLink ->
            val playerUrl = fixUrl(playerLink.attr("href"))
            val playerName = playerLink.text().trim()

            when {
                playerName.contains("vidara", ignoreCase = true) || playerUrl.contains("vidara") -> {
                    // Vidara / Vidaara often gives direct or embed
                    val playerDoc = app.get(playerUrl, referer = data).document
                    playerDoc.select("iframe[src], a[href*='vidaarax.net'], a[href*='.mp4']").forEach { el ->
                        val src = fixUrlNull(el.attr("src") ?: el.attr("href"))
                        if (src != null && (src.contains(".mp4") || src.contains("vidaarax.net"))) {
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = "Vidara Player",
                                    url = src,
                                    referer = playerUrl,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                            found = true
                        }
                    }
                }
                else -> {
                    // Other players (lulustream, doodstream, streamruby)
                    val playerDoc = app.get(playerUrl, referer = data).document
                    val iframeSrc = playerDoc.selectFirst("iframe")?.attr("src")
                    if (iframeSrc != null) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = playerName,
                                url = fixUrl(iframeSrc),
                                referer = playerUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
            }
        }

        // === Fallback: Direct JS iframe (vidaarax.net) ===
        val jsIframeMatch = document.toString().contains("vidaarax.net")
        if (jsIframeMatch) {
            // Extract the embed ID from the JS or static HTML
            val embedUrl = Regex("""https?://vidaarax\.net/e/[\w-]+""").find(document.toString())?.value
            if (embedUrl != null) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "Vidara Direct",
                        url = embedUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        return found
    }
}