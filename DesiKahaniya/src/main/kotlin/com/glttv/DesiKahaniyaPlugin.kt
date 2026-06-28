package com.glttv

import android.content.Context
import com.desikahani2.Desikahani2
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiKahaniyaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Desikahani2())
    }
}