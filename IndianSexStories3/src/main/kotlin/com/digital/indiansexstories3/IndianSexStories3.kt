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
        "$mainUrl/videos/" to "Videos",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isVideos = request.data.contains("/videos")
        val doc = getDocument(pagedUrl(request.data, page))
        val items = if (isVideos) {
            doc.select("[id*=most_recent] .thumb.item").ifEmpty { doc.select(".thumb.item") }
                .mapNotNull { if (it.hasClass("ad")) null else it.toVideoResponse() }
        } else {
            doc.select("article.post").mapNotNull { it.toSearchResponse() }
        }.distinctBy { it.url }
        val hasNext = if (isVideos) {
            doc.html().contains(";from:${page + 1}\"")
        } else {
            doc.selectFirst("a.next.page-numbers, a.nextpostslink") != null
        }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = getDocument(url)
        val items = doc.select("article.post").mapNotNull { it.toSearchResponse() }.toMutableList()
        var videoNext = false
        runCatching {
            val videoDoc = getDocument("$mainUrl/videos/search/$encoded/$page/")
            videoDoc.select(".thumb.item").forEach { element ->
                if (!element.hasClass("ad")) element.toVideoResponse()?.let(items::add)
            }
            videoNext = videoDoc.html().contains(";from:${page + 1}\"")
        }
        return newSearchResponseList(
            items.distinctBy { it.url },
            hasNext = videoNext || doc.selectFirst("a.next.page-numbers, a.nextpostslink") != null,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        if (url.contains("/videos/")) return loadVideo(url, doc)
        val article = doc.selectFirst("article.post, article.inside-article") ?: doc.body()
        val title = article.selectFirst("h1.post-title, h1.entry-title, h1.title, h2.post-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Story title was not found")
        val storyText = article.selectFirst(".story-content, .entry-content, .thecontent")
            ?.let(::extractStoryText)
            ?.takeIf { it.isNotBlank() }
        val videoText = article.selectFirst(".desc, .description")?.text()?.trim().orEmpty()
        if (storyText == null && videoText.isBlank()) {
            throw ErrorLoadingException("Story text was not found")
        }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        val tags = article.select(".meta-tags a, .tags-links a, a[rel=tag]").map { it.text().trim() }.filter(String::isNotBlank)

        if (storyText != null) StoryReader.cache(url, StoryDocument(title, storyText))
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = storyText ?: videoText
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.contains("/videos/")) return false
        return runCatching {
            val doc = getDocument(data)
            val flashvars = parseFlashvars(doc)
            val license = flashvars["license_code"]
            var found = false
            val urlKeys = listOf("video_url", "video_alt_url", "video_alt_url2", "video_alt_url3")
            for (key in urlKeys) {
                val raw = flashvars[key]?.takeIf { it.startsWith("http") || it.startsWith("function/0/") } ?: continue
                val link = kvsDecodeUrl(raw, license)
                val label = flashvars["${key}_text"]?.takeIf(String::isNotBlank) ?: "Direct"
                callback(newExtractorLink(name, label, link, ExtractorLinkType.VIDEO) { referer = data })
                found = true
            }
            if (found) return@runCatching true
            val content = doc.selectFirst(".video-holder, .video-inner") ?: doc
            content.select("iframe[src], video[src], video source[src]")
                .map { it.absUrl("src").ifBlank { it.attr("src") } }
                .filter { it.startsWith("http") && !it.contains("indiansexstories3.com") }
                .distinct()
                .forEach { link -> if (loadExtractor(link, data, subtitleCallback, callback)) found = true }
            found
        }.getOrDefault(false)
    }

    private suspend fun loadVideo(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1.title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Video title was not found")
        val plot = doc.selectFirst(".desc, .description")?.text()?.trim().orEmpty()
        val flashvars = parseFlashvars(doc)
        val poster = flashvars["preview_url"]?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        val tags = doc.select("a[href*=/videos/categories/], a[href*=/videos/tags/]")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    private fun parseFlashvars(doc: Document): Map<String, String> {
        val js = doc.select("script").firstOrNull { it.data().contains("flashvars") }?.data() ?: return emptyMap()
        return Regex("(\\w+)\\s*:\\s*'([^']*)'").findAll(js)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun kvsDecodeUrl(videoUrl: String, licenseCode: String?): String {
        if (!videoUrl.startsWith("function/0/") || licenseCode == null) return videoUrl
        val base = videoUrl.removePrefix("function/0/")
        val token = kvsLicenseToken(licenseCode)
        val match = Regex("/([0-9a-f]{32})").find(base) ?: return base
        val hash = match.groupValues[1]
        val indices = (0 until hash.length).toMutableList()
        var accum = 0
        for (src in hash.length - 1 downTo 0) {
            accum += token.getOrElse(src) { 0 }
            val dest = (src + accum) % hash.length
            indices[src] = indices[dest].also { indices[dest] = indices[src] }
        }
        val decoded = indices.joinToString("") { hash[it].toString() }
        return base.replaceRange(match.groups[1]!!.range, decoded)
    }

    private fun kvsLicenseToken(licenseCode: String): List<Int> {
        val code = licenseCode.replace("$", "")
        val values = code.map { it - '0' }
        val mod = code.replace('0', '1')
        val center = mod.length / 2
        val front = mod.substring(0, center + 1).toLong()
        val back = mod.substring(center).toLong()
        val modStr = (4 * kotlin.math.abs(front - back)).toString().take(center + 1)
        return buildList {
            for ((index, ch) in modStr.withIndex()) {
                val current = ch - '0'
                for (offset in 0 until 4) add((values.getOrElse(index + offset) { 0 } + current) % 10)
            }
        }
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
        val href = resolveHref(link) ?: return null
        val image = selectFirst(".featured-thumbnail img, .post-image img, .entry-content img, img.wp-post-image")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank)
            ?: image?.attr("src")?.takeIf(String::isNotBlank)
            ?: LOGO_URL
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    private fun Element.toVideoResponse(): SearchResponse? {
        val link = selectFirst("a[href][title]") ?: return null
        val title = link.attr("title").trim().ifBlank { link.text().trim() }.ifBlank { return null }
        val href = resolveHref(link) ?: return null
        val image = selectFirst(".img-holder img, img")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank)?.let(::resolveStaticUrl)
            ?: image?.attr("src")?.takeIf(String::isNotBlank)?.let(::resolveStaticUrl)
            ?: image?.attr("data-webp")?.takeIf(String::isNotBlank)?.let(::resolveStaticUrl)
            ?: LOGO_URL
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    private fun resolveStaticUrl(raw: String): String = when {
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$mainUrl$raw"
        else -> "$mainUrl/$raw"
    }

    private fun resolveHref(link: Element): String? {
        val raw = link.absUrl("href").ifBlank { link.attr("href") }.ifBlank { return null }
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> "$mainUrl/$raw"
        }
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

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("/videos")) {
            "${base.trimEnd('/')}?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page"
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }

    companion object {
        const val ISS3_NAME = "IndianSexStories3"
        const val LOGO_URL = "https://www.indiansexstories3.com/videos/contents/eldjdnoapebd/theme/logo.png"
    }
}

data class StoryDocument(val title: String, val text: String)
