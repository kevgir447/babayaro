package com.babayaro.dizikorea

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizikoreaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DizikoreaProvider())
    }
}
