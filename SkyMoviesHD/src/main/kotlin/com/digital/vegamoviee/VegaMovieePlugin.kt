package com.digital.skymovieshd
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin class SkyMoviesHDPlugin:BasePlugin(){override fun load()=registerMainAPI(VegaMoviee())}
