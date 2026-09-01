package com.digital.prmovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PrMovies : MainAPI() {
    private val cloudflareKiller = CloudflareKiller()
    override var mainUrl = "https://prmovies.energy"
    override var name = "PRMovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/genre/bollywood/" to "Bollywood",
        "$mainUrl/genre/hollywood/" to "Hollywood",
        "$mainUrl/genre/dual-audio/" to "Dual Audio",
        "$mainUrl/series/" to "English Series",
        "$mainUrl/genre/web-series/" to "Hindi Series",
        "$mainUrl/genre/south-special/" to "South Special",
        "$mainUrl/genre/top-rated/" to "Top Rated",
        "$mainUrl/genre/erotic-movies/" to "Erotic",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        val items = doc.select(".ml-item").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = getDocument(url)
        val items = doc.select(".ml-item").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newSearchResponseList(items, hasNext = hasNextPage(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val title = doc.selectFirst(".mvic-desc h3[itemprop=name], .mvic-desc h3")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - Prmovies")?.trim()
            ?: throw ErrorLoadingException("Title was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst(".mvic-thumb img")?.attr("src")?.takeIf(String::isNotBlank)
        val background = styleUrl(doc.selectFirst("#content-cover")?.attr("style"))
        val plot = doc.selectFirst(".mvic-desc .f-desc, .mvic-desc [itemprop=description]")?.text()?.trim()
        val year = infoLinks(doc, "Release:").firstOrNull()?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val tags = infoLinks(doc, "Genre:")
        val actors = infoLinks(doc, "Actors:").map { ActorData(Actor(it)) }
        val recommendations = doc.select(".movies-list .ml-item, .mlist .ml-item, #mv-info + * .ml-item")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .filterNot { it.url == url }

        val episodeElements = doc.select("#seasons .tvseason")
        if (episodeElements.isNotEmpty() || url.contains("/series/")) {
            val defaultSeason = Regex("(?i)season[ -]?(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodes = episodeElements.flatMap { seasonBlock ->
                val season = Regex("\\d+").find(seasonBlock.selectFirst(".les-title")?.text().orEmpty())
                    ?.value?.toIntOrNull() ?: defaultSeason
                seasonBlock.select(".les-content a[href]").mapIndexedNotNull { index, link ->
                    val href = link.absUrl("href").ifBlank { link.attr("href") }.ifBlank { return@mapIndexedNotNull null }
                    val number = Regex("(?i)episode[ -]?(\\d+)").find(link.text())?.groupValues?.get(1)?.toIntOrNull()
                        ?: index + 1
                    newEpisode(href) {
                        this.name = link.text().trim().ifBlank { "Episode $number" }
                        this.season = season
                        this.episode = number
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = getDocument(data)
        val candidates = buildList {
            addAll(doc.select("#content-embed iframe[src], .movieplay iframe[src], iframe[src]").map { it.absUrl("src").ifBlank { it.attr("src") } })
            addAll(doc.select("video[src], video source[src]").map { it.absUrl("src").ifBlank { it.attr("src") } })
        }.filter { it.startsWith("http") && !it.contains("twitter.com") }.distinct()

        var found = false
        candidates.forEach { playerUrl ->
            if (playerUrl.contains(Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = playerUrl,
                        type = if (playerUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = data
                    },
                )
                found = true
            } else if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
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
            ),
            interceptor = cloudflareKiller,
        )
        val doc = response.document
        if (response.code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("PRMovies requested Cloudflare verification. Open the site once in WebView or try again later.")
        }
        return doc
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a.ml-mask[href], a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }.ifBlank { return null }
        val title = link.selectFirst("h2")?.text()?.trim()
            ?: link.attr("oldtitle").trim().takeIf(String::isNotBlank)
            ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val image = link.selectFirst("img")
        val poster = image?.attr("data-original")?.takeIf(String::isNotBlank)
            ?: image?.attr("data-src")?.takeIf(String::isNotBlank)
            ?: image?.attr("src")?.takeIf(String::isNotBlank)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun infoLinks(doc: Document, label: String): List<String> =
        doc.select(".mvic-info p").firstOrNull { it.selectFirst("strong")?.text()?.trim() == label }
            ?.select("a")?.map { it.text().trim() }?.filter(String::isNotBlank).orEmpty()

    private fun styleUrl(style: String?): String? = style?.let {
        Regex("url\\(['\"]?([^'\")]+)").find(it)?.groupValues?.get(1)
    }

    private fun pagedUrl(base: String, page: Int): String =
        if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.selectFirst("a.next.page-numbers, a.nextpostslink, .pagination a[href*='/page/${page + 1}']") != null
}
