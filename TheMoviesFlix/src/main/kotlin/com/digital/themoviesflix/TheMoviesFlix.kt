package com.digital.themoviesflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TheMoviesFlix : MainAPI() {
    override var mainUrl = "https://themoviesflix.actor"
    override var name = "TheMoviesFlix"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    private val cloudflareKiller = CloudflareKiller()

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/category/bollywood-movies/" to "Bollywood",
        "$mainUrl/category/hollywood/" to "Hollywood",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/480p/" to "480p",
        "$mainUrl/category/720p/" to "720p",
        "$mainUrl/category/1080p/" to "1080p",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        return newHomePageResponse(request.name, parseItems(doc), hasNext = hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = getDocument(url)
        return newSearchResponseList(parseItems(doc), hasNext = hasNext(doc))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val article = doc.selectFirst("article.post, .single_post") ?: doc
        val title = article.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - TheMoviesflix")?.trim()
            ?: throw ErrorLoadingException("TheMoviesFlix title was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val plot = article.selectFirst(".entry-content")?.text()?.trim()?.takeIf(String::isNotBlank)
        val tags = article.select(".post-tags a, a[rel=tag], .thecategory a").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val episodes = article.select(".entry-content a[href]").mapNotNull { link ->
            val episode = Regex("(?i)(?:episode|ep)[ ._-]*(\\d+)").find(link.text()) ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return@mapNotNull null
            newEpisode(href) { name = link.text().trim(); this.episode = episode.groupValues[1].toInt(); season = 1 }
        }.distinctBy { it.data }
        return if (episodes.isNotEmpty()) newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot; this.tags = tags }
        else newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; this.plot = plot; this.tags = tags }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = getDocument(data)
        val links = doc.select(".entry-content a[href]").map { it.absUrl("href") }
            .filter { it.startsWith("http") && !it.startsWith(mainUrl) }
            .filterNot { it.contains(Regex("(?i)(imdb|youtube|telegram|how-to-download|facebook|twitter)")) }
            .distinct()
        var found = false
        links.forEach { link -> if (loadExtractor(link, data, subtitleCallback, callback)) found = true }
        return found
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller, headers = mapOf("Accept-Language" to "en-US,en;q=0.9"))
        if (response.code == 403 || response.document.title().contains("Just a moment", true)) throw ErrorLoadingException("TheMoviesFlix Cloudflare verification is required. Open the site in WebView and retry.")
        return response.document
    }

    private fun parseItems(doc: Document) = doc.select("article.latestpost").mapNotNull { it.toResult() }.distinctBy { it.url }

    private fun Element.toResult(): SearchResponse? {
        val link = selectFirst("h2.entry-title a[href], a#featured-thumbnail[href]") ?: return null
        val href = link.absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return null
        val title = selectFirst("h2.entry-title")?.text()?.trim() ?: link.attr("title").trim().takeIf(String::isNotBlank) ?: return null
        val poster = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.takeIf(String::isNotBlank)
        val series = title.contains(Regex("(?i)season|web series|episode|series"))
        return if (series) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster } else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    private fun pagedUrl(base: String, page: Int) = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
    private fun hasNext(doc: Document) = doc.selectFirst("a.next.page-numbers, .navigation a.next, link[rel=next]") != null
}
