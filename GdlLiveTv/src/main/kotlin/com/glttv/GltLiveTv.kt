// ════════════════════════════════════════════════════════════════════════════
// PACKAGE DECLARATION
// ────────────────────────────────────────────────────────────────────────────
// The package name must match your folder structure.
// com.glttv → folder: com/glttv/
// ════════════════════════════════════════════════════════════════════════════
package com.glttv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

// ════════════════════════════════════════════════════════════════════════════
// THE PROVIDER CLASS
// ────────────────────────────────────────────────────────────────────────────
// MainAPI is the base class every CloudStream provider extends.
// Think of it like a "blueprint" that forces you to fill in:
//   - what the site is (mainUrl, name, lang)
//   - what it can do (hasMainPage, hasSearch, supportedTypes)
//   - how to do it (getMainPage, search, load, loadLinks)
// ════════════════════════════════════════════════════════════════════════════
class GdlTvProvider : MainAPI() {

    // ── Site Identity ─────────────────────────────────────────────────────
    // These tell CloudStream basic facts about your provider.
    override var mainUrl = "https://gdltv.live"
    override var name    = "GDL TV"
    override var lang    = "en"

    // ── Capabilities ──────────────────────────────────────────────────────
    // These are simple true/false flags. Set them to match what the site supports.
    override val hasMainPage       = true    // Show a home screen with rows
    override val hasDownloadSupport = false   // Live TV can't be "downloaded"
    override val hasQuickSearch    = false    // No instant search overlay needed

    // ── Content Type ──────────────────────────────────────────────────────
    // TvType.Live = live TV channels (like a TV guide)
    // This is the KEY difference from GdlCinema which used Movie + TvSeries.
    // CloudStream uses this to show the right UI (e.g., no episode list for Live).
    override val supportedTypes = setOf(TvType.Live)

    // ════════════════════════════════════════════════════════════════════════
    // HOME PAGE ROWS
    // ────────────────────────────────────────────────────────────────────────
    // mainPageOf() takes pairs of "URL" to "Row Title".
    // When the user scrolls to a row, getMainPage() is called with that pair.
    //
    // HOW TO FIND THESE URLs:
    //   1. Open gdltv.live in your browser
    //   2. Open DevTools (F12) → Network tab
    //   3. Look for requests to /api/... or /channels/...
    //   4. Those are your category endpoints
    //
    // These are GUESSED patterns — adjust after inspecting the real site.
    // ════════════════════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "$mainUrl/api/channels?category=news"         to "📺 News",
        "$mainUrl/api/channels?category=sports"       to "⚽ Sports",
        "$mainUrl/api/channels?category=entertainment" to "🎬 Entertainment",
        "$mainUrl/api/channels?category=movies"       to "🎥 Movies",
        "$mainUrl/api/channels?category=music"        to "🎵 Music",
        "$mainUrl/api/channels?category=kids"         to "👧 Kids",
        "$mainUrl/api/channels?category=documentary"  to "📰 Documentary",
        "$mainUrl/api/channels"                       to "🌐 All Channels"
    )

    // ════════════════════════════════════════════════════════════════════════
    // CONFIG CACHING
    // ────────────────────────────────────────────────────────────────────────
    // If the site needs an API token or worker URLs (like GdlCinema did),
    // we cache them here so we don't re-fetch on every request.
    // ════════════════════════════════════════════════════════════════════════
    private var apiToken: String = ""

    // ════════════════════════════════════════════════════════════════════════
    // getMainPage() — Fills each home screen row
    // ────────────────────────────────────────────────────────────────────────
    // Called when:
    //   - App opens (page 1 of each section)
    //   - User scrolls to load more (page 2, 3, ...)
    //
    // Parameters:
    //   page    = which page of results (1 = first load)
    //   request = contains request.data (the URL) and request.name (the title)
    //
    // Returns: HomePageResponse — a list of HomePageList rows
    // ════════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Step 1: Make sure we have config (token, etc.)
        ensureConfig()

        // Step 2: Fetch the channel list from the API
        // app.get() is CloudStream's HTTP client — use it for all requests.
        // It handles cookies, user agents, etc. automatically.
        val channelList: TvChannelList? = safeAsync<TvChannelList?> {
            app.get(
                request.data,                        // The URL from mainPageOf()
                headers = apiHeaders(),              // Auth headers if needed
                params  = mapOf("page" to page.toString())
            ).parsedSafe<TvChannelList>()            // Parse JSON → data class
        }

        // Step 3: Convert each channel to a SearchResponse
        // SearchResponse is what CloudStream uses to display cards in the UI.
        // For live TV, we use newLiveSearchResponse().
        val results = channelList?.channels.orEmpty().map { channel ->
            channel.toSearchResponse()
        }

        // Step 4: Wrap in HomePageResponse
        // hasNext = true means "there might be more to load on scroll"
        return newHomePageResponse(
            list    = arrayListOf(HomePageList(request.name, results)),
            hasNext = results.size >= 20
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // search() — Handles the search bar
    // ────────────────────────────────────────────────────────────────────────
    // Called when the user types in the search box.
    // Returns a flat list of SearchResponse items.
    // ════════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()

        // Search the site's API for matching channels
        val results: TvChannelList? = safeAsync<TvChannelList?> {
            app.get(
                "$mainUrl/api/channels/search",
                headers = apiHeaders(),
                params  = mapOf("q" to query)      // Most APIs use ?q=searchterm
            ).parsedSafe<TvChannelList>()
        }

        return results?.channels.orEmpty().map { it.toSearchResponse() }
    }

    // ════════════════════════════════════════════════════════════════════════
    // load() — The Detail Page
    // ────────────────────────────────────────────────────────────────────────
    // Called when the user TAPS on a channel card.
    // The `url` parameter is whatever href you set in toSearchResponse().
    //
    // Your job here:
    //   1. Parse the URL to get the channel ID
    //   2. Fetch full channel details (description, logo, stream info)
    //   3. Return a LoadResponse with all that info
    //
    // For live TV, there are NO seasons or episodes.
    // The dataUrl you pass becomes the `data` parameter in loadLinks().
    // ════════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        ensureConfig()

        // Step 1: Get channel ID from URL
        // The URL we set looks like: /channel?id=cnn-news&name=CNN+News
        val params    = parseQueryParams(url)
        val channelId = params["id"]   ?: return null
        val name      = params["name"] ?: channelId

        // Step 2: Fetch channel details from the API
        val detail: TvChannelDetail? = safeAsync<TvChannelDetail?> {
            app.get(
                "$mainUrl/api/channel/$channelId",
                headers = apiHeaders()
            ).parsedSafe<TvChannelDetail>()
        }

        val channelName  = detail?.name      ?: name
        val logo         = detail?.logo
        val description  = detail?.description
        val category     = detail?.category

        // Step 3: Build the stream data URL
        // This is what gets passed to loadLinks() later.
        // We encode the channel ID so loadLinks() knows what to fetch.
        val dataUrl = "$mainUrl/stream?id=$channelId"

        // Step 4: Return a LiveStreamLoadResponse
        // newLiveStreamLoadResponse() is the right builder for TvType.Live.
        // It creates a detail page with just: logo, name, description, and a "Watch" button.
        return newLiveStreamLoadResponse(
            name    = channelName,
            url     = url,          // Original URL (used for browser/share)
            dataUrl = dataUrl       // What loadLinks() will receive as `data`
        ) {
            this.posterUrl = logo
            this.plot      = description
            this.tags      = listOfNotNull(category)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // loadLinks() — Gets the Actual Video Stream
    // ────────────────────────────────────────────────────────────────────────
    // Called when the user presses "Watch" / "Play".
    //
    // Parameters:
    //   data              = the dataUrl you set in load()
    //   isCasting         = true if casting to Chromecast/etc.
    //   subtitleCallback  = call this to add subtitle tracks
    //   callback          = call this with each stream link
    //
    // You must call callback() at least once for video to play.
    // You can call it multiple times for multiple quality options.
    //
    // Returns: true if we tried (even if no streams found), false on hard failure
    // ════════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        val params    = parseQueryParams(data)
        val channelId = params["id"] ?: return false

        // ── Strategy 1: Ask our own backend for a stream URL ──────────────
        // This mirrors how GdlCinema called its workers.
        // The backend returns a proxied URL so geo-blocks don't apply.
        val streamData: TvStreamResponse? = safeAsync<TvStreamResponse?> {
            app.get(
                "$mainUrl/api/stream/$channelId",
                headers = apiHeaders()
            ).parsedSafe<TvStreamResponse>()
        }

        if (streamData?.ok == true) {
            streamData.streams.forEach { stream ->
                val streamUrl = stream.proxiedUrl ?: stream.originalUrl ?: return@forEach
                val isM3u8    = streamUrl.contains(".m3u8") || streamUrl.contains("m3u8")

                if (isM3u8) {
                    // M3u8Helper.generateM3u8() reads the playlist and creates
                    // an ExtractorLink for each quality level (720p, 1080p, etc.)
                    safeAsync<Unit> {
                        M3u8Helper.generateM3u8(
                            source  = stream.name ?: "Server",
                            streamUrl = streamUrl,
                            referer   = mainUrl,
                            headers   = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer"    to mainUrl,
                                "Origin"     to mainUrl
                            )
                        ).forEach(callback)
                    }
                } else {
                    // For non-HLS streams (plain MP4, etc.), create the link directly.
                    //
                    // WHY newExtractorLink() instead of ExtractorLink()?
                    //   CloudStream updated its API. The old constructor is deprecated
                    //   and causes a compile error. The new pattern is:
                    //     newExtractorLink(required params) { optional params in lambda }
                    //
                    // ExtractorLinkType tells the player what kind of stream this is:
                    //   M3U8  = HLS playlist (.m3u8)  — handled by M3u8Helper instead
                    //   DASH  = MPEG-DASH (.mpd)
                    //   VIDEO = plain video file (mp4, mkv, etc.)
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name   = stream.name ?: "Direct Stream",
                            url    = streamUrl,
                            type   = ExtractorLinkType.VIDEO   // plain video, not HLS
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // ── Strategy 2: Try direct M3U playlist from the site ─────────────
        // Many live TV sites expose a direct .m3u8 or .m3u URL per channel.
        // We try fetching it as a fallback.
        safeAsync<Unit> {
            val directUrl = "$mainUrl/live/$channelId/index.m3u8"
            M3u8Helper.generateM3u8(
                source    = "Direct",
                streamUrl = directUrl,
                referer   = mainUrl
            ).forEach(callback)
        }

        return true
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPER: ensureConfig()
    // ────────────────────────────────────────────────────────────────────────
    // Fetches the site's config once and caches it.
    // The `if` guard at the top makes subsequent calls instant (no network).
    // ════════════════════════════════════════════════════════════════════════
    private suspend fun ensureConfig() {
        if (apiToken.isNotBlank()) return   // Already loaded, skip

        val config: TvSiteConfig? = safeAsync<TvSiteConfig?> {
            app.get("$mainUrl/api/config").parsedSafe<TvSiteConfig>()
        }
        apiToken = config?.apiToken ?: ""
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPER: apiHeaders()
    // ────────────────────────────────────────────────────────────────────────
    // Returns auth headers. Many sites require a Bearer token.
    // If this site doesn't need auth, just return emptyMap().
    // ════════════════════════════════════════════════════════════════════════
    private fun apiHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Accept"     to "application/json",
            "Referer"    to mainUrl,
            "Origin"     to mainUrl
        )
        if (apiToken.isNotBlank()) {
            headers["Authorization"] = "Bearer $apiToken"
        }
        return headers
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPER: parseQueryParams()
    // ────────────────────────────────────────────────────────────────────────
    // Turns a URL like "/watch?type=tv&id=123" into a Map:
    //   {"type" → "tv", "id" → "123"}
    //
    // We use java.net.URI to parse it safely.
    // The trick: if the URL is relative (starts with /), we prepend a fake host
    // so URI can parse it without throwing.
    // ════════════════════════════════════════════════════════════════════════
    private fun parseQueryParams(url: String): Map<String, String> {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "https://host$url"
            val query   = URI(fullUrl).query ?: return emptyMap()
            query.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else pair.substring(0, idx) to pair.substring(idx + 1)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // EXTENSION FUNCTION: TvChannel.toSearchResponse()
    // ────────────────────────────────────────────────────────────────────────
    // Converts our data class → CloudStream's SearchResponse.
    //
    // WHY AN EXTENSION FUNCTION?
    //   It keeps the conversion logic inside the class that needs it,
    //   without cluttering the data class itself.
    //
    // newLiveSearchResponse() creates a card with:
    //   - Channel name
    //   - Logo image
    //   - "LIVE" badge
    //
    // The `href` (2nd param) is what becomes `url` in load().
    // We encode channel info into it so load() can use it without re-fetching.
    // ════════════════════════════════════════════════════════════════════════
    private fun TvChannel.toSearchResponse(): LiveSearchResponse {
        val href = "/channel?id=${this.id}&name=${this.name?.replace(" ", "+") ?: ""}"
        return newLiveSearchResponse(
            name       = this.name ?: "Unknown Channel",
            url        = href,
            type       = TvType.Live
        ) {
            this.posterUrl = this@toSearchResponse.logo
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DATA MODELS
    // ────────────────────────────────────────────────────────────────────────
    // These Kotlin data classes mirror the JSON the site returns.
    //
    // HOW TO FIGURE OUT THE REAL STRUCTURE:
    //   1. Open gdltv.live in browser → DevTools → Network
    //   2. Find an API call to /api/channels or similar
    //   3. Look at the Response tab
    //   4. Map each JSON field to a @JsonProperty here
    //
    // @JsonProperty("snake_case") maps JSON field names to Kotlin camelCase.
    // All fields are nullable (?) with defaults so missing fields don't crash.
    // ════════════════════════════════════════════════════════════════════════

    // Response from /api/config
    data class TvSiteConfig(
        @JsonProperty("apiToken")  val apiToken: String?       = null,
        @JsonProperty("version")   val version: String?        = null,
        @JsonProperty("baseUrl")   val baseUrl: String?        = null
    )

    // A single channel (in a list response)
    data class TvChannel(
        @JsonProperty("id")          val id: String?        = null,
        @JsonProperty("name")        val name: String?      = null,
        @JsonProperty("logo")        val logo: String?      = null,
        @JsonProperty("category")    val category: String?  = null,
        @JsonProperty("country")     val country: String?   = null,
        @JsonProperty("language")    val language: String?  = null,
        @JsonProperty("is_live")     val isLive: Boolean    = true,
        @JsonProperty("stream_url")  val streamUrl: String? = null   // Sometimes included directly
    )

    // Response from /api/channels (list of channels)
    data class TvChannelList(
        @JsonProperty("channels")    val channels: List<TvChannel> = emptyList(),
        @JsonProperty("total")       val total: Int                = 0,
        @JsonProperty("page")        val page: Int                 = 1,
        @JsonProperty("total_pages") val totalPages: Int           = 0
    )

    // Response from /api/channel/{id} (single channel detail)
    data class TvChannelDetail(
        @JsonProperty("id")          val id: String?        = null,
        @JsonProperty("name")        val name: String?      = null,
        @JsonProperty("logo")        val logo: String?      = null,
        @JsonProperty("category")    val category: String?  = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("country")     val country: String?   = null,
        @JsonProperty("language")    val language: String?  = null,
        @JsonProperty("stream_url")  val streamUrl: String? = null,
        @JsonProperty("streams")     val streams: List<TvStream> = emptyList()
    )

    // A single stream option (one channel may have multiple quality/mirror options)
    data class TvStream(
        @JsonProperty("name")         val name: String?    = null,
        @JsonProperty("url")          val url: String?     = null,
        @JsonProperty("quality")      val quality: String? = null,  // "720p", "1080p", etc.
        @JsonProperty("is_m3u8")      val isM3u8: Boolean  = true
    )

    // Response from /api/stream/{id} — mirrors GdlCinema's WorkerResponse
    data class TvStreamResponse(
        @JsonProperty("ok")      val ok: Boolean              = false,
        @JsonProperty("name")    val name: String?            = null,
        @JsonProperty("streams") val streams: List<TvWorkerStream> = emptyList()
    )

    // A proxied stream from the worker backend
    data class TvWorkerStream(
        @JsonProperty("name")         val name: String?   = null,
        @JsonProperty("original_url") val originalUrl: String? = null,
        @JsonProperty("proxied_url")  val proxiedUrl: String?  = null,
        @JsonProperty("quality")      val quality: Int?   = null    // In pixels (720, 1080)
    )
}