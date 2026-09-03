package com.digital.spankbang
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
class SpankBang: MainAPI(){
 override var mainUrl="https://spankbang.com"; override var name="SpankBang"; override var lang="en"; override val supportedTypes=setOf(TvType.NSFW); override val hasMainPage=true; override val hasQuickSearch=true
 override val mainPage=mainPageOf("$mainUrl/" to "Latest Videos", "$mainUrl/categories/" to "Categories", "$mainUrl/channels/" to "Channels")
 override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,app.get(if(page==1)request.data else "${request.data.trimEnd('/')}/$page",referer=mainUrl).document.select("a[href*='/video/'], a[href*='/s/']").mapNotNull{it.toResult()}.distinctBy{it.url},true)
 override suspend fun search(query:String,page:Int):SearchResponseList{val d=app.get("$mainUrl/s/${URLEncoder.encode(query,"UTF-8")}/",referer=mainUrl).document;return newSearchResponseList(d.select("a[href*='/video/'],a[href*='/s/']").mapNotNull{it.toResult()}.distinctBy{it.url},true)}
 override suspend fun load(url:String):LoadResponse{val d=app.get(url,referer=mainUrl).document;val title=d.selectFirst("h1,meta[property=og:title]")?.let{if(it.tagName()=="meta")it.attr("content") else it.text()}?.trim()?:"SpankBang";val poster=d.selectFirst("meta[property=og:image]")?.attr("content");return newMovieLoadResponse(title,url,TvType.NSFW,url){posterUrl=poster}}
 override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{val d=app.get(data,referer=mainUrl).document;var f=false;d.select("video source[src],video[src],iframe[src]").forEach{val u=it.absUrl("src").ifBlank{fixUrl(it.attr("src"))};if(u.startsWith("http")){if(u.contains(".m3u8")||u.contains(".mp4")){callback(newExtractorLink(name,name,u,if(u.contains(".m3u8"))ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){referer=data});f=true}else if(loadExtractor(u,data,subtitleCallback,callback))f=true}};return f}
 private fun Element.toResult():SearchResponse?{val u=absUrl("href").ifBlank{fixUrl(attr("href"))};if(!u.startsWith(mainUrl))return null;val t=attr("title").ifBlank{text()}.trim();if(t.isBlank())return null;return newMovieSearchResponse(t,u,TvType.NSFW){posterUrl=selectFirst("img")?.let{it.absUrl("src").ifBlank{it.attr("data-src")}}}}
}
