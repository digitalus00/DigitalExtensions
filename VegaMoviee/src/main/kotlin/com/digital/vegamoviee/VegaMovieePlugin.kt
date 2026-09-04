package com.digital.vegamoviee
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin class VegaMovieePlugin:BasePlugin(){override fun load()=registerMainAPI(VegaMoviee())}
