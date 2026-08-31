package com.digital.multimovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MultiMovies : MainAPI() {
    override var mainUrl = "https://multimovies.beer"
    override var name = "Multimovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(mainUrl to "Latest", "$mainUrl/movies/" to "Movies", "$mainUrl/tvshows/" to "TV Shows", "$mainUrl/release/2026/" to "2026")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/")
        val items = doc.select(".thumb, .items article, .result-item, .movie-item, article.item").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = doc.selectFirst("a.next, .pagination a.next, .nav-previous a") != null)
    }
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}" else "$mainUrl/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = getDoc(url)
        return newSearchResponseList(doc.select(".thumb, .result-item, .movie-item, article.item").mapNotNull { it.toResult() }.distinctBy { it.url }, hasNext = doc.selectFirst("a.next, .pagination a.next") != null)
    }
    override suspend fun quickSearch(query: String) = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title = doc.selectFirst(".sheader h1, .data h1, h1.entry-title")?.text()?.trim() ?: doc.title().substringAfter("| ").trim()
        val poster = doc.selectFirst(".poster img, .sheader .poster img, meta[property=og:image]")?.let { if (it.tagName() == "meta") it.attr("content") else it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = doc.selectFirst(".wp-content p, .description, .resum p, .movie-description")?.text()?.trim()
        val isTv = url.contains("/tvshows/")
        if (isTv) {
            val eps = doc.select(".episodios article, .episodios .episodi, .episodios li").mapIndexedNotNull { i, e ->
                val href = e.selectFirst("a[href]")?.absUrl("href") ?: return@mapIndexedNotNull null
                newEpisode(href) { name = e.selectFirst(".episodiotitle, .episodiotitle a, h4")?.text()?.trim() ?: "Episode ${i + 1}"; season = 1; episode = i + 1 }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { this.posterUrl = poster; this.plot = plot }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = getDoc(data)
        val nonce = Regex("linksnonce\\\"?\\s*:\\s*\\\"([^\"]+)").find(doc.html())?.groupValues?.get(1)
        val postId = Regex("data-post=['\"](\\d+)").find(doc.html())?.groupValues?.get(1) ?: Regex("post-(\\d+)").find(doc.html())?.groupValues?.get(1)
        val type = if (data.contains("/tvshows/")) "tv" else "movie"
        val candidates = mutableListOf<String>()
        doc.select("iframe[src], video source[src], video[src]").forEach { candidates += it.absUrl("src").ifBlank { it.attr("src") } }
        if (postId != null) for (server in 1..6) runCatching {
            val body = mapOf("action" to "dooplay_player_ajax", "post" to postId, "nume" to server.toString(), "type" to type, "_wpnonce" to (nonce ?: ""))
            val html = app.post("$mainUrl/wp-admin/admin-ajax.php", data = body, referer = data).text
            val parsed = org.jsoup.Jsoup.parse(html)
            parsed.select("iframe[src], video source[src], video[src]").forEach { candidates += it.absUrl("src").ifBlank { it.attr("src") } }
        }
        var found = false
        candidates.filter { it.startsWith("http") }.distinct().forEach { link -> if (link.matches(Regex(".*\\.(m3u8|mp4)(\\?.*)?", RegexOption.IGNORE_CASE))) { callback(newExtractorLink(name, "Direct", link, if (link.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)); found = true } else if (loadExtractor(link, data, subtitleCallback, callback)) found = true }
        return found
    }
    private suspend fun getDoc(url: String): Document = app.get(url, referer = mainUrl, headers = mapOf("Accept" to "text/html,application/xhtml+xml", "Cache-Control" to "no-cache")).document
    private fun Element.toResult(): SearchResponse? { val a = selectFirst("a[href]") ?: return null; val href = a.absUrl("href").ifBlank { a.attr("href") }; if (!href.contains("/movies/") && !href.contains("/tvshows/")) return null; val title = selectFirst(".title, h2, h3, .data h3")?.text()?.trim() ?: a.selectFirst("img[alt]")?.attr("alt")?.trim() ?: return null; val img = selectFirst("img"); val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")?.takeIf { it.isNotBlank() }; return if (href.contains("/tvshows/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster } else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster } }
}
