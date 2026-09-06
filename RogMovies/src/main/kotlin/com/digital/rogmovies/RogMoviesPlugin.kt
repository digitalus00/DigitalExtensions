package com.digital.rogmovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RogMoviesPlugin : BasePlugin() {
    override fun load() = registerMainAPI(RogMovies())
}
