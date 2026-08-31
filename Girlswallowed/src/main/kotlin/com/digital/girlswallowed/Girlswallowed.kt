package com.digital.girlswallowed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Girlswallowed : MainAPI() {
    override var mainUrl = "https://girlswallowed.com"
    override var name = "Girlswallowed"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos",
        "$mainUrl/all-swallowed-videos/" to "All Videos",
        "$mainUrl/weekly-site-updates/" to "Weekly Updates",
        "$mainUrl/onlyfans/" to "OnlyFans",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pagedUrl(request.data, page), referer = "$mainUrl/").document
        val items = doc.select("article.loop-video").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNext(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("article.loop-video").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newSearchResponseList(items, hasNext = hasNext(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val titleElement: Element? = doc.selectFirst("h1.entry-title, meta[itemprop=name]")
        val title = titleElement?.run { if (tagName() == "meta") attr("content") else text() }
            ?.trim()?.takeIf(String::isNotBlank) ?: throw ErrorLoadingException("Girlswallowed title was not found")
        val poster = doc.selectFirst("meta[property=og:image], meta[itemprop=thumbnailUrl]")?.attr("content")?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val tags = doc.select("#video-actors a, .tags-list a").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val recommendations = doc.select(".under-video-block article.loop-video").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val links = doc.select(".video-player iframe[src], .video-player video[src], .video-player source[src]")
            .map { it.absUrl("src").ifBlank { fixUrl(it.attr("src")) } }.filter { it.startsWith("http") }.distinct()
        var found = false
        links.forEach { link ->
            if (link.contains(Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(newExtractorLink(name, name, link, if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = data })
                found = true
            } else if (loadExtractor(link, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = selectFirst(".entry-header")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").trim().takeIf(String::isNotBlank) ?: return null
        val image = selectFirst(".post-thumbnail img")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank) ?: image?.attr("src")?.takeIf(String::isNotBlank)
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster?.let(::fixUrl) }
    }

    private fun pagedUrl(base: String, page: Int) = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
    private fun hasNext(doc: Document, page: Int) = doc.selectFirst("link[rel=next], a.next.page-numbers, .pagination a.next") != null ||
        doc.select(".pagination a, a.page-numbers").any { (it.text().toIntOrNull() ?: 0) > page }
}
