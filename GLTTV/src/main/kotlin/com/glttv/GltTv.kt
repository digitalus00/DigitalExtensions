package com.glttv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URI

class GdlCinemaProvider : MainAPI() {

    override var mainUrl = "https://gdlcinema.site"
    override var name = "GDL Cinema"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImg  = "https://image.tmdb.org/t/p/w500"

    private var tmdbToken: String = ""
    private var workerUrls: List<String> = emptyList()

    override val mainPage = mainPageOf(
        "$mainUrl/trending/movie/week" to "Trending Movies",
        "$mainUrl/trending/tv/week"    to "Trending TV",
        "$mainUrl/movie/now_playing"   to "Now Playing",
        "$mainUrl/movie/popular"       to "Popular Movies",
        "$mainUrl/tv/popular"          to "Popular TV",
        "$mainUrl/tv/top_rated"        to "Top Rated TV",
        "$mainUrl/discover/hindi"      to "Hindi Cinema"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()
        val path = request.data.removePrefix(mainUrl)

        val items: List<TmdbItem> = when {
            path.startsWith("/trending/movie") ->
                tmdbGet("$tmdbBase$path", page)
            path.startsWith("/trending/tv") ->
                tmdbGet("$tmdbBase$path", page)
            path.startsWith("/movie/") ->
                tmdbGet("$tmdbBase$path", page)
            path.startsWith("/tv/") ->
                tmdbGet("$tmdbBase$path", page)
            path.startsWith("/discover/hindi") ->
                tmdbGet("$tmdbBase/discover/movie", page, mapOf(
                    "with_original_language" to "hi",
                    "sort_by" to "popularity.desc"
                ))
            else -> emptyList()
        }

        val mediaType = when {
            path.contains("tv") || path.contains("/tv/") -> "tv"
            else -> "movie"
        }
        val results = items.map { it.toSearchResponse(mediaType) }

        return newHomePageResponse(
            arrayListOf(HomePageList(request.name, results)),
            hasNext = results.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()
        val movies: List<TmdbItem> =
            tmdbGet("$tmdbBase/search/movie", 1, mapOf("query" to query))
        val shows: List<TmdbItem> =
            tmdbGet("$tmdbBase/search/tv", 1, mapOf("query" to query))

        return movies.map { it.toSearchResponse("movie") } +
                shows.map { it.toSearchResponse("tv") }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureConfig()

        val params = parseQueryParams(url)
        val type   = params["type"] ?: return null
        val tmdbId = params["id"]   ?: return null
        val tvType = if (type == "tv") TvType.TvSeries else TvType.Movie

        val detail: TmdbDetail = safeAsync<TmdbDetail?> {
            app.get(
                "$tmdbBase/$type/$tmdbId",
                headers = tmdbHeaders(),
                params  = mapOf(
                    "language"           to "en-US",
                    "append_to_response" to "credits,videos,similar"
                )
            ).parsedSafe<TmdbDetail>()
        } ?: return null

        val title    = detail.title ?: detail.name ?: return null
        val year     = (detail.releaseDate ?: detail.firstAirDate).orEmpty().take(4).toIntOrNull()
        val poster   = detail.posterPath?.let { "$tmdbImg$it" }
        val backdrop = detail.backdropPath?.let { "$tmdbImg$it" }
        val plot     = detail.overview
        val runtime  = detail.runtime ?: detail.episodeRunTime?.firstOrNull()
        val tags     = detail.genres?.mapNotNull { it.name }

        val score: Score? = detail.voteAverage?.let { Score.from10(it) }

        // Trailer
        val trailerKey = detail.videos?.results
            ?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
            ?.key
        val trailer = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }

        val actors = detail.credits?.cast.orEmpty().take(15).map { cast ->
            ActorData(
                actor      = Actor(cast.name ?: "", cast.profilePath?.let { "$tmdbImg$it" }),
                roleString = cast.character
            )
        }

        val recommendations = detail.similar?.results.orEmpty().take(10).mapNotNull { item ->
            val rTitle  = item.title ?: item.name ?: return@mapNotNull null
            val rPoster = item.posterPath?.let { "$tmdbImg$it" }
            val rYear   = (item.releaseDate ?: item.firstAirDate).orEmpty().take(4).toIntOrNull()
            val rId     = item.id ?: return@mapNotNull null

            if (item.name != null && item.title == null) {
                newTvSeriesSearchResponse(rTitle, "/watch?type=tv&id=$rId", TvType.TvSeries) {
                    this.posterUrl = rPoster
                    this.year      = rYear
                }
            } else {
                newMovieSearchResponse(rTitle, "/watch?type=movie&id=$rId", TvType.Movie) {
                    this.posterUrl = rPoster
                    this.year      = rYear
                }
            }
        }

        val episodes: List<Episode> = if (tvType == TvType.TvSeries) {
            val seasons = detail.seasons.orEmpty().filter { (it.seasonNumber ?: 0) > 0 }
            seasons.flatMap { season ->
                val sNum = season.seasonNumber ?: return@flatMap emptyList<Episode>()
                val seasonData: TmdbSeasonEpisodes? = safeAsync<TmdbSeasonEpisodes?> {
                    app.get(
                        "$tmdbBase/tv/$tmdbId/season/$sNum",
                        headers = tmdbHeaders(),
                        params  = mapOf("language" to "en-US")
                    ).parsedSafe<TmdbSeasonEpisodes>()
                }
                (seasonData?.episodes ?: emptyList<TmdbEpisode>()).mapNotNull { ep ->
                    val epNum  = ep.episodeNumber ?: return@mapNotNull null
                    val epName = ep.name ?: "Episode $epNum"
                    newEpisode("/watch?type=tv&id=$tmdbId&s=$sNum&e=$epNum") {
                        this.name        = epName
                        this.season      = sNum
                        this.episode     = epNum
                        this.posterUrl   = ep.stillPath?.let { "$tmdbImg$it" }
                        this.description = ep.overview
                    }
                }
            }
        } else emptyList()

        if (tvType == TvType.TvSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdrop
                this.year                = year
                this.plot                = plot
                this.tags                = tags
                this.score               = score
                this.duration            = runtime
                this.actors              = actors
                this.recommendations     = recommendations
                addTrailer(trailer)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdrop
            this.year                = year
            this.plot                = plot
            this.tags                = tags
            this.score               = score
            this.duration            = runtime
            this.actors              = actors
            this.recommendations     = recommendations
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        val params  = parseQueryParams(data)
        val type    = params["type"] ?: return false
        val tmdbId  = params["id"]   ?: return false
        val season  = params["s"]    ?: "1"
        val episode = params["e"]    ?: "1"

        val workers = workerUrls.shuffled().take(3)

        workers.forEach { worker ->
            safeAsync<Unit> {
                val isFast = workers.indexOf(worker) == 0
                val streamUrl = if (type == "tv") {
                    if (isFast) "$worker/extract/tv/$tmdbId?season=$season&episode=$episode&fast=1"
                    else "$worker/extract/tv/$tmdbId?season=$season&episode=$episode"
                } else {
                    if (isFast) "$worker/extract/movie/$tmdbId?fast=1"
                    else "$worker/extract/movie/$tmdbId"
                }

                val response: WorkerResponse? = safeAsync<WorkerResponse?> {
                    app.get(streamUrl).parsedSafe<WorkerResponse>()
                }
                if (response?.ok == true) {
                    response.streams.forEach { stream ->
                        val playUrl    = stream.proxiedUrl ?: stream.originalUrl ?: return@forEach
                        val sourceName = stream.name ?: "Server"
                        safeAsync<Unit> {
                            M3u8Helper.generateM3u8(
                                sourceName,
                                playUrl,
                                referer = worker,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Accept"     to "*/*",
                                    "Referer"    to worker,
                                    "Origin"     to getBaseUrl(worker)
                                )
                            ).forEach(callback)
                        }
                    }
                }
            }
        }

        if (type == "movie") {
            safeAsync<Unit> {
                val hdvbResp: HdvbResponse? = safeAsync<HdvbResponse?> {
                    app.get("$mainUrl/api/hdvb?tmdbId=$tmdbId").parsedSafe<HdvbResponse>()
                }
                val imdbId = hdvbResp?.imdbId ?: return@safeAsync

                extractHdvbStreams(imdbId).forEach { streamUrl ->
                    safeAsync<Unit> {
                        M3u8Helper.generateM3u8(
                            "HDVB Mirror",
                            streamUrl,
                            "https://gemma416okl.com",
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Accept"     to "*/*",
                                "Referer"    to "https://gemma416okl.com"
                            )
                        ).forEach(callback)
                    }
                }
            }
        }

        return true
    }

    // ── HDVB Stream Extraction ─────────────────────────────────────────────────

    private suspend fun extractHdvbStreams(imdbId: String): List<String> {
        val playUrl = "https://gemma416okl.com/play/$imdbId"

        val html: String = safeAsync<String?> {
            try {
                app.get(playUrl, referer = "https://gemma416okl.com/").text
            } catch (_: Exception) {
                app.get(
                    "$mainUrl/proxy",
                    params = mapOf("url" to playUrl, "referer" to "https://gemma416okl.com/")
                ).text
            }
        } ?: return emptyList()

        val match = Regex("""let p3\s*=\s*(\{.*?\});""").find(html) ?: return emptyList()

        val p3: HdvbPayload = safeAsync<HdvbPayload?> {
            parseJson<HdvbPayload>(match.groupValues[1])
        } ?: return emptyList()

        val fileUrl: String = p3.file ?: return emptyList()
        val key: String     = p3.key  ?: return emptyList()

        val playlistText: String = safeAsync<String?> {
            app.post(
                fileUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-CSRF-TOKEN" to key
                ),
                referer = playUrl
            ).text
        } ?: return emptyList()

        if (playlistText == "11") return emptyList()

        val playlist: List<HdvbPlaylistItem> = safeAsync<List<HdvbPlaylistItem>?> {
            parseJson<List<HdvbPlaylistItem>>(playlistText)
        } ?: return emptyList()

        val streams = mutableListOf<String>()

        for (item in playlist) {
            val file: String = item.file ?: continue

            if (file.startsWith("~")) {
                val subUrl = "https://gemma416okl.com/playlist/${file.removePrefix("~")}.txt"
                val streamUrl: String? = safeAsync<String?> {
                    app.post(
                        subUrl,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "X-CSRF-TOKEN" to key
                        ),
                        referer = playUrl
                    ).text
                }
                if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                    streams.add(streamUrl)
                }
            } else if (file.startsWith("http")) {
                streams.add(file)
            }
        }
        return streams
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun parseQueryParams(url: String): Map<String, String> {
        return try {
            val query = URI(
                if (url.startsWith("http")) url else "https://host$url"
            ).query ?: return emptyMap()
            query.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun ensureConfig() {
        if (tmdbToken.isNotBlank() && workerUrls.isNotEmpty()) return
        val config: SiteConfig? = safeAsync<SiteConfig?> {
            app.get("$mainUrl/api/config").parsedSafe<SiteConfig>()
        }
        tmdbToken  = config?.tmdbToken ?: ""
        workerUrls = config?.workerUrls ?: emptyList()
    }

    private fun tmdbHeaders() = mapOf(
        "Authorization" to "Bearer $tmdbToken",
        "Accept"        to "application/json"
    )

    private suspend fun tmdbGet(
        url: String,
        page: Int = 1,
        extraParams: Map<String, String> = emptyMap()
    ): List<TmdbItem> {
        val params = mutableMapOf("language" to "en-US", "page" to page.toString())
        params.putAll(extraParams)

        val result: List<TmdbItem>? = safeAsync<List<TmdbItem>?> {
            app.get(url, headers = tmdbHeaders(), params = params)
                .parsedSafe<TmdbPaginatedItems>()
                ?.results
        }
        return result ?: emptyList()
    }

    private fun TmdbItem.toSearchResponse(type: String): SearchResponse {
        val tTitle  = title ?: name ?: "Unknown"
        val href    = if (type == "tv") "/watch?type=tv&id=$id" else "/watch?type=movie&id=$id"
        val tPoster = posterPath?.let { "$tmdbImg$it" }
        val tYear   = (releaseDate ?: firstAirDate).orEmpty().take(4).toIntOrNull()

        if (type == "tv") {
            return newTvSeriesSearchResponse(tTitle, href, TvType.TvSeries) {
                this.posterUrl = tPoster
                this.year      = tYear
            }
        }
        return newMovieSearchResponse(tTitle, href, TvType.Movie) {
            this.posterUrl = tPoster
            this.year      = tYear
        }
    }

    fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    // ── Data Models ────────────────────────────────────────────────────────────

    data class SiteConfig(
        @JsonProperty("tmdbToken")  val tmdbToken: String? = null,
        @JsonProperty("workerUrls") val workerUrls: List<String> = emptyList()
    )

    data class WorkerResponse(
        @JsonProperty("ok")      val ok: Boolean = false,
        @JsonProperty("title")   val title: String? = null,
        @JsonProperty("streams") val streams: List<WorkerStream> = emptyList()
    )

    data class WorkerStream(
        @JsonProperty("name")         val name: String? = null,
        @JsonProperty("original_url") val originalUrl: String? = null,
        @JsonProperty("proxied_url")  val proxiedUrl: String? = null
    )

    data class HdvbResponse(
        @JsonProperty("ok")     val ok: Boolean = false,
        @JsonProperty("imdbId") val imdbId: String? = null
    )

    data class HdvbPayload(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("key")  val key: String? = null
    )

    data class HdvbPlaylistItem(
        @JsonProperty("file")  val file: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    // FIXED: Concrete class instead of generic to avoid type erasure
    data class TmdbPaginatedItems(
        @JsonProperty("results")     val results: List<TmdbItem> = emptyList(),
        @JsonProperty("page")        val page: Int = 1,
        @JsonProperty("total_pages") val totalPages: Int = 0
    )

    data class TmdbItem(
        @JsonProperty("id")             val id: Int? = null,
        @JsonProperty("title")          val title: String? = null,
        @JsonProperty("name")           val name: String? = null,
        @JsonProperty("poster_path")    val posterPath: String? = null,
        @JsonProperty("backdrop_path")  val backdropPath: String? = null,
        @JsonProperty("overview")       val overview: String? = null,
        @JsonProperty("release_date")   val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average")   val voteAverage: Double? = null,
        @JsonProperty("popularity")     val popularity: Double? = null,
        @JsonProperty("genre_ids")      val genreIds: List<Int>? = null
    )

    data class TmdbDetail(
        @JsonProperty("id")                val id: Int? = null,
        @JsonProperty("title")             val title: String? = null,
        @JsonProperty("name")              val name: String? = null,
        @JsonProperty("poster_path")       val posterPath: String? = null,
        @JsonProperty("backdrop_path")     val backdropPath: String? = null,
        @JsonProperty("overview")          val overview: String? = null,
        @JsonProperty("release_date")      val releaseDate: String? = null,
        @JsonProperty("first_air_date")    val firstAirDate: String? = null,
        @JsonProperty("vote_average")      val voteAverage: Double? = null,
        @JsonProperty("runtime")           val runtime: Int? = null,
        @JsonProperty("episode_run_time")  val episodeRunTime: List<Int>? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("imdb_id")           val imdbId: String? = null,
        @JsonProperty("tagline")           val tagline: String? = null,
        @JsonProperty("genres")            val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits")           val credits: TmdbCredits? = null,
        @JsonProperty("videos")            val videos: TmdbVideos? = null,
        @JsonProperty("similar")           val similar: TmdbSimilar? = null,
        @JsonProperty("seasons")           val seasons: List<TmdbSeason>? = null
    )

    data class TmdbGenre(
        @JsonProperty("id")   val id: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
        @JsonProperty("crew") val crew: List<TmdbCrew>? = null
    )

    data class TmdbCast(
        @JsonProperty("name")         val name: String? = null,
        @JsonProperty("character")    val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    data class TmdbCrew(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("job")  val job: String? = null
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo>? = null
    )

    data class TmdbVideo(
        @JsonProperty("key")  val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class TmdbSimilar(
        @JsonProperty("results") val results: List<TmdbItem>? = null
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("name")          val name: String? = null
    )

    data class TmdbSeasonEpisodes(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList()
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number")  val seasonNumber: Int? = null,
        @JsonProperty("name")           val name: String? = null,
        @JsonProperty("overview")       val overview: String? = null,
        @JsonProperty("still_path")     val stillPath: String? = null,
        @JsonProperty("air_date")       val airDate: String? = null,
        @JsonProperty("vote_average")   val voteAverage: Double? = null
    )
}