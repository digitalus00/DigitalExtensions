package com.digital.cinemahub

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class CinemaHub : MainAPI() {
    override var mainUrl = "https://apicinema.opguys.cfd"
    override var name = "CinemaHub"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val gson = Gson()

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/catalog/trending?type=movie" to "Trending Movies",
        "$mainUrl/api/v1/catalog/trending?type=tv" to "Trending TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}&page=$page"
        val result = api<CatalogResponse>(url)
        return newHomePageResponse(request.name, result.results.map { it.search() }, hasNext = page < result.total_pages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val result = api<CatalogResponse>("$mainUrl/api/v1/catalog/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page")
        return newSearchResponseList(result.results.map { it.search() }, hasNext = page < result.total_pages)
    }

    override suspend fun quickSearch(query: String) = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val parts = url.removePrefix("cinemahub:").split(":")
        val type = parts[0]
        val id = parts[1]
        val detail = api<Detail>("$mainUrl/api/v1/catalog/details/$type/$id")
        val title = detail.title ?: detail.name ?: "Unknown"
        val poster = detail.poster_path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = detail.backdrop_path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w1280$it" }
        val genres = detail.genres.orEmpty().mapNotNull { it.name }
        val year = (detail.release_date ?: detail.first_air_date)?.take(4)?.toIntOrNull()
        if (type == "tv") {
            val episodes = detail.seasons.orEmpty().filter { (it.season_number ?: 0) > 0 }.flatMap { seasonData ->
                (1..(seasonData.episode_count ?: 0)).map { ep -> newEpisode("cinemahub:tv:$id:${seasonData.season_number}:$ep") { this.season = seasonData.season_number ?: 1; this.episode = ep; name = "Episode $ep" } }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; backgroundPosterUrl = backdrop; plot = detail.overview; this.year = year; tags = genres }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, "cinemahub:movie:$id") { posterUrl = poster; backgroundPosterUrl = backdrop; plot = detail.overview; this.year = year; tags = genres }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val p = data.removePrefix("cinemahub:").split(":")
        val type = p[0]; val id = p[1]
        val path = if (type == "movie") "/api/v1/extract/movie/$id" else "/api/v1/extract/tv/$id?s=${p[2]}&e=${p[3]}"
        val result = api<Streams>(mainUrl + path)
        result.streams.orEmpty().forEach { stream ->
            val link = stream.proxied_url ?: stream.original_url ?: return@forEach
            callback(newExtractorLink(stream.name ?: name, stream.name ?: "Stream", link, if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = mainUrl })
        }
        val subPath = if (type == "movie") "/api/v1/subtitles/movie/$id" else "/api/v1/subtitles/tv/$id?s=${p[2]}&e=${p[3]}"
        runCatching { api<Subtitles>(mainUrl + subPath).subtitles.orEmpty().forEach { s -> if (s.url != null) subtitleCallback(SubtitleFile(s.language ?: "English", s.url!!)) } }
        return result.streams.orEmpty().isNotEmpty()
    }

    private suspend inline fun <reified T> api(url: String): T = gson.fromJson(app.get(url, headers = mapOf("Accept" to "application/json")).text, T::class.java)
    private fun Item.search(): SearchResponse = if (media_type == "tv") newTvSeriesSearchResponse(title ?: "Unknown", "cinemahub:tv:$id", TvType.TvSeries) { posterUrl = poster_path; year = release_date?.take(4)?.toIntOrNull() } else newMovieSearchResponse(title ?: "Unknown", "cinemahub:movie:$id", TvType.Movie) { posterUrl = poster_path; year = release_date?.take(4)?.toIntOrNull() }
}

data class CatalogResponse(val results: List<Item> = emptyList(), val total_pages: Int = 1)
data class Item(val id: Int = 0, val media_type: String? = null, val title: String? = null, val overview: String? = null, val poster_path: String? = null, val release_date: String? = null)
data class Detail(val title: String? = null, val name: String? = null, val overview: String? = null, val poster_path: String? = null, val backdrop_path: String? = null, val release_date: String? = null, val first_air_date: String? = null, val genres: List<Genre>? = null, val seasons: List<Season>? = null)
data class Genre(val name: String? = null)
data class Season(val season_number: Int? = null, val episode_count: Int? = null)
data class Streams(val streams: List<Stream>? = null)
data class Stream(val name: String? = null, val original_url: String? = null, val proxied_url: String? = null)
data class Subtitles(val subtitles: List<Sub>? = null)
data class Sub(val language: String? = null, val url: String? = null)
