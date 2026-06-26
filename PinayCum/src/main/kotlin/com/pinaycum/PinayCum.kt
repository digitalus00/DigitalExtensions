package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycumvid.xyz"
    override var name = "PinayCum"           // ← This must be set
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
        val title = selectFirst("h6.vid-title strong, .vid-title, h4, strong")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(attr("href")) ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.attr("src")
                ?: selectFirst("div[style*='background:url']")?.attr("style")?.let {
                    Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
                }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h4, h1, title")?.text()?.trim() ?: "Pinay Video"

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

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

        // Player buttons
        document.select("a.btn-dark[href*='&s=']").forEach { el ->
            val playerUrl = fixUrl(el.attr("href"))
            val playerName = el.text().trim()

            val playerDoc = app.get(playerUrl, referer = data).document
            val iframe = playerDoc.selectFirst("iframe")?.attr("src")

            if (iframe != null) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = playerName.ifEmpty { "Player" },
                        url = fixUrl(iframe),
                        referer = playerUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        // Vidaara Direct Fallback
        Regex("""https?://vidaarax\.net/e/[\w-]+""").find(document.toString())?.value?.let { embed ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "Vidara Direct",
                    url = embed,
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