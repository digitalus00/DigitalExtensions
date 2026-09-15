package com.digital.desihub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class DesiHub : MainAPI() {
    override var mainUrl = "https://desihub.sh"
    override var name = "DesiHub"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/explore/1" to "Explore",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data == mainUrl -> "$mainUrl/page/$page"
            request.data.startsWith("$mainUrl/explore") -> "$mainUrl/explore/$page"
            else -> "${request.data.trimEnd('/')}/page/$page"
        }
        val doc = app.get(url, headers = headers, referer = "$mainUrl/").document
        val items = parseCards(doc)
        val hasNext = doc.selectFirst("a[href*=/page/${page + 1}], a[href*=/explore/${page + 1}]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    // The site search navigates to /x/<slug> where slug drops non-word
    // characters, lowercases and joins words with dashes.
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleaned = query.trim().replace(Regex("[^\\w\\s-]"), "")
        if (cleaned.length < 3) return newSearchResponseList(emptyList(), false)
        val slug = cleaned.lowercase().replace(Regex("\\s+"), "-")
        val url = if (page <= 1) "$mainUrl/x/$slug" else "$mainUrl/x/$slug/$page"
        val doc = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").document }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)
        return newSearchResponseList(parseCards(doc), hasNext = doc.selectFirst("a[href*=/x/$slug/${page + 1}]") != null)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | ")?.trim()
            ?: throw ErrorLoadingException("DesiHub title was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val year = Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})").find(doc.html())?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[href*=/category/]")
            .mapNotNull { it.attr("href").substringAfterLast('/').takeIf(String::isNotBlank) }
            .map { it.replace('-', ' ').trim() }
            .filter { it.length > 2 }
            .distinct()
            .take(8)
        val recommendations = parseCards(doc).filter { it.url != url }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Every post embeds one or more downloaddirect.xyz players; each embed is
    // a Plyr page wrapping a single direct mp4.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers, referer = "$mainUrl/").document
        val embeds = doc.select("iframe[src]").map { fixUrl(it.attr("src")) }
            .filter { it.startsWith("http") }.distinct()
        var found = false
        embeds.forEachIndexed { index, embed ->
            val label = if (embeds.size > 1) "${name} ${index + 1}" else name
            when {
                resolveDownloadDirect(embed, label, data, callback) -> found = true
                loadExtractor(embed, data, subtitleCallback, callback) -> found = true
            }
        }
        return found
    }

    private suspend fun resolveDownloadDirect(
        embedUrl: String,
        label: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(embedUrl, headers = headers, referer = pageUrl).text }.getOrNull()
            ?: return false
        val mp4 = Regex("<source[^>]+src=\"(https?://[^\"]+\\.mp4[^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("https?://[^\\s\"']+\\.mp4(?:\\?[^\\s\"']*)?", RegexOption.IGNORE_CASE).find(html)?.value
            ?: return false
        callback(
            newExtractorLink(name, label, mp4, ExtractorLinkType.VIDEO) {
                referer = embedUrl
            }
        )
        return true
    }

    // Poster cards wrap a title heading and a next/image loader whose url
    // query parameter carries the real image host.
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").let { if (it.startsWith("http")) it else mainUrl + it }
            if (!href.contains("$mainUrl/post/") && !href.contains("$mainUrl/feed/")) continue
            val title = a.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf(String::isNotBlank) ?: continue
            val poster = a.selectFirst("img")?.let(::extractNextImage)
            results.add(newMovieSearchResponse(title, href, TvType.NSFW) { posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    private fun extractNextImage(img: Element): String? {
        for (attr in listOf("srcset", "src")) {
            val value = img.attr(attr).takeIf { it.isNotBlank() } ?: continue
            val encoded = Regex("/_next/image\\?url=([^&\\s]+)").find(value)?.groupValues?.get(1) ?: continue
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: continue
            if (decoded.startsWith("http")) return decoded
        }
        return null
    }
}
