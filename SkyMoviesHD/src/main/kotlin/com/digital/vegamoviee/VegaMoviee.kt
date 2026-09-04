package com.digital.skymovieshd
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
class VegaMoviee:MainAPI(){
 private val resolver=WebViewResolver(Regex("(?i)https?://.*\\.(?:mp4|mkv|m3u8)(?:\\?.*)?$"), emptyList(), null, true, null, {}, 60000L)
 override var mainUrl="https://skymovieshd.meme";override var name="SkyMoviesHD";override var lang="hi";override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries);override val hasMainPage=true;override val hasQuickSearch=true
 override val mainPage=mainPageOf("$mainUrl/category/Bollywood-Movies.html" to "Bollywood","$mainUrl/category/Hollywood-English-Movies.html" to "Hollywood","$mainUrl/category/Hollywood-Hindi-Dubbed-Movies.html" to "Hindi Dubbed","$mainUrl/category/South-Indian-Hindi-Dubbed-Movies.html" to "South Hindi","$mainUrl/category/All-Web-Series.html" to "Web Series","$mainUrl/category/Korean-and-China-Movies.html" to "Korean")
 private val h=mapOf("User-Agent" to "Mozilla/5.0")
 override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{val d=app.get(if(page==1)request.data else "${request.data.trimEnd('/')}/page/$page/",headers=h,referer=mainUrl).document;return newHomePageResponse(request.name,items(d),d.selectFirst("a.next, a.next.page-numbers, link[rel=next]")!=null)}
 override suspend fun search(query:String,page:Int):SearchResponseList{val q=URLEncoder.encode(query.trim(),"UTF-8");val d=app.get("$mainUrl/search.php?search=$q&cat=All",headers=h,referer=mainUrl).document;return newSearchResponseList(items(d),false)}
 override suspend fun quickSearch(query:String)=search(query,1).items
 override suspend fun load(url:String):LoadResponse{val d=app.get(url,headers=h,referer=mainUrl).document;val title=d.selectFirst("h1.entry-title,h1")?.text()?.trim()?:d.selectFirst("meta[property=og:title]")?.attr("content")?:"VegaMoviee";val poster=d.selectFirst("meta[property=og:image]")?.attr("content");val plot=d.selectFirst("meta[name=description],meta[property=og:description]")?.attr("content");val links=d.select(".entry-content a[href]").map{it.absUrl("href")}.filter{it.startsWith("http")&&!it.contains("vegamoviee.com")};return newMovieLoadResponse(title,url,TvType.Movie,url){posterUrl=poster;this.plot=plot;recommendations=d.select("article.post-item").mapNotNull{it.toResult()}}}
 override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{val d=app.get(data,headers=h,referer=mainUrl).document;var f=false;d.select(".L a[href]").forEach{val u=it.absUrl("href");if(!u.startsWith("http")||u.contains(mainUrl))return@forEach;val r=runCatching{resolver.resolveUsingWebView(u,data){it.url.toString().contains(Regex("\\.(?:mp4|mkv|m3u8)(?:\\?|$)",RegexOption.IGNORE_CASE))}.first}.getOrNull();val out=r?.url?.toString()?:u;if(out.contains(Regex("\\.(?:mp4|mkv|m3u8)",RegexOption.IGNORE_CASE))){callback(newExtractorLink(name,"Direct",out,if(out.contains("m3u8"))ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){referer=data;headers=r?.headers?.toMap()?:emptyMap()});f=true}else if(loadExtractor(out,data,subtitleCallback,callback))f=true};return f}
 private fun items(d:org.jsoup.nodes.Document)=d.select("a[href*='/movie/']").mapNotNull{it.toResult()}.distinctBy{it.url}
 private fun Element.toResult():SearchResponse?{val u=absUrl("href");val t=text().trim().ifBlank{attr("title")};if(!u.startsWith(mainUrl)||t.isBlank())return null;return newMovieSearchResponse(t,u,TvType.Movie)}
}
