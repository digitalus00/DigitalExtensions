package com.digital.spankbang
import com.lagradost.cloudstream3.plugins.*
@CloudstreamPlugin class SpankBangPlugin:BasePlugin(){override fun load()=registerMainAPI(SpankBang())}
