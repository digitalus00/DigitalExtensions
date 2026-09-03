package com.digital.beeg
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin class BeegPlugin:BasePlugin(){override fun load()=registerMainAPI(Beeg())}
