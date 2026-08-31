package com.digital.girlswallowed

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GirlswallowedPlugin : BasePlugin() {
    override fun load() = registerMainAPI(Girlswallowed())
}
