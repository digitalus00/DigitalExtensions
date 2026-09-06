package com.digital.xprimehub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class XPrimeHub : MainAPI() {
    override var mainUrl = "https://xprimehub.pics"
    override var name = "XPrimeHub"
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    private val episodeRegex = Regex("(?i)episodes?\\s*:?\\s*(\\d+)")
    private val seasonRegex = Regex("(?i)season\\s*(\\d+)")

    override val mainPage = mainPageOf(
        mainUrl to "Latest",
        "$mainUrl/by-genres/brazzers/" to "Brazzers",
        "$mainUrl/english/" to "English",
        "$mainUrl/sexmex/" to "SexMex",
        "$mainUrl/niksindian/" to "Niks Indian",
        "$mainUrl/korean/" to "Korean",
        "$mainUrl/russian/" to "Russian",
        "$mainUrl/tagalog/" to "Tagalog",
        "$mainUrl/onlyfans/" to "OnlyFans",
        "$mainUrl/by-quality/1080p/" to "1080p",
        "$mainUrl/by-quality/720p/" to "720p",
        "$mainUrl/by-quality/480p/" to "480p",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = headers, referer = mainUrl).document
        val items = parseGrid(doc)
        val hasNext = doc.selectFirst("a.next.page-numbers, a.page-btn.next-btn, link[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val body = runCatching {
            app.get("$mainUrl/search.php?q=$q&page=$page", headers = headers, referer = mainUrl).text
        }.getOrNull() ?: return newSearchResponseList(emptyList(), false)
        val result = runCatching { parseJson<TsSearchResponse>(body) }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)
        val perPage = result.requestParams?.perPage ?: 15
        val items = result.hits.mapNotNull { hit ->
            val doc = hit.document ?: return@mapNotNull null
            val permalink = doc.permalink?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = if (permalink.startsWith("http")) permalink else mainUrl + permalink
            newSearchResult(cleanTitle(doc.postTitle ?: return@mapNotNull null), url, doc.postThumbnail)
        }
        return newSearchResponseList(items, page * perPage < result.found)
    }

    override suspend fun quickSearch(query: String) = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, referer = mainUrl).document
        val title = doc.selectFirst("h1.single-post-title, h1.entry-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img.wp-post-image[src]")?.absUrl("src")
        val plot = doc.selectFirst("p.xp-plot-box")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select(".category-tag, .meta-categories a, .pink-cat-badge")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
        val sections = collectSections(doc)
        val isSeries = title.contains(Regex("(?i)series|season|episode")) && sections.any { it.links.isNotEmpty() }

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val titleSeason = seasonRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()
        // Per-episode links: "G-Direct" and "V-Cloud" mirrors on nexdrive hold one
        // file per episode behind "Episodes: N" headings.
        val episodeLinks = mutableMapOf<Pair<Int, Int>, MutableList<EpisodeLink>>()
        val batchLinks = mutableListOf<EpisodeLink>()
        for (section in sections) {
            val season = seasonRegex.find(section.heading)?.groupValues?.get(1)?.toIntOrNull()
                ?: titleSeason ?: 1
            for ((mirrorLabel, nexdriveUrl) in section.links) {
                val nexDoc = runCatching {
                    app.get(nexdriveUrl, headers = headers, referer = url).document
                }.getOrNull() ?: continue
                when {
                    mirrorLabel.contains(Regex("(?i)batch|zip")) -> {
                        for ((kind, linkUrl) in innerMirrorLinks(nexDoc)) {
                            batchLinks.add(EpisodeLink("${section.heading} ${cleanMirrorLabel(mirrorLabel)}", kind, linkUrl))
                        }
                    }
                    mirrorLabel.contains(Regex("(?i)g-direct|instant|v-cloud|resumable")) -> {
                        for ((episode, kind, linkUrl) in episodeMirrorLinks(nexDoc)) {
                            episodeLinks.getOrPut(season to episode) { mutableListOf() }
                                .add(EpisodeLink(section.heading, kind, linkUrl))
                        }
                    }
                }
            }
        }

        val episodes = mutableListOf<Episode>()
        if (episodeLinks.isNotEmpty()) {
            for ((key, links) in episodeLinks.toSortedMap(compareBy({ it.first }, { it.second }))) {
                val (season, episode) = key
                episodes.add(
                    newEpisode(links.distinctBy { it.u }.toJson()) {
                        name = "Episode $episode"
                        this.season = season
                        this.episode = episode
                    }
                )
            }
            if (batchLinks.isNotEmpty()) {
                episodes.add(
                    newEpisode(batchLinks.distinctBy { it.u }.toJson()) {
                        name = "Complete Pack"
                        this.season = titleSeason ?: 1
                        this.episode = 0
                    }
                )
            }
        } else {
            // Nexdrive pages expose no per-episode split: offer one entry per quality.
            for (section in sections) {
                if (section.links.isEmpty()) continue
                val season = seasonRegex.find(section.heading)?.groupValues?.get(1)?.toIntOrNull()
                    ?: titleSeason ?: 1
                val payload = section.links.map { (label, nexdriveUrl) ->
                    EpisodeLink("${section.heading} ${cleanMirrorLabel(label)}", "nexdrive", nexdriveUrl)
                }
                episodes.add(
                    newEpisode(payload.toJson()) {
                        name = section.heading
                        this.season = season
                        this.episode = episodes.size + 1
                    }
                )
            }
        }
        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entries = if (data.startsWith("http")) {
            null
        } else {
            runCatching { parseJson<List<EpisodeLink>>(data) }.getOrNull()
        }
        return if (entries != null) {
            var found = false
            for (entry in entries) {
                when (entry.t) {
                    "fastdl" -> if (resolveFastdl(entry.u, buildLabel(entry.q, "G-Direct"), mainUrl, callback)) found = true
                    "vcloud" -> if (resolveVcloud(entry.u, buildLabel(entry.q, "V-Cloud"), callback)) found = true
                    "nexdrive" -> if (resolveNexdrivePage(entry.u, entry.q, callback, subtitleCallback)) found = true
                }
            }
            found
        } else {
            val doc = app.get(data, headers = headers, referer = mainUrl).document
            var found = false
            for (section in collectSections(doc)) {
                for ((_, nexdriveUrl) in section.links) {
                    if (resolveNexdrivePage(nexdriveUrl, section.heading, callback, subtitleCallback)) found = true
                }
            }
            found
        }
    }

    // G-Direct (instant) links are wrapped by fastdl.zip; the embed page carries a
    // redirect whose "link" query parameter is the direct Google CDN file url.
    private suspend fun resolveFastdl(
        embedUrl: String,
        label: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(embedUrl, headers = headers, referer = pageReferer).text
        }.getOrNull() ?: return false
        val reurl = Regex("var reurl\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return false
        val direct = if (reurl.contains("link=")) {
            reurl.substringAfter("link=")
        } else {
            runCatching {
                app.get(reurl, headers = headers, referer = embedUrl).text
            }.getOrNull()?.let { dlHtml ->
                Regex("href=\"(https?://[^\"]+googleusercontent\\.com[^\"]+)\"")
                    .find(dlHtml)?.groupValues?.get(1)
            }
        }
        if (direct.isNullOrBlank() || !direct.startsWith("http")) return false
        callback(
            newExtractorLink(name, label, direct, ExtractorLinkType.VIDEO) {
                referer = "https://fastdl.zip/"
            }
        )
        return true
    }

    // V-Cloud (resumable) links hide a one-time token url behind double base64.
    private suspend fun resolveVcloud(
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(pageUrl, headers = headers, referer = mainUrl).text
        }.getOrNull() ?: return false
        val tokenUrl = Regex("atob\\(atob\\('([^']+)'\\)\\)").find(html)?.groupValues?.get(1)?.let { b64 ->
            runCatching {
                String(Base64.getDecoder().decode(String(Base64.getDecoder().decode(b64))))
            }.getOrNull()?.trim()
        } ?: return false
        if (!tokenUrl.startsWith("http")) return false
        val tokenHtml = runCatching {
            app.get(tokenUrl, headers = headers, referer = pageUrl).text
        }.getOrNull() ?: return false
        val final = Regex("var url\\s*=\\s*'([^']+)'").find(tokenHtml)?.groupValues?.get(1)?.trim()
        if (final.isNullOrBlank() || !final.startsWith("http")) return false
        callback(
            newExtractorLink(name, label, final.replace(" ", "%20"), ExtractorLinkType.VIDEO) {
                referer = pageUrl
            }
        )
        return true
    }

    // Resolves every usable mirror inside a nexdrive page.
    private suspend fun resolveNexdrivePage(
        nexdriveUrl: String,
        heading: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val nexDoc = runCatching {
            app.get(nexdriveUrl, headers = headers, referer = mainUrl).document
        }.getOrNull() ?: return false
        var found = false
        var episodeHeading = ""
        for (el in nexDoc.select("h1, h2, h3, h4, h5, h6, a[href]")) {
            if (el.tagName().startsWith("h")) {
                val text = el.text().trim()
                if (text.isNotBlank()) episodeHeading = text
                continue
            }
            val href = el.absUrl("href").takeIf { it.startsWith("http") } ?: continue
            val text = el.text().trim()
            val episode = episodeRegex.find(episodeHeading)?.groupValues?.get(1)
            val labelHeading = if (episode != null) "$heading E$episode" else heading
            when {
                href.contains("fastdl.zip") -> {
                    if (resolveFastdl(href, buildLabel(labelHeading, "G-Direct"), nexdriveUrl, callback)) found = true
                }
                href.contains("vcloud.fit") -> {
                    if (resolveVcloud(href, buildLabel(labelHeading, "V-Cloud"), callback)) found = true
                }
                href.contains(Regex("\\.(mp4|mkv|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)) -> {
                    callback(
                        newExtractorLink(name, buildLabel(labelHeading, "Direct"), href, ExtractorLinkType.VIDEO) {
                            referer = nexdriveUrl
                        }
                    )
                    found = true
                }
                else -> {
                    val promo = text.contains(Regex("(?i)telegram|join|group|official")) ||
                        href.contains(Regex("(?i)vglist|vegamovies-apk|gokuhd|rogmovies|xprimehub|vegamovies\\.futbol"))
                    if (!promo && runCatching {
                            loadExtractor(href, nexdriveUrl, subtitleCallback, callback)
                        }.getOrDefault(false)
                    ) found = true
                }
            }
        }
        return found
    }

    // Nexdrive pages for series group one mirror link per episode under
    // "Episodes: N" headings.
    private fun episodeMirrorLinks(nexDoc: Document): List<Triple<Int, String, String>> {
        val out = mutableListOf<Triple<Int, String, String>>()
        var episodeHeading = ""
        for (el in nexDoc.select("h1, h2, h3, h4, h5, h6, a[href]")) {
            if (el.tagName().startsWith("h")) {
                val text = el.text().trim()
                if (text.isNotBlank()) episodeHeading = text
                continue
            }
            val href = el.absUrl("href").takeIf { it.startsWith("http") } ?: continue
            val episode = episodeRegex.find(episodeHeading)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val kind = when {
                href.contains("fastdl.zip") -> "fastdl"
                href.contains("vcloud.fit") -> "vcloud"
                else -> null
            } ?: continue
            if (out.none { it.first == episode && it.third == href }) {
                out.add(Triple(episode, kind, href))
            }
        }
        return out
    }

    private fun innerMirrorLinks(nexDoc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in nexDoc.select("a[href]")) {
            val href = a.absUrl("href").takeIf { it.startsWith("http") } ?: continue
            val kind = when {
                href.contains("fastdl.zip") -> "fastdl"
                href.contains("vcloud.fit") -> "vcloud"
                else -> null
            } ?: continue
            if (out.none { it.second == href }) out.add(kind to href)
        }
        return out
    }

    // Collects quality sections: a heading followed by one or more nexdrive
    // mirror links (G-Direct / V-Cloud / Batch/Zip / V-Drive).
    private fun collectSections(doc: Document): List<Section> {
        val container = doc.selectFirst("article.single-entry-body, main.page-body, .entry-content, article") ?: doc
        val sections = mutableListOf<Section>()
        var current: Section? = null
        for (el in container.select("h2, h3, h4, h5, h6, a[href]")) {
            if (el.tagName().startsWith("h")) {
                val text = el.text().trim()
                if (text.isNotBlank()) {
                    current = Section(text, mutableListOf())
                    sections.add(current)
                }
                continue
            }
            val href = el.absUrl("href")
            if (href.contains("nexdrive.")) {
                val section = current ?: Section("Download", mutableListOf()).also { sections.add(it) }
                if (section.links.none { it.second == href }) {
                    section.links.add(el.text().trim() to href)
                }
            }
        }
        return sections.filter { it.links.isNotEmpty() }
    }

    private fun parseGrid(doc: Document): List<SearchResponse> {
        val articles = doc.select("article").mapNotNull { it.toArticleResult() }
        if (articles.isNotEmpty()) return articles.distinctBy { it.url }
        return doc.select(".movies-grid a[href], .vm3-main-grid a[href]").mapNotNull { it.toCardResult() }
            .distinctBy { it.url }
    }

    private fun Element.toCardResult(): SearchResponse? {
        val href = absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return null
        val title = cleanTitle(selectFirst("p.poster-title")?.text() ?: return null)
        val poster = selectFirst("img")?.let { img ->
            img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("data-src").takeIf { it.isNotBlank() }
        }
        return newSearchResult(title, href, poster)
    }

    private fun Element.toArticleResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title a[href], h3.entry-title a[href]") ?: return null
        val href = anchor.absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return null
        val title = cleanTitle(selectFirst("h2.entry-title, h3.entry-title")?.text() ?: anchor.text())
        val poster = selectFirst("img")?.let { img ->
            img.absUrl("src").takeIf { it.isNotBlank() } ?: img.attr("data-src").takeIf { it.isNotBlank() }
        }
        return newSearchResult(title, href, poster)
    }

    private fun newSearchResult(title: String, url: String, poster: String?): SearchResponse {
        val clean = title.ifBlank { "Untitled" }
        return if (clean.contains(Regex("(?i)series|season|episode"))) {
            newTvSeriesSearchResponse(clean, url, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(clean, url, TvType.NSFW) { posterUrl = poster }
        }
    }

    private fun cleanTitle(raw: String): String = raw.trim()
        .removePrefix("Download*")
        .removePrefix("Download")
        .replace(Regex("(?i)\\s*[|–-]\\s*(XPrimeHub|VegaMovies)(\\.pics|\\.is|\\.tw)?\\s*$"), "")
        .trim()

    private fun cleanMirrorLabel(label: String): String =
        label.replace(Regex("[^A-Za-z0-9\\[\\]/.%-]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun buildLabel(heading: String, host: String): String {
        val quality = Regex("(\\d{3,4}p)").find(heading)?.groupValues?.get(1)
        val size = Regex("([\\d.]+\\s*(?:GB|MB)(?:\\s*/\\s*E)?)", RegexOption.IGNORE_CASE).find(heading)
            ?.groupValues?.get(1)?.replace(Regex("\\s+"), "")
        val season = seasonRegex.find(heading)?.groupValues?.get(1)
        val episode = episodeRegex.find(heading)?.groupValues?.get(1)
        val parts = mutableListOf<String>()
        if (heading.contains("batch", true)) parts.add("Batch")
        season?.let { parts.add("S$it") }
        episode?.let { parts.add("E$it") }
        quality?.let { parts.add(it) }
        size?.let { parts.add(it) }
        if (parts.isEmpty()) parts.add(heading.trim().take(30).ifBlank { "Download" })
        parts.add(host)
        return parts.joinToString(" • ")
    }

    data class Section(
        val heading: String,
        val links: MutableList<Pair<String, String>>
    )

    data class EpisodeLink(
        @JsonProperty("q") val q: String = "",
        @JsonProperty("t") val t: String = "",
        @JsonProperty("u") val u: String = ""
    )

    data class TsSearchResponse(
        @JsonProperty("found") val found: Int = 0,
        @JsonProperty("hits") val hits: List<TsHit> = emptyList(),
        @JsonProperty("request_params") val requestParams: TsRequestParams? = null
    )

    data class TsRequestParams(
        @JsonProperty("per_page") val perPage: Int = 15
    )

    data class TsHit(
        @JsonProperty("document") val document: TsDocument? = null
    )

    data class TsDocument(
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("post_thumbnail") val postThumbnail: String? = null
    )
}
