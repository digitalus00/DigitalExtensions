package com.digital.aagmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Aagmaal : MainAPI() {
    override var mainUrl = "https://aagmaal.com"
    override var name = "Aagmaal"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
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
        val doc = app.get(url, headers = headers, referer = "$mainUrl/").document
        return newHomePageResponse(request.name, parseCards(doc), hasNext = hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = app.get(url, headers = headers, referer = "$mainUrl/").document
        return newSearchResponseList(parseCards(doc), hasNext = hasNextPage(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, referer = "$mainUrl/").document
        val rawTitle = doc.selectFirst("h1 span[itemprop=name]")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: throw ErrorLoadingException("Aagmaal title was not found")
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".entry img[src], article img[src]")?.absUrl("src")?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
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
        val doc = app.get(data, headers = headers, referer = "$mainUrl/").document
        val entry = doc.selectFirst(".entry, .post-listing, article") ?: doc
        val links = mutableSetOf<String>()
        entry.select("iframe[src]").forEach { iframe ->
            iframe.attr("src").takeIf { it.startsWith("http") }?.let { links.add(it) }
        }
        entry.select("a[href]").forEach { a ->
            a.absUrl("href").takeIf { it.startsWith("http") }?.let { links.add(it) }
        }
        val promo = Regex("(?i)aagmaal|aagvdo|t\\.me|telegram|ibb\\.co|dmca|contact|comment|twitter|facebook|whatsapp")
        var found = false
        for (link in links) {
            when {
                link.contains(Regex("\\.(mp4|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)) -> {
                    callback(
                        newExtractorLink(
                            name, name, link,
                            if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { referer = data }
                    )
                    found = true
                }
                link.contains("cdn1.site") -> {
                    val embed = link.replace("/d/", "/e/")
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
        val html = runCatching { app.get(embedUrl, headers = headers, referer = "$mainUrl/").text }.getOrNull()
            ?: return false
        val streamUrl = Regex("file:\\s*[\"'](https?://[^\"]+?\\.m3u8[^\"]*)[\"']").find(html)?.groupValues?.get(1)
            ?: Regex("file:\\s*[\"'](https?://[^\"]+?\\.mp4[^\"]*)[\"']").find(html)?.groupValues?.get(1)
            ?: unpackSources(html)
            ?: return false
        callback(
            newExtractorLink(
                name, "LuluStream", streamUrl,
                if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) { referer = embedUrl }
        )
        return true
    }

    private fun unpackSources(html: String): String? {
        val packed = Regex("eval\\(function\\(p,a,c,k,e,[rd]\\).*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.value?.substringBefore("</script>") ?: return null
        val unpacked = runCatching { JsUnpacker(packed).unpack() }.getOrNull() ?: return null
        return Regex("https?://[^\\s\"']+?\\.(?:m3u8|mp4)(?:\\?[^\\s\"']*)?", RegexOption.IGNORE_CASE)
            .find(unpacked.replace("\\/", "/"))?.value
    }

    // Sahifa grid: heading links plus a thumbnail image a few levels above.
    private fun parseCards(doc: Document): List<SearchResponse> {
        return doc.select("h2.post-box-title a[href], h3.post-box-title a[href], h1.post-box-title a[href]")
            .mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val url = if (href.startsWith("http")) href else fixUrl(href)
                if (!url.startsWith(mainUrl) || url.contains(Regex("/(page|category|tag|feed)/"))) return@mapNotNull null
                val title = cleanTitle(anchor.text()).takeIf(String::isNotBlank) ?: return@mapNotNull null
                newMovieSearchResponse(title, url, TvType.NSFW) { posterUrl = findPoster(anchor) }
            }
            .distinctBy { it.url }
    }

    private fun findPoster(anchor: Element): String? {
        var block: Element? = anchor.parent()
        repeat(4) {
            val current = block ?: return null
            val src = current.selectFirst("img[src]")?.let { img ->
                img.absUrl("src").ifBlank { img.attr("src") }.takeIf { it.startsWith("http") }
            }
            if (src != null) return src
            block = current.parent()
        }
        return null
    }

    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("(?i)\\s*[|–-]\\s*(watch\\s+free|watch\\s+online|full\\s+hd)(\\s*[|–-]\\s*aagmaal(\\.com)?)?\\s*$"), "")
        .replace(Regex("(?i)\\s*[|–-]\\s*aagmaal(\\.com)?\\.?$"), "")
        .trim()

    private fun hasNextPage(doc: Document, page: Int) =
        doc.selectFirst("link[rel=next], a.next.page-numbers, .pagination a.next") != null ||
            doc.select(".pagination a, a.page-numbers").any { (it.text().toIntOrNull() ?: 0) > page }
}
