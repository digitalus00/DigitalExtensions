package com.glttv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class GltTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GdlCinemaProvider())
    }
}