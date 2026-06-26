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

        // Better poster extraction for homepage
        var poster = selectFirst("img")?.attr("src")
            ?: selectFirst("div[style*='background']")?.attr("style")?.let {
                Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
            }

        if (poster?.startsWith("00IMG/") == true) {
            poster = fixUrl(poster, mainUrl)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h4, h1, title")?.text()?.trim() ?: "Pinay Video"

        // Better poster extraction on watch page
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div#preroll-overlay")?.attr("style")?.let {
                Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
            }
            ?: document.selectFirst("img")?.attr("src")

        if (poster?.startsWith("00IMG/") == true) {
            poster = fixUrl(poster, mainUrl)
        }

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        val recommendations = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
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

        // Extract all player buttons
        document.select("a.btn-dark[href*='&s=']").forEach { playerBtn ->
            val playerUrl = fixUrl(playerBtn.attr("href"))
            val playerText = playerBtn.text().trim()

            try {
                val playerDoc = app.get(playerUrl, referer = data).document

                // Look for iframe
                val iframeSrc = playerDoc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = playerText.ifEmpty { "Player" },
                            url = fixUrl(iframeSrc),
                            referer = playerUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    found = true
                }

                // Look for direct mp4
                playerDoc.select("source[src*='.mp4'], a[href*='.mp4']").forEach { srcEl ->
                    val src = fixUrlNull(srcEl.attr("src") ?: srcEl.attr("href"))
                    if (src != null) {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$playerText Direct",
                                url = src,
                                referer = playerUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
            } catch (e: Exception) {
                // Ignore failed player
            }
        }

        // Direct Vidaara / Vidara extraction (most reliable)
        Regex("""https?://vidaarax\.net/e/[\w-]+""").find(document.toString())?.value?.let { embedUrl ->
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

        return found
    }
}