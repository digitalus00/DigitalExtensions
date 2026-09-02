package com.digital.rivestream
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class RiveStreamPlugin : BasePlugin() { override fun load() = registerMainAPI(RiveStream()) }
