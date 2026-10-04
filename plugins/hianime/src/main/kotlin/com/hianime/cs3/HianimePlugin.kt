package com.hianime.cs3

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [HianimeProvider] as a regular MainAPI source — it shows up on
 * search and home exactly like any repository-installed extension, with no code
 * in the app itself.
 */
class HianimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HianimeProvider())
    }
}