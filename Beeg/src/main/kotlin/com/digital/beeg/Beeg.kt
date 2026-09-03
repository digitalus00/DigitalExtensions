package com.digital.beeg
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
class Beeg:MainAPI(){
 override var mainUrl="https://beeg.com";override var name="Beeg";override var lang="en";override val supportedTypes=setOf(TvType.NSFW);override val hasMainPage=true;override val hasQuickSearch=true
 override val mainPage=mainPageOf("$mainUrl/" to "Latest Videos","$mainUrl/categories" to "Categories")
 override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,app.get(request.data,referer=mainUrl).document.select("a[href]").mapNotNull{it.toResult()}.distinctBy{it.url},false)
 override suspend fun search(query:String,page:Int)=newSearchResponseList(app.get("$mainUrl/search/${URLEncoder.encode(query,"UTF-8")}",referer=mainUrl).document.select("a[href]").mapNotNull{it.toResult()}.distinctBy{it.url},false)
 override suspend fun load(url:String):LoadResponse{val d=app.get(url,referer=mainUrl).document;val t=d.selectFirst("h1,meta[property=og:title]")?.let{if(it.tagName()=="meta")it.attr("content") else it.text()}?.trim()?:"Beeg";return newMovieLoadResponse(t,url,TvType.NSFW,url){posterUrl=d.selectFirst("meta[property=og:image]")?.attr("content")}}
 override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit)=false
 private fun Element.toResult():SearchResponse?{val u=absUrl("href").ifBlank{fixUrl(attr("href"))};val t=attr("title").ifBlank{text()}.trim();if(!u.startsWith(mainUrl)||t.isBlank()||u==mainUrl)return null;return newMovieSearchResponse(t,u,TvType.NSFW){posterUrl=selectFirst("img")?.absUrl("src")}}
}
