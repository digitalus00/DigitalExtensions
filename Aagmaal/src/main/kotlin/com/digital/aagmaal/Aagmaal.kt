package com.digital.aagmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Aagmaal : MainAPI() {
    override var mainUrl = "https://aagmaal.com"
    override var name = "Aagmaal"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cloudflareKiller = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/category/ullu-app/" to "Ullu",
        "$mainUrl/category/primeshots/" to "PrimeShots",
        "$mainUrl/category/nuefliks/" to "Nuefliks",
        "$mainUrl/category/moodx-tv/" to "MoodX",
        "$mainUrl/category/huntersapp/" to "Hunters",
        "$mainUrl/category/hotx-vip/" to "HotX",
        "$mainUrl/category/uncut-short-films/" to "Short Films",
        "$mainUrl/category/uncutmasala/" to "Uncut Masala",
        "$mainUrl/category/bongonaari/" to "BongNaari",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = getDocument(url)
        return newHomePageResponse(request.name, parseCards(doc), hasNext = hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = getDocument(url)
        return newSearchResponseList(parseCards(doc), hasNext = hasNextPage(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val rawTitle = doc.selectFirst("h1 span[itemprop=name], h1.entry-title, h1.post-title, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: throw ErrorLoadingException("Aagmaal title was not found")
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: findImage(doc.selectFirst(".entry, .entry-content, article, .post-content") ?: doc)
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?:^|[\\s–-])((?:19|20)\\d{2})(?:[\\s–-]|$)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length > 2 }.distinct().take(8)
        val recommendations = parseCards(doc).filter { it.url != url }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Posts embed a LuluStream player (cdn1.site) plus mirror download links
    // from the StreamTape family (strcloud.club, stmix.io, strmup.to...).
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers, referer = "$mainUrl/", interceptor = cloudflareKiller)
        val doc = response.document
        val raw = (response.text + " " + doc.html()).decodeEscapedUrl()
        val entry = doc.selectFirst(".entry, .entry-content, .post-content, .post-listing, article") ?: doc
        val links = linkedSetOf<String>()
        entry.select("iframe[src], video[src], video source[src], a[href]").forEach { element ->
            val attribute = if (element.tagName() == "a") "href" else "src"
            element.attr(attribute).toAbsoluteUrl(data)?.let(links::add)
            listOf("data-src", "data-url", "data-video", "data-embed").forEach { key ->
                element.attr(key).toAbsoluteUrl(data)?.let(links::add)
            }
        }
        directMediaUrls(raw).forEach(links::add)

        val promo = Regex("(?i)aagmaal|aagvdo|t\\.me|telegram|ibb\\.co|dmca|contact|comment|twitter|facebook|whatsapp|instagram")
        var found = false
        for (link in links) {
            when {
                isMediaUrl(link) -> {
                    callback(
                        newExtractorLink(
                            name, name, link,
                            if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { referer = data }
                    )
                    found = true
                }
                isLuluUrl(link) -> {
                    val embed = link.toEmbedUrl()
                    if (resolveLuluStream(embed, callback)) found = true
                }
                promo.containsMatchIn(link) -> {}
                else -> if (runCatching { loadExtractor(link, data, subtitleCallback, callback) }.getOrDefault(false)) found = true
            }
        }
        return found
    }

    // LuluStream embeds run jwplayer; the m3u8 hides in a "sources" object,
    // sometimes behind a packed script.
    private suspend fun resolveLuluStream(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching {
            app.get(embedUrl, headers = headers, referer = "$mainUrl/", interceptor = cloudflareKiller).text
        }.getOrNull()
            ?: return false
        val decoded = html.decodeEscapedUrl()
        val streamUrl = directMediaUrls(decoded).firstOrNull()
            ?: unpackSources(decoded)
            ?: return false
        val normalized = streamUrl.toAbsoluteUrl(embedUrl) ?: return false
        callback(
            newExtractorLink(
                name, "LuluStream", normalized,
                if (isM3u8(normalized)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) { referer = embedUrl }
        )
        return true
    }

    private fun unpackSources(html: String): String? {
        val packed = Regex("eval\\(function\\(p,a,c,k,e,[rd]\\).*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.value?.substringBefore("</script>") ?: return null
        val unpacked = runCatching { JsUnpacker(packed).unpack() }.getOrNull() ?: return null
        return directMediaUrls(unpacked.replace("\\/", "/")).firstOrNull()
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, headers = headers, referer = "$mainUrl/", interceptor = cloudflareKiller)
        if (response.code == 403 || response.document.title().contains("Just a moment", true)) {
            throw ErrorLoadingException("Aagmaal requested Cloudflare verification. Open the site once in WebView and retry.")
        }
        return response.document
    }

    // The site has used several WordPress themes. Find permalink cards by their
    // structure instead of relying on one theme's post-box-title class.
    private fun parseCards(doc: Document): List<SearchResponse> {
        val cards = doc.select("article, .post, .post-box, .item, .blog-post, .td_module_wrap, .blog-entry")
        val anchors = if (cards.isNotEmpty()) cards.flatMap { card ->
            card.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], a.post-title[href], a.entry-title[href], a[title][href]").take(1)
        } else doc.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href]")
        return anchors.mapNotNull { anchor ->
                val url = anchor.attr("href").toAbsoluteUrl(doc.baseUri()) ?: return@mapNotNull null
                if (!url.startsWith(mainUrl) || isNavigationUrl(url)) return@mapNotNull null
                val title = cleanTitle(anchor.attr("title").ifBlank { anchor.text() }).takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                newMovieSearchResponse(title, url, TvType.NSFW) { posterUrl = findPoster(anchor) }
            }
            .distinctBy { it.url }
    }

    private fun findPoster(anchor: Element): String? {
        var block: Element? = anchor.parent()
        repeat(4) {
            val current = block ?: return null
            val src = current.selectFirst("img")?.let { image ->
                listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { key ->
                    image.attr(key).toAbsoluteUrl(image.baseUri())
                }
            }
            if (src != null) return src
            block = current.parent()
        }
        return null
    }

    private fun findImage(element: Element): String? = element.selectFirst("img")?.let { image ->
        listOf("data-src", "data-lazy-src", "data-original", "src").firstNotNullOfOrNull { key ->
            image.attr(key).toAbsoluteUrl(image.baseUri())
        }
    }

    private fun isNavigationUrl(url: String) = url.contains(Regex("/(page|category|tag|feed|author|wp-content|wp-json)(/|$|\\?)")) ||
        url.endsWith("/comments/feed/")

    private fun isLuluUrl(url: String) = url.contains(Regex("(?i)(?:cdn1\\.site|lulu(?:stream|vid)?\\.)"))

    private fun String.toEmbedUrl() = replace(Regex("/(?:(?:d|f|download))/"), "/e/")

    private fun String.toAbsoluteUrl(base: String): String? = trim().trim('"', '\'', '`')
        .takeIf { it.isNotBlank() && !it.startsWith("javascript:") && !it.startsWith("#") }
        ?.let { value ->
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> runCatching { URI(base.ifBlank { mainUrl }).resolve(value).toString() }.getOrNull()
            }
        }
        ?.takeIf { it.startsWith("http") }

    private fun directMediaUrls(raw: String): List<String> = Regex(
        "(?:https?:)?//[^\\s\\\"'<>\\\\]+?\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>\\\\]*)?",
        RegexOption.IGNORE_CASE
    ).findAll(raw)
        .mapNotNull { it.value.toAbsoluteUrl(mainUrl) }
        .distinct()
        .toList()

    private fun isMediaUrl(url: String) = url.contains(Regex("(?i)\\.(?:mp4|m3u8)(?:\\?|$)"))

    private fun isM3u8(url: String) = url.contains(".m3u8", true)

    private fun String.decodeEscapedUrl() = replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("(?i)\\s*[|–-]\\s*(watch\\s+free|watch\\s+online|full\\s+hd)(\\s*[|–-]\\s*aagmaal(\\.com)?)?\\s*$"), "")
        .replace(Regex("(?i)\\s*[|–-]\\s*aagmaal(\\.com)?\\.?$"), "")
        .trim()

    private fun hasNextPage(doc: Document, page: Int) =
        doc.selectFirst("link[rel=next], a.next.page-numbers, .pagination a.next") != null ||
            doc.select(".pagination a, a.page-numbers").any { (it.text().toIntOrNull() ?: 0) > page }
}
