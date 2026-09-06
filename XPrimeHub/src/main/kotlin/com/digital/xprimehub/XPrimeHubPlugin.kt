package com.digital.xprimehub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XPrimeHubPlugin : BasePlugin() {
    override fun load() = registerMainAPI(XPrimeHub())
}
