package com.torrin.mdblist

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [MdblistProvider] as a regular MainAPI source — it shows up on
 * the home/dashboard page exactly like any repository-installed extension,
 * with no code in the app itself.
 */
class MdblistPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MdblistProvider())
    }
}
