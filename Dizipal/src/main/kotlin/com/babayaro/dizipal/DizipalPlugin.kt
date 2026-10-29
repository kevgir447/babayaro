package com.babayaro.dizipal

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizipalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DizipalProvider())
    }
}
