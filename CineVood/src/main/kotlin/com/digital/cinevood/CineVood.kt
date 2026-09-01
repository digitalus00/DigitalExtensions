package com.digital.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CineVood : MainAPI() {
    data class SearchResult(val found: Int = 0, val hits: List<SearchHit> = emptyList())
    data class SearchHit(val document: SearchDocument? = null)
    data class SearchDocument(
        val post_title: String? = null,
        val permalink: String? = null,
        val post_thumbnail: String? = null,
    )

    override var mainUrl = "https://cinevood.bingo"
    override var name = "CineVood"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cloudflareKiller = CloudflareKiller()

    private val catalogPages = listOf(
        mainUrl,
        "$mainUrl/bollywood/",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/",
        "$mainUrl/hindi-dubbed/south-dubbed/",
        "$mainUrl/web-series/",
        "$mainUrl/18-adult/",
    )

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/bollywood/" to "Bollywood",
        "$mainUrl/hindi-dubbed/hollywood-dubbed/" to "Hollywood Dubbed",
        "$mainUrl/hindi-dubbed/south-dubbed/" to "South Hindi Dubbed",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/movies/dual-audio/" to "Dual Audio",
        "$mainUrl/18-adult/" to "18+ Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        val items = doc.select("article.latestPost").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val direct = runCatching {
            val response = app.get(
                "$mainUrl/search.php?q=$encoded&page=$page",
                referer = "$mainUrl/search.html?q=$encoded",
                interceptor = cloudflareKiller,
            ).parsed<SearchResult>()
            val items = response.hits.mapNotNull { hit ->
                val item = hit.document ?: return@mapNotNull null
                val title = cleanTitle(item.post_title) ?: return@mapNotNull null
                val href = item.permalink?.let(::fixUrl)?.takeIf { it.startsWith(mainUrl) } ?: return@mapNotNull null
                val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
                val isSeries = title.contains(Regex("(?i)season|web[ ._-]*series|complete series"))
                if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = item.post_thumbnail; this.year = year }
                else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = item.post_thumbnail; this.year = year }
            }
            newSearchResponseList(items, hasNext = page * 18 < response.found)
        }.getOrNull()
        if (direct != null && direct.items.isNotEmpty()) return direct

        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val fallback = catalogPages.flatMap { catalogUrl ->
            runCatching { parseItems(getDocument(catalogUrl)) }.getOrDefault(emptyList())
        }.distinctBy { it.url }.filter { result ->
            val title = result.name.lowercase()
            terms.all(title::contains)
        }
        return newSearchResponseList(fallback, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val title = cleanTitle(doc.selectFirst("h1.title, h1.entry-title, .single-title, article h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("CineVood title was not found")
        val content = doc.selectFirst(".thecontent, .post-single-content, .entry-content, article .post-content, article.post") ?: doc
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: content.selectFirst("img[src]")?.attr("src")?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
            ?: content.selectFirst("p")?.text()?.trim()?.takeIf(String::isNotBlank)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val tags = doc.select(".thecategory a, .post-info a[rel=category], .tags a, a[rel=tag]")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val recommendations = doc.select(".related-posts article.latestPost, article.latestPost")
            .mapNotNull { it.toSearchResponse() }.filter { it.url != url }.distinctBy { it.url }
        val episodeLinks = content.select("a[href]").mapNotNull { link ->
            val label = link.text().trim()
            val match = Regex("(?i)(?:episode|ep)[ ._-]*(\\d+)").find(label) ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val episode = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val season = Regex("(?i)season[ ._-]*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?i)season[ ._-]*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            newEpisode(href) { this.name = label.ifBlank { "Episode $episode" }; this.season = season; this.episode = episode }
        }.distinctBy { it.data }
        val isSeries = episodeLinks.isNotEmpty() || url.contains("series", true) || title.contains(Regex("(?i)season|web[ ._-]*series"))

        if (isSeries && episodeLinks.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeLinks) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
        return newMovieLoadResponse(title, url, if (isSeries) TvType.TvSeries else TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith(mainUrl)) {
            return loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        val doc = getDocument(data)
        val content = doc.selectFirst(".thecontent, .post-single-content, .entry-content, article .post-content, article.post") ?: doc
        val candidates = buildList {
            addAll(content.select("iframe[src], video[src], video source[src]").map { it.absUrl("src").ifBlank { it.attr("src") } })
            addAll(content.select("a[href]:has(button), a.btn[href], a.button[href], a[href*='download'], a[href*='watch'], a[href*='stream']")
                .map { it.absUrl("href").ifBlank { it.attr("href") } })
        }.filter { link ->
            link.startsWith("http") &&
                !link.startsWith(mainUrl) &&
                !link.contains(Regex("(?i)(youtube|youtu\\.be|facebook|twitter|instagram|telegram|whatsapp|imdb|tmdb|image\\.tmdb|wp-content|doubleclick|googlesyndication)"))
        }.distinct()

        var found = false
        candidates.forEach { link ->
            if (link.contains(Regex("\\.(m3u8|mp4|mkv)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(newExtractorLink(name, "CineVood Direct", link, if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = data })
                found = true
            } else if (loadExtractor(link, data, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(
            url,
            referer = "$mainUrl/",
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
                "Cache-Control" to "no-cache",
            ),
            interceptor = cloudflareKiller,
        )
        val doc = response.document
        if (response.code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("CineVood Cloudflare verification did not complete. Open the site once in WebView and retry.")
        }
        return doc
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("h2.title a[href], a.post-image[href], h2 a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = cleanTitle(selectFirst("h2.title, h2.front-view-title")?.text()
            ?: link.attr("title"))
            ?: return null
        val image = selectFirst("img")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank)
            ?: image?.attr("src")?.takeIf(String::isNotBlank)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val isSeries = title.contains(Regex("(?i)season|web[ ._-]*series|complete series"))
        return if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster; this.year = year }
        else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster; this.year = year }
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.selectFirst("a.next.page-numbers, .pagination a.next, link[rel=next]") != null ||
            doc.select(".pagination a, .page-numbers a").any { it.text().toIntOrNull()?.let { number -> number > page } == true }

    private fun pagedUrl(base: String, page: Int): String = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"

    private fun parseItems(doc: Document): List<SearchResponse> =
        doc.select("article.latestPost").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }

    private fun cleanTitle(value: String?): String? = value?.trim()
        ?.replace(Regex("(?i)\\s*[-|]?\\s*CineVood\\s*$"), "")
        ?.trim()?.takeIf(String::isNotBlank)
}
