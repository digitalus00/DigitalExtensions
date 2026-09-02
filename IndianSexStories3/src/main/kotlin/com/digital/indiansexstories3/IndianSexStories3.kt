package com.digital.indiansexstories3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class IndianSexStories3 : MainAPI() {
    private val cloudflareKiller = CloudflareKiller()
    override var mainUrl = "https://www.indiansexstories3.com"
    override var name = ISS3_NAME
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Stories",
        "$mainUrl/popular-stories/" to "Top Collection",
        "$mainUrl/category/hindi-sex-stories/" to "Hindi Stories",
        "$mainUrl/category/desi/" to "Desi Stories",
        "$mainUrl/category/couple/" to "Bhabhi Stories",
        "$mainUrl/category/group/" to "Family Stories",
        "$mainUrl/category/virgin/" to "First Time",
        "$mainUrl/series/" to "Other Languages",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDocument(pagedUrl(request.data, page))
        val items = doc.select("article.post").mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
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
            doc.select("article.post").mapNotNull { it.toSearchResponse() }.distinctBy { it.url },
            hasNext = doc.selectFirst("a.next.page-numbers, a.nextpostslink") != null,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val article = doc.selectFirst("article.post, .inside-article")
            ?: throw ErrorLoadingException("Story content was not found")
        val title = article.selectFirst("h1.entry-title, h2.post-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Story title was not found")
        val content = article.selectFirst(".entry-content, .thecontent")
            ?.let(::extractStoryText)
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Story text was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        val tags = article.select(".meta-tags a, .tags-links a, a[rel=tag]").map { it.text().trim() }.filter(String::isNotBlank)

        StoryReader.cache(url, StoryDocument(title, content))
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = content
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = getDocument(data)
        val content = doc.selectFirst(".entry-content, .thecontent, article.post") ?: doc
        val links = content.select("iframe[src], video[src], video source[src], audio[src], audio source[src], a[href]")
            .map { it.absUrl("src").ifBlank { it.absUrl("href") }.ifBlank { it.attr("src").ifBlank { it.attr("href") } } }
            .filter { it.startsWith("http") && !it.contains("indiansexstories3.com") }
            .filterNot { it.contains(Regex("(?i)(youtube|youtu\\.be|facebook|twitter|instagram|telegram|whatsapp|image)")) }
            .distinct()
        var found = false
        links.forEach { link ->
            if (link.contains(Regex("\\.(m3u8|mp4|webm|mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(newExtractorLink(name, "Direct", link, if (link.contains(Regex("\\.(mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE))) ExtractorLinkType.VIDEO else if (link.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = data })
                found = true
            } else if (loadExtractor(link, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(
            url,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.8,hi;q=0.7",
                "Referer" to "$mainUrl/",
                "Cache-Control" to "no-cache",
            ),
            interceptor = cloudflareKiller,
        )
        val doc = response.document
        if (response.code == 403 || doc.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("IndianSexStories3 temporarily requested Cloudflare verification. Try again shortly.")
        }
        return doc
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("h2.post-title a[href], h1.entry-title a[href], .post-title a[href]") ?: return null
        val title = link.text().trim().ifBlank { return null }
        val href = link.absUrl("href").ifBlank { link.attr("href") }.takeIf { it.startsWith(mainUrl) } ?: return null
        val image = selectFirst(".featured-thumbnail img, .entry-content img, img.wp-post-image")
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
        const val ISS3_NAME = "IndianSexStories3"
        const val LOGO_URL = "https://www.indiansexstories3.com/wp-content/uploads/2014/12/dk_logo.png"
    }
}

data class StoryDocument(val title: String, val text: String)
