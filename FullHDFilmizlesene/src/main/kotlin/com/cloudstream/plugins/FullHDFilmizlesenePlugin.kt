package com.cloudstream.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FullHDFilmizlesenePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FullHDFilmizleseneProvider())
    }
}
