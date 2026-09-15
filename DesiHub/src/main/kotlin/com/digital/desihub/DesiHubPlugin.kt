package com.digital.desihub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DesiHubPlugin : BasePlugin() {
    override fun load() = registerMainAPI(DesiHub())
}
