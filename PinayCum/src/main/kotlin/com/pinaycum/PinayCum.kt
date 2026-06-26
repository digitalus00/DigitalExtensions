override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false
        
        // Track unique URLs processed during this run to prevent duplicate listings
        val processedUrls = mutableSetOf<String>()
        val pageHtml = document.toString()

        // 1. Aggressive StreamRuby Domain Sweeper
        Regex("""https?://(?:streamruby|rubystream|rubyembed|rubystr|struby|streamr)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value
            if (processedUrls.add(cleanUrl)) {
                try {
                    val rubyResponse = app.get(cleanUrl, referer = data).text
                    val streamUrlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    val directStreamUrl = streamUrlRegex.find(rubyResponse)?.groupValues?.get(1)

                    if (directStreamUrl != null) {
                        val isM3u8 = directStreamUrl.contains(".m3u8")
                        callback(
                            newExtractorLink(
                                source = "StreamRuby",
                                name = "StreamRuby Mirror",
                                url = directStreamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Broad Vidara Sweeper (Handles variations like vidaara, vidaarax with .net, .com, .xyz, etc.)
        Regex("""https?://(?:vidaara|vidaarax)[\w-]*\.[a-z]+/e/[\w-]+""").findAll(pageHtml).forEach { match ->
            val embedUrl = match.value
            if (processedUrls.add(embedUrl)) {
                try {
                    val embedDoc = app.get(embedUrl, referer = mainUrl).text
                    val streamUrl = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedDoc)?.groupValues?.get(1)

                    if (streamUrl != null) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Vidara Direct",
                                url = streamUrl,
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Clean Host Linker for DoodStream and LuluStream
        Regex("""https?://(?:doodstream\.com|dood\.[^\s"'><]+|ds2play\.[^\s"'><]+)/[efd]/[a-zA-Z0-9]+""").findAll(pageHtml).forEach { match ->
            val embedUrl = match.value.replace("/d/", "/e/").replace("/f/", "/e/")
            if (processedUrls.add(embedUrl)) {
                callback(newExtractorLink("DoodStream", "DoodStream Mirror", embedUrl, ExtractorLinkType.VIDEO))
                found = true
            }
        }

        Regex("""https?://(?:lulustream|lulu)[^\s"'><]+""").findAll(pageHtml).forEach { match ->
            val luluUrl = match.value
            if (processedUrls.add(luluUrl)) {
                callback(newExtractorLink("LuluStream", "LuluStream Mirror", luluUrl, ExtractorLinkType.VIDEO))
                found = true
            }
        }

        // 4. Secondary Deep Scan of Structural Server Buttons
        val playerButtons = document.select("a.btn-dark[href*='&s=']")
        for (playerBtn in playerButtons) {
            val playerUrl = fixUrl(playerBtn.attr("href"))
            val btnName = playerBtn.text().trim()

            try {
                val playerDoc = app.get(playerUrl, referer = data).document
                val collectedUrls = mutableListOf<String>()
                playerDoc.select("iframe").forEach { el -> el.attr("src").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }
                playerDoc.select("a").forEach { el -> el.attr("href").takeIf { it.isNotEmpty() }?.let { collectedUrls.add(it) } }

                for (rawUrl in collectedUrls.distinct()) {
                    val cleanUrl = fixUrlNull(rawUrl) ?: continue
                    if (!processedUrls.add(cleanUrl)) continue 

                    if (cleanUrl.contains("ruby") || cleanUrl.contains("streamruby") || cleanUrl.contains("struby")) {
                        val rubyResponse = app.get(cleanUrl, referer = playerUrl).text
                        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(rubyResponse)?.groupValues?.get(1)?.let { directUrl ->
                            callback(newExtractorLink("StreamRuby", "$btnName (StreamRuby)", directUrl, if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                            found = true
                        }
                    } else if (cleanUrl.contains("dood") || cleanUrl.contains("ds2play")) {
                        callback(newExtractorLink("DoodStream", "$btnName (DoodStream)", cleanUrl.replace("/d/", "/e/"), ExtractorLinkType.VIDEO))
                        found = true
                    } else if (cleanUrl.contains("lulu")) {
                        callback(newExtractorLink("LuluStream", "$btnName (LuluStream)", cleanUrl, ExtractorLinkType.VIDEO))
                        found = true
                    } else if (cleanUrl.contains("vidaara") || cleanUrl.contains("vidara")) {
                        val embedDoc = app.get(cleanUrl, referer = playerUrl).text
                        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(embedDoc)?.groupValues?.get(1)?.let { directUrl ->
                            callback(newExtractorLink(name, "$btnName (Vidara)", directUrl, if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return found
    }