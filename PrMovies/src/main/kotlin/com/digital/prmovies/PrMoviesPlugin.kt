package com.digital.prmovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PrMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PrMovies())
    }
}
