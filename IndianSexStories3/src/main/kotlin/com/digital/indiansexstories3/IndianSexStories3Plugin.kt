package com.digital.indiansexstories3

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IndianSexStories3Plugin : Plugin() {
    override fun load(context: Context) {
        StoryReader.initialize(context)
        (context as? AppCompatActivity)?.let(StoryReader::register)
        registerMainAPI(IndianSexStories3())
    }
}
