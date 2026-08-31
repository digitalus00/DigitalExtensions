package com.digital.maturetube

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MatureTubePlugin : BasePlugin() {
    override fun load() = registerMainAPI(MatureTube())
}
