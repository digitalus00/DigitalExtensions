package com.digital.hdhub4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HDHub4u : MainAPI() {
    override var mainUrl = "https://new5.hdhub4u.cl"
    override var name = "HDHub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val mainPage = mainPageOf(mainUrl to "Latest", "$mainUrl/category/bollywood-movies/" to "Bollywood", "$mainUrl/category/hollywood-movies/" to "Hollywood", "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed", "$mainUrl/category/south-hindi-movies/" to "South Hindi", "$mainUrl/category/category/web-series/" to "Web Series")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse { val doc = getDoc(if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"); val items = doc.select(".thumb").mapNotNull { it.toResult() }.distinctBy { it.url }; return newHomePageResponse(request.name, items, hasNext = doc.selectFirst("a.next.page-numbers") != null) }
    override suspend fun search(query: String, page: Int): SearchResponseList { val url = if (page <= 1) "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}" else "$mainUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}"; val doc = getDoc(url); return newSearchResponseList(doc.select(".thumb").mapNotNull { it.toResult() }.distinctBy { it.url }, hasNext = doc.selectFirst("a.next.page-numbers") != null) }
    override suspend fun quickSearch(query: String) = search(query, 1).items

    override suspend fun load(url: String): LoadResponse { val doc = getDoc(url); val title = doc.selectFirst("h1.entry-title, .page-title, h1")?.text()?.trim() ?: doc.title(); val poster = doc.selectFirst("main img[src], .page-body img[src]")?.attr("src"); val plot = doc.selectFirst(".page-body p")?.text()?.trim(); return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; this.plot = plot } }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean { val doc = getDoc(data); val links = doc.select(".page-body a[href], main a[href]").map { it.absUrl("href").ifBlank { it.attr("href") } }.filter { it.startsWith("http") && !it.contains("hdhub4u.cl") && !it.contains("catimages") && !it.contains("youtube") }.distinct(); var found = false; links.forEach { link -> if (loadExtractor(link, data, subtitleCallback, callback)) found = true }; return found }
    private suspend fun getDoc(url: String): Document = app.get(url, referer = mainUrl, headers = mapOf("Accept" to "text/html,application/xhtml+xml" )).document
    private fun Element.toResult(): SearchResponse? { val a = selectFirst("a[href]") ?: return null; val href = a.absUrl("href").ifBlank { a.attr("href") }; if (href == mainUrl || href.contains("/category/") || href.contains("/page/")) return null; val img = selectFirst("img"); val title = a.selectFirst("p")?.text()?.trim() ?: img?.attr("alt")?.trim() ?: return null; val poster = img?.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img?.attr("src"); val isSeries = title.contains(Regex("(?i)season|series|episodes")); return if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster } else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster } }
}
