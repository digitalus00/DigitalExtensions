package com.glttv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiKahaniyaPlugin : Plugin() {
    override fun load(context: Context) {
        (context as? AppCompatActivity)?.let(StoryReader::register)
        registerMainAPI(DesiKahaniya())
    }
}
