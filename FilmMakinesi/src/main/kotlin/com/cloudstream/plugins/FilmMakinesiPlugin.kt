package com.cloudstream.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.context.Context

@CloudstreamPlugin
class FilmMakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesiProvider())
    }
}
