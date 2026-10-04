package com.bollyflix.cs3

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [BollyflixProvider] as a regular MainAPI source — it shows up on
 * search and home exactly like any repository-installed extension, with no code
 * in the app itself.
 */
class BollyflixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BollyflixProvider())
    }
}