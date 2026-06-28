package com.desikahani2

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Desikahani2 : MainAPI() {
    override var mainUrl              = "https://www.desikahani2.net"
    override var name                 = "DesiKahani2"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    // Homepage rails. Keys are path fragments appended to mainUrl, values are display names.
    override val mainPage = mainPageOf(
        "/videos"                    to "Latest Videos",
        "/category/desi-mms"         to "Desi MMS",
        "/category/desi-bhabhi"      to "Desi Bhabhi",
        "/category/desi-aunty"       to "Desi Aunty",
        "/category/college-girls"    to "College Girls",
        "/category/hindi-audio"      to "Hindi Audio",
        "/category/sex-scandals"     to "Sex Scandals",
        "/category/threesome"        to "Threesome"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        // WordPress style pagination: /videos/page/2/  or  /category/x/page/2/
        val url = if (page == 1) "$mainUrl$path/" else "$mainUrl$path/page/$page/"
        val document = app.get(url).document

        val home = document.select("article, div.item, div.post, li.video-item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple anchor patterns – WP themes vary
        val linkEl = this.selectFirst("h2 a, h3 a, a.title, a.thumb, a[rel=bookmark], a")
            ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isBlank()) return null

        val title = (linkEl.attr("title").ifBlank { null }
            ?: linkEl.text().ifBlank { null }
            ?: this.selectFirst("h2, h3, .title")?.text()
            ?: "No Title").trim()

        val imgEl = this.selectFirst("img")
        var posterUrl = imgEl?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = imgEl?.attr("data-lazy-src")
        if (posterUrl.isNullOrBlank()) posterUrl = imgEl?.attr("src")
        posterUrl = posterUrl?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val encoded = query.trim().replace(" ", "+")

        // Paginate WP search a few pages deep
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/?s=$encoded"
            else "$mainUrl/page/$i/?s=$encoded"
            val doc = app.get(url).document
            val page = doc.select("article, div.item, div.post, li.video-item")
                .mapNotNull { it.toSearchResult() }
            if (page.isEmpty()) break
            // Stop if we’re just looping over the same first-page results
            if (results.isNotEmpty() && results.last().url == page.last().url) break
            results.addAll(page)
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Untitled"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video")?.attr("poster")
        )

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".entry-content p, .description, .post-content p")?.text()?.trim()

        val tags = document.select("a[rel=tag], .tags a, .post-tags a")
            .mapNotNull { it.text().ifBlank { null } }
            .distinct()

        val recommendations = document.select(
            ".related-posts article, .related article, .related-videos .item, .related li"
        ).mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val raw = doc.html()

        val candidates = mutableSetOf<String>()

        // 1) Direct <video>/<source> tags
        doc.select("video source, video").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) candidates.add(fixUrl(src))
        }

        // 2) Iframes (embedded players) – most common for tube sites
        doc.select("iframe").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.isNotBlank()) candidates.add(fixUrl(src))
        }

        // 3) Inline JS – file: "...", source: "...", "https://....mp4|.m3u8"
        val patterns = listOf(
            Regex("""file\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""src\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { rgx ->
            rgx.findAll(raw).forEach { m -> candidates.add(m.groupValues[1]) }
        }

        if (candidates.isEmpty()) return false

        candidates.forEach { link ->
            val low = link.lowercase()
            when {
                low.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = link,
                        referer = mainUrl
                    ).forEach(callback)
                }
                low.contains(".mp4") -> {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = INFER_TYPE
                        ) {
                            this.referer = mainUrl
                            this.quality = getIndexQuality(link)
                        }
                    )
                }
                else -> {
                    // Unknown iframe host – hand off to CloudStream’s extractor registry
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}