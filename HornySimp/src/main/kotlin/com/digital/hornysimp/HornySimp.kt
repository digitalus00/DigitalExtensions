package com.digital.hornysimp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HornySimp : MainAPI() {
    data class VidaraStream(val streaming_url: String? = null, val subtitles: List<VidaraSubtitle>? = null)
    data class VidaraSubtitle(val file: String? = null, val label: String? = null)

    override var mainUrl = "https://hornysimp.com"
    override var name = "HornySimp"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        mainUrl to "Latest Videos",
        "$mainUrl/leaked-clips/" to "Leaked Clips",
        "$mainUrl/hd-porns/" to "HD Porn",
        "$mainUrl/jav/" to "JAV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pagedUrl(request.data, page), referer = "$mainUrl/").document
        val items = doc.select(".pt-cv-content-item").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = hasNext(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select(".pt-cv-content-item, article.ilovewp-post").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newSearchResponseList(items, hasNext = hasNext(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.hscp-main-title, h1.entry-title")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - HornySimp")?.trim()
            ?: throw ErrorLoadingException("HornySimp title was not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf(String::isNotBlank)
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        val tags = doc.select(".hscp-model-name, a[rel=tag], a[rel=category]").map { it.text().trim() }.filter { it.isNotBlank() && !it.equals("Unknown Model", true) }.distinct()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster; this.plot = plot; this.tags = tags }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val links = doc.select(".hscp-video-container iframe[src], article iframe[src], article video[src], article source[src]")
            .map { it.absUrl("src").ifBlank { fixUrl(it.attr("src")) } }.filter { it.startsWith("http") }.distinct()
        var found = false
        links.forEach { link ->
            if (link.contains(Regex("\\.(m3u8|mp4)(\\?|$)", RegexOption.IGNORE_CASE))) {
                callback(newExtractorLink(name, name, link, if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = data })
                found = true
            } else if (link.contains("vidara.to/", true) && loadVidara(link, data, subtitleCallback, callback)) {
                found = true
            } else if (link.contains("hrnyvid.xyz/", true) && loadLuluStream(link, data, callback)) {
                found = true
            } else if (loadExtractor(link, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun loadVidara(embedUrl: String, pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = Regex("^(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return false
        val fileCode = embedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank) ?: return false
        val stream = runCatching {
            app.post("$origin/api/stream", json = mapOf("filecode" to fileCode, "device" to "android"), referer = pageUrl).parsed<VidaraStream>()
        }.getOrNull() ?: return false
        val streamUrl = stream.streaming_url?.takeIf { it.startsWith("http") } ?: return false
        stream.subtitles.orEmpty().forEach { subtitle ->
            subtitle.file?.takeIf { it.startsWith("http") }?.let { subtitleCallback(newSubtitleFile(subtitle.label ?: "Unknown", it)) }
        }
        callback(newExtractorLink(name, "Vidara", streamUrl, ExtractorLinkType.M3U8) { referer = embedUrl })
        return true
    }

    private suspend fun loadLuluStream(embedUrl: String, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching { app.get(embedUrl, referer = pageUrl).text }.getOrNull() ?: return false
        val packed = Regex("eval\\(function\\(p,a,c,k,e,[rd]\\).*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.value?.substringBefore("</script>") ?: return false
        val unpacked = runCatching { JsUnpacker(packed).unpack() }.getOrNull() ?: return false
        val streamUrl = Regex("https?://[^\\s\\\"']+?\\.m3u8(?:\\?[^\\s\\\"']*)?", RegexOption.IGNORE_CASE)
            .find(unpacked.replace("\\/", "/"))?.value ?: return false
        callback(newExtractorLink(name, "LuluStream", streamUrl, ExtractorLinkType.M3U8) { referer = embedUrl })
        return true
    }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst(".pt-cv-title a[href], .title-post a[href], a.pt-cv-href-thumbnail[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }.takeIf { it.startsWith(mainUrl) } ?: return null
        val title = selectFirst(".pt-cv-title, .title-post")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").trim().takeIf(String::isNotBlank) ?: return null
        val image = selectFirst("img.pt-cv-thumbnail, img")
        val poster = image?.attr("data-src")?.takeIf(String::isNotBlank) ?: image?.attr("src")?.takeIf(String::isNotBlank)
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster?.let(::fixUrl) }
    }

    private fun pagedUrl(base: String, page: Int) = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
    private fun hasNext(doc: Document, page: Int) = doc.selectFirst("link[rel=next], a.next.page-numbers, .pagination a.next") != null ||
        doc.select(".pagination a, a.page-numbers").any { (it.text().toIntOrNull() ?: 0) > page }
}
