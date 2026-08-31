package com.digital.cinevood

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineVoodPlugin : BasePlugin() {
    override fun load() = registerMainAPI(CineVood())
}
