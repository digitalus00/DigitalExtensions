package com.digital.rivestream

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class RiveStream : MainAPI() {
    override var mainUrl = "https://rivestream.ru"
    override var name = "RiveStream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val gson = Gson()
    private val tmdb = "https://api.themoviedb.org/3"
    private val key = "d64117f26031a428449f102ced3aba73"
    override val mainPage = mainPageOf(
        "$tmdb/trending/movie/week?api_key=$key" to "Trending Movies",
        "$tmdb/trending/tv/week?api_key=$key" to "Trending TV",
        "$tmdb/movie/now_playing?api_key=$key" to "Now Playing",
        "$tmdb/tv/on_the_air?api_key=$key" to "On The Air"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = api<TmdbList>("${request.data}&page=$page")
        return newHomePageResponse(request.name, result.results.map { it.toSearch() }, page < result.total_pages)
    }
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val result = api<TmdbList>("$tmdb/search/multi?api_key=$key&query=${URLEncoder.encode(query, "UTF-8")}&page=$page")
        val items = result.results.filter { it.media_type == "movie" || it.media_type == "tv" }.map { it.toSearch() }
        return newSearchResponseList(items, page < result.total_pages)
    }
    override suspend fun quickSearch(query: String) = search(query, 1).items
    override suspend fun load(url: String): LoadResponse {
        val p = url.substringAfter("/title/").split("/"); val type = p[0]; val id = p[1]
        if (type == "tv") {
            val d = api<TvDetail>("$tmdb/tv/$id?api_key=$key")
            val eps = d.seasons.orEmpty().filter { it.season_number > 0 }.flatMap { s ->
                (1..s.episode_count).map { e -> newEpisode("$mainUrl/play/tv/$id/${s.season_number}/$e") { name = "Episode $e"; season = s.season_number; episode = e } }
            }
            return newTvSeriesLoadResponse(d.name ?: "Unknown", url, TvType.TvSeries, eps) { posterUrl = poster(d.poster_path); backgroundPosterUrl = poster(d.backdrop_path); plot = d.overview; year = d.first_air_date?.take(4)?.toIntOrNull(); tags = d.genres.orEmpty().map { it.name } }
        }
        val d = api<MovieDetail>("$tmdb/movie/$id?api_key=$key")
        return newMovieLoadResponse(d.title ?: "Unknown", url, TvType.Movie, "$mainUrl/play/movie/$id") { posterUrl = poster(d.poster_path); backgroundPosterUrl = poster(d.backdrop_path); plot = d.overview; year = d.release_date?.take(4)?.toIntOrNull(); tags = d.genres.orEmpty().map { it.name } }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val p = data.substringAfter("/play/").split("/"); val type = p[0]; val id = p[1]
        val path = if (type == "movie") "$mainUrl/api/provider?provider=apex&id=$id" else "$mainUrl/api/provider?provider=apex&id=$id&season=${p[2]}&episode=${p[3]}"
        val result = api<Scrape>("https://scrapper.rivestream.app${path.substringAfter(mainUrl)}")
        result.data?.sources.orEmpty().forEach { callback(newExtractorLink("RiveStream Apex", "Apex", it.url, ExtractorLinkType.M3U8) { referer = mainUrl }) }
        return result.data?.sources?.isNotEmpty() == true
    }
    private fun poster(path: String?) = path?.let { "https://image.tmdb.org/t/p/w500$it" }
    private suspend inline fun <reified T> api(url: String): T = gson.fromJson(app.get(url, headers = mapOf("Accept" to "application/json")).text, T::class.java)
    private fun Item.toSearch() = if (media_type == "tv") newTvSeriesSearchResponse(name ?: title ?: "Unknown", "$mainUrl/title/tv/$id", TvType.TvSeries) { posterUrl = poster(poster_path); year = (first_air_date ?: release_date)?.take(4)?.toIntOrNull() } else newMovieSearchResponse(title ?: name ?: "Unknown", "$mainUrl/title/movie/$id", TvType.Movie) { posterUrl = poster(poster_path); year = (release_date ?: first_air_date)?.take(4)?.toIntOrNull() }
}
data class TmdbList(val results: List<Item> = emptyList(), val total_pages: Int = 1)
data class Item(val id: Int = 0, val media_type: String? = null, val title: String? = null, val name: String? = null, val poster_path: String? = null, val release_date: String? = null, val first_air_date: String? = null)
data class Genre(val name: String = "")
data class Season(val season_number: Int = 0, val episode_count: Int = 0)
data class MovieDetail(val title: String? = null, val overview: String? = null, val poster_path: String? = null, val backdrop_path: String? = null, val release_date: String? = null, val genres: List<Genre>? = null)
data class TvDetail(val name: String? = null, val overview: String? = null, val poster_path: String? = null, val backdrop_path: String? = null, val first_air_date: String? = null, val genres: List<Genre>? = null, val seasons: List<Season>? = null)
data class Scrape(val data: Sources? = null)
data class Sources(val sources: List<Source> = emptyList())
data class Source(val url: String = "")
