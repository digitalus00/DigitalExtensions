package com.glttv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DesiKahaniya : MainAPI() {
    override var mainUrl = "https://www.desikahani2.net"
    override var name = API_NAME
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Stories",
        "$mainUrl/category/top-collection/" to "Top Collection",
        "$mainUrl/category/hindi-chudai-kahani/" to "Hindi Stories",
        "$mainUrl/category/desi-chudai/" to "Desi Stories",
        "$mainUrl/category/bhabhi-ki-chudai/" to "Bhabhi Stories",
        "$mainUrl/category/parivar-me-chudai/" to "Family Stories",
        "$mainUrl/category/pehli-chudai/" to "First Time",
        "$mainUrl/category/other-languages/" to "Other Languages",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        val items = doc.select("article.post").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            request.name,
            items,
            hasNext = doc.selectFirst("a.next.page-numbers, a.nextpostslink") != null,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = getDocument(url)
        return newSearchResponseList(
            doc.select("article.post").mapNotNull { it.toSearchResponse() },
            hasNext = doc.selectFirst("a.next.page-numbers, a.nextpostslink") != null,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val article = doc.selectFirst("article.post")
            ?: throw ErrorLoadingException("Story content was not found")
        val title = article.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Story title was not found")
        val content = article.selectFirst(".entry-content")
            ?.let(::extractStoryText)
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Story text was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf(String::isNotBlank)
            ?: content.take(240).trimEnd() + if (content.length > 240) "..." else ""
        val tags = article.select(".tags-links a, a[rel=tag]").map { it.text().trim() }.filter(String::isNotBlank)

        StoryReader.cache(url, StoryDocument(title, content))
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = false

    private suspend fun getDocument(url: String): Document {
        val response = app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.8,hi;q=0.7",
                "Referer" to "$mainUrl/",
                "Cache-Control" to "no-cache",
            ),
        )
        val doc = response.document
        if (response.code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("The website temporarily requested Cloudflare verification. Try again shortly.")
        }
        return doc
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("h2.entry-title a[href], h1.entry-title a[href]") ?: return null
        val title = link.text().trim().ifBlank { return null }
        val href = link.absUrl("href").ifBlank { link.attr("href") }.ifBlank { return null }
        val image = selectFirst(".post-image img, .entry-content img, img.wp-post-image")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank)
            ?: image?.attr("src")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    private fun extractStoryText(content: Element): String {
        val clean = content.clone()
        clean.select(
            "script, style, iframe, form, ins, .code-block, .sharedaddy, .post-views, " +
                ".jp-relatedposts, .yarpp-related, .ad, [class*=advert], [id*=advert]",
        ).remove()
        clean.select("br").append("\n")
        clean.select("p, div, h2, h3, h4, li, blockquote").forEach { it.append("\n\n") }
        return clean.wholeText()
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun pagedUrl(base: String, page: Int): String =
        if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"

    companion object {
        const val API_NAME = "DesiKahaniya"
        const val LOGO_URL = "https://www.desikahani2.net/wp-content/uploads/2014/12/dk_logo.png"
    }
}

data class StoryDocument(val title: String, val text: String)
