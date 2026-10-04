package com.torrin.trakt

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [TraktProvider] as a regular MainAPI source — it shows up on the
 * home/dashboard page exactly like any repository-installed extension, with
 * no code in the app itself.
 */
class TraktPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TraktProvider())
    }
}
