package com.digital.maturetube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MatureTube : MainAPI() {
    override var mainUrl = "https://www.maturetube.com"
    override var name = "MatureTube"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    private val cloudflareKiller = CloudflareKiller()

    override val mainPage = mainPageOf(mainUrl to "Latest Videos")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        val items = parseItems(doc)
        return newHomePageResponse(request.name, items, hasNext = hasNext(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val slug = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "-")
        val base = "$mainUrl/search/$slug/"
        val doc = getDocument(pagedUrl(base, page))
        return newSearchResponseList(parseItems(doc), hasNext = hasNext(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val titleElement: Element? = doc.selectFirst("h1, .headline, .title-video, meta[property=og:title]")
        val title = titleElement?.run { if (tagName() == "meta") attr("content") else text() }
            ?.trim()?.takeIf(String::isNotBlank) ?: throw ErrorLoadingException("MatureTube title was not found")
        val posterElement: Element? = doc.selectFirst("meta[property=og:image], video[poster]")
        val poster = posterElement?.run { if (tagName() == "meta") attr("content") else attr("poster") }?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val tags = doc.select("a[href*=/categories/], a[href*=/tags/], a[href*=/models/]").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster; this.plot = plot; this.tags = tags }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = getDocument(data)
        val html = doc.toString().replace("\\/", "/")
        val candidates = buildList {
            addAll(doc.select("iframe[src], video[src], video source[src]").map { it.absUrl("src").ifBlank { fixUrl(it.attr("src")) } })
            addAll(Regex("https?://[^\\s\\\"']+?\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"']*)?", RegexOption.IGNORE_CASE).findAll(html).map { it.value })
        }.filter { it.startsWith("http") }.distinct()
        var found = false
        candidates.forEach { link ->
            if (link.contains(Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(newExtractorLink(name, name, link, if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = data })
                found = true
            } else if (!link.startsWith(mainUrl) && loadExtractor(link, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller, headers = mapOf("Accept-Language" to "en-US,en;q=0.9"))
        val doc = response.document
        if (response.code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("MatureTube requested Cloudflare verification. Open the site once in WebView and retry.")
        }
        return doc
    }

    private fun parseItems(doc: Document) = doc.select(".item, .video-item, .thumb-block, .list-videos .item, article")
        .mapNotNull { it.toResult() }.distinctBy { it.url }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst("a[href*=/video/], a[href*=/videos/], a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
            .takeIf { it.startsWith(mainUrl) && !it.contains(Regex("/(categories|tags|models|search)/", RegexOption.IGNORE_CASE)) } ?: return null
        val title = selectFirst(".title, .headline, h2, h3")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").trim().takeIf(String::isNotBlank)
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf(String::isNotBlank) ?: return null
        val image = selectFirst("img")
        val poster = listOf("data-original", "data-src", "data-thumb", "src").firstNotNullOfOrNull { key -> image?.attr(key)?.takeIf(String::isNotBlank) }
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster?.let(::fixUrl) }
    }

    private fun pagedUrl(base: String, page: Int) = if (page <= 1) base else "${base.trimEnd('/')}?page=$page"
    private fun hasNext(doc: Document, page: Int) = doc.selectFirst("link[rel=next], a.next, .pagination a.next") != null ||
        doc.select(".pagination a, .pagination li a").any { (it.text().toIntOrNull() ?: 0) > page }
}
