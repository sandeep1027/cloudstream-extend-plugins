package com.prowlarr.cs3

import com.lagradost.cloudstream3.plugins.BasePlugin

class ProwlarrPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ProwlarrProvider())
    }
}