package com.digital.lala49

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Lala49Plugin : Plugin() {
    override fun load() = registerMainAPI(Lala49())
}
