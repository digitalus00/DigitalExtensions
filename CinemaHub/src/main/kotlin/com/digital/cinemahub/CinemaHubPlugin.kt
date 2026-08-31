package com.digital.cinemahub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinemaHubPlugin : BasePlugin() {
    override fun load() = registerMainAPI(CinemaHub())
}
