package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycum.tv"
    override var name = "PinayCum.tv"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "tl"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
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
        val titleElement = selectFirst("h2, strong, .title, a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.attr("src")
                ?: selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("data-lazy")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h1, h2, title")?.text()?.trim() ?: "Pinay Video"

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

        // Extract all provider links (doodstream, streamruby, vidara, etc.)
        document.select("a[href*='?s=']").forEach { el ->
            val providerUrl = fixUrlNull(el.attr("href"))
            if (providerUrl != null) {
                // Try to load the provider page
                val providerDoc = app.get(providerUrl, referer = mainUrl).document

                // Look for direct video sources
                providerDoc.select("video[src], source[src], a[href*='.mp4'], a[href*='.m3u8']").forEach { videoEl ->
                    val videoUrl = fixUrlNull(videoEl.attr("src") ?: videoEl.attr("href"))
                    if (videoUrl != null && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                        callback(
                            newExtractorLink(
                                name = name,
                                source = "Direct",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }

                // Also try vidaratem.com directly
                providerDoc.selectFirst("a[href*='vidaratem.com']")?.attr("href")?.let { vidLink ->
                    callback(
                        newExtractorLink(
                            name = name,
                            source = "Vidaratem",
                            url = fixUrl(vidLink),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        }

        // Fallback: Direct vidaratem from main page
        document.selectFirst("a[href*='vidaratem.com']")?.attr("href")?.let { link ->
            callback(
                newExtractorLink(
                    name = name,
                    source = "Vidaratem",
                    url = fixUrl(link),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }
}