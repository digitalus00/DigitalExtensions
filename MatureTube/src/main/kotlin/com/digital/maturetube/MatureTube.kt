package com.digital.maturetube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class MatureTube : MainAPI() {
    data class MatureTubeItem(val title: String, val poster: String? = null, val outUrl: String)

    override var mainUrl = "https://www.maturetube.com"
    override var name = "MatureTube"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    private val cloudflareKiller = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/category/mom" to "Mom",
        "$mainUrl/category/mature" to "Mature",
        "$mainUrl/category/granny" to "Granny",
        "$mainUrl/category/gilf" to "GILF",
        "$mainUrl/category/homemade" to "Homemade",
        "$mainUrl/category/amateur-wife" to "Amateur Wife",
        "$mainUrl/category/taboo-fantasy" to "Taboo Fantasy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        return newHomePageResponse(request.name, parseItems(doc), hasNext = hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val response = app.post(
            "$mainUrl/searching/by-form",
            data = mapOf("search_query[query]" to query.trim()),
            referer = "$mainUrl/",
            interceptor = cloudflareKiller,
            headers = defaultHeaders,
        )
        checkResponse(response.code, response.document)
        val doc = if (page <= 1) response.document else getDocument(pagedUrl(response.url, page))
        return newSearchResponseList(parseItems(doc), hasNext = hasNext(doc))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val item = parseJson<MatureTubeItem>(URLDecoder.decode(url.substringAfter("/__item__?data="), "UTF-8"))
        return newMovieLoadResponse(item.title, url, TvType.NSFW, item.outUrl) { posterUrl = item.poster }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller, headers = defaultHeaders, allowRedirects = false)
        val destination = response.headers["Location"]?.let(::fixUrl)
            ?: response.url.takeIf { it.startsWith("http") && !it.startsWith(mainUrl) }
            ?: return false
        return loadExtractor(destination, data, subtitleCallback, callback)
    }

    private val defaultHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9")

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller, headers = defaultHeaders)
        checkResponse(response.code, response.document)
        return response.document
    }

    private fun checkResponse(code: Int, doc: Document) {
        if (code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("MatureTube requested Cloudflare verification. Open the site once in WebView and retry.")
        }
    }

    private fun parseItems(doc: Document) = doc.select(".card.sub.group").mapNotNull { it.toResult() }.distinctBy { it.url }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst("a.item-title[href*=/out/], a.item-link[href*=/out/]") ?: return null
        val outUrl = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
        val title = selectFirst(".item-title[title]")?.attr("title")?.trim()?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").trim().takeIf(String::isNotBlank)
            ?: selectFirst("img.item-image")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val image = selectFirst("img.item-image")
        val poster = listOf("data-src", "data-original", "src")
            .firstNotNullOfOrNull { key -> image?.attr(key)?.takeIf(String::isNotBlank) }?.let(::fixUrl)
        val item = MatureTubeItem(title, poster, outUrl)
        val itemUrl = "$mainUrl/__item__?data=${URLEncoder.encode(item.toJson(), "UTF-8")}"
        return newMovieSearchResponse(title, itemUrl, TvType.NSFW) { posterUrl = poster }
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val separator = if ('?' in base) '&' else '?'
        return "${base.substringBefore("#")}${separator}page=$page"
    }

    private fun hasNext(doc: Document) = doc.selectFirst("link[rel=next], .pagination a[aria-label='Next page']") != null
}
