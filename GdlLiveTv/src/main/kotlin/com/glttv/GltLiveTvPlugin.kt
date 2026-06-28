package com.glttv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class GltLiveTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GdlTvProvider())
    }
}