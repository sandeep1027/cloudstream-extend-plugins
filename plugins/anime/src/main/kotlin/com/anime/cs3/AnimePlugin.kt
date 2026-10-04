package com.anime.cs3

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [AnimeProvider] as a MainAPI source for anime content with
 * AniList integration for metadata, search, and tracking.
 */
class AnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeProvider())
    }
}
