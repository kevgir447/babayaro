package com.babayaro.hdfilmcehennemi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HdfilmcehennemiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HdfilmcehennemiProvider())
    }
}
