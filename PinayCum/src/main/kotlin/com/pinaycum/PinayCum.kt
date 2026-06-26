package com.pinaycum

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PinayCum : MainAPI() {
    override var mainUrl = "https://pinaycumvid.xyz"
    override var name = "PinayCum"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "tl"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
    )

    // ... (getMainPage, search, toSearchResult, load functions remain the same as previous version)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("h4, h1, title")?.text()?.trim() ?: "Pinay Video"

        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div#preroll-overlay")?.attr("style")?.let {
                Regex("url\\([\"']?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
            }

        if (poster != null) poster = fixUrl(poster)

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        val recommendations = document.select("a[href*='watch.php?id=']").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        // === 1. Player Buttons (Vidara, Lulustream, etc.) ===
        document.select("a.btn-dark[href*='&s=']").forEach { btn ->
            val playerUrl = fixUrl(btn.attr("href"))
            val playerName = btn.text().trim()

            try {
                val playerDoc = app.get(playerUrl, referer = data).document

                // Try iframe
                playerDoc.selectFirst("iframe")?.attr("src")?.let { iframe ->
                    loadExtractor(iframe, playerUrl, subtitleCallback, callback)
                    found = true
                }

                // Direct sources if any
                playerDoc.select("source[src], video[src], a[href*='.mp4'], a[href*='.m3u8']").forEach { el ->
                    val src = fixUrlNull(el.attr("src") ?: el.attr("href"))
                    if (src != null) {
                        callback(ExtractorLink(
                            source = name,
                            name = playerName,
                            url = src,
                            referer = playerUrl,
                            quality = Qualities.Unknown.value
                        ))
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        // === 2. Direct Vidaara Embed (Most Important) ===
        Regex("""https?://vidaarax\.net/e/[\w-]+""").find(document.toString())?.value?.let { vidaaraUrl ->
            loadExtractor(vidaaraUrl, data, subtitleCallback, callback)
            found = true
        }

        return found
    }
}