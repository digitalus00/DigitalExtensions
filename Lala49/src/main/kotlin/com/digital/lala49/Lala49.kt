package com.digital.lala49

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Lala49 : MainAPI() {
    override var mainUrl = "https://lalamasa.mobi"
    override var name = "Lala49"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/trending/?t=week" to "Trending",
        "$mainUrl/category/desi-amateur-porn/" to "Desi Amateur",
        "$mainUrl/category/indian-amateur-porn/" to "Indian Amateur",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/chamet-live-videos/" to "Chamet",
        "$mainUrl/category/pakistani-amateur-porn/" to "Pakistani",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pagedUrl(request.data, page), referer = "$mainUrl/").document
        return newHomePageResponse(request.name, parseItems(doc), hasNext = doc.selectFirst("a.next.page-numbers, .pager a[href*=/page/]") != null)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, referer = "$mainUrl/").document
        return newSearchResponseList(parseItems(doc), hasNext = doc.selectFirst("a.next.page-numbers, .pager a[href*=/page/]") != null)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.vh1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: throw ErrorLoadingException("Lala49 title was not found")
        val poster = doc.selectFirst("video[poster]")?.attr("poster")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val tags = doc.select(".video-cats a, a[rel=category], a[rel=tag]").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val links = doc.select("video source[src], video[src]").map { it.absUrl("src").ifBlank { fixUrl(it.attr("src")) } }
            .plus(Regex("https?://[^\\s\\\"']+?\\.mp4(?:\\?[^\\s\\\"']*)?", RegexOption.IGNORE_CASE).findAll(doc.html().replace("\\/", "/")).map { it.value })
            .filter { it.startsWith("http") }.distinct()
        links.forEach { link -> callback(newExtractorLink(name, "Lala49 Direct", link, ExtractorLinkType.VIDEO) { referer = data }) }
        return links.isNotEmpty()
    }

    private fun parseItems(doc: Document) = doc.select("article.vcard").mapNotNull { it.toResult() }.distinctBy { it.url }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = selectFirst(".vtitle")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val style = selectFirst(".thumb")?.attr("style").orEmpty().replace("&#038;", "&")
        val poster = Regex("url\\(['\"]?([^)'\"]+)").find(style)?.groupValues?.get(1)
        return newMovieSearchResponse(title, href, TvType.NSFW) { posterUrl = poster }
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if ('?' in base) "${base.substringBefore('?').trimEnd('/')}/page/$page/?${base.substringAfter('?')}"
        else "${base.trimEnd('/')}/page/$page/"
    }
}
