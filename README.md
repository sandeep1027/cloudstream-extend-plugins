# CloudStream Extend — Plugins

Source and built `.cs3` packages for the plugins used with
[cloudstream-extend](https://github.com/sandeep1027/cloudstream-extend).

Each plugin lives in its own module and is published as its own installable
repository, so a source going down — or a domain changing — only affects that one
plugin. Nothing here is bundled into the app: users install what they want from
Settings → Extensions → Add repository.

## Install

Settings → Extensions → Add repository, then paste the URL for the plugin you
want:

| Plugin | Repository URL | Needs |
|---|---|---|
| **HiAnime** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/hianime/repo/repository.json` | nothing |
| **AniKoto** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/anikoto/repo/repository.json` | nothing |
| **AnimeCube** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/animecube/repo/repository.json` | nothing |
| **HDHub4u** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/hdhub4u/repo/repository.json` | nothing |
| **BollyFlix** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/bollyflix/repo/repository.json` | nothing |
| **YTS** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/yts/repo/repository.json` | a debrid account |
| **Torrin** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin/repo/repository.json` | debrid + TMDB key |
| **Torrin MDBList** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-mdblist/repo/repository.json` | debrid + TMDB key + MDBList key |
| **Torrin Trakt** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-trakt/repo/repository.json` | debrid + TMDB key + Trakt id |
| **Prowlarr** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/prowlarr/repo/repository.json` | a Prowlarr instance + debrid |

The first five stream directly and need no key. **YTS** is a metadata/torrent
source: the site serves no video, so every link is a magnet resolved through
your **Torrin / TorBox / Real-Debrid** account (Settings → Player → Debrid).
The Torrin plugins additionally want a free TMDB key in Settings → Player →
Metadata. **Prowlarr** searches the indexers you configured on your own Prowlarr
server: give it the address and API key under Settings → Player → Prowlarr, and
it hands the matched releases to debrid the same way.

Home rows are shown for **one provider at a time** — tap the provider chip at the
bottom of Home and pick the one you want. The chips in that picker decide which
providers it lists at all: a source that only serves a type you have not selected
is hidden. **Movies**, **TV Series** and **Anime** are selected by default, which
covers the anime-only sources (HiAnime, AniKoto, AnimeCube); enable more chips
there if you install something else. The chips filter the picker, not the rows a
provider returns.

Each plugin has an icon in Settings → Extensions, served from
`plugins/<module>/repo/icon.png` through the `iconUrl` in that plugin's
`plugins.json`. There are no real logos to use, so they are initials on a colour:
regenerate them with `powershell -NoProfile -File tools/make_plugin_icons.ps1`.

## Plugins

| Module | Source | What it does |
|---|---|---|
| `plugins/hianime` | hianime.at | Anime: home rows, search, detail, Sub/Dub episodes. Streams via VidPlay/MegaPlay `getSources` and Zoko's player blob into m3u8/MP4 with subtitles. |
| `plugins/anikoto` | anikototv.to | Anime: home rows, search, sub/dub, streams through the site's server chain including MegaPlay's AES-encrypted sources. |
| `plugins/animecube` | animecube.live | Anime: listings and episodes from the site's Next.js payload, streams from its sources endpoint, Dailymotion/Rumble expanded to HLS/MP4. |
| `plugins/hdhub4u` | HDHub4u | Movies and series (Hindi/Hollywood): home rows, search, episodes, streams through the site's link-bypass hops. |
| `plugins/bollyflix` | new.bollyflix.vote | Bollywood/Hollywood/dual-audio/Korean catalogue from the site's WordPress REST API, with the per-title quality table. Streams directly from the site's own mirrors; no debrid account needed. |
| `plugins/yts` | en.yts.lu | Movies and shows with home rows per streaming service (Netflix, Prime Video, Disney+, Max, Hulu + TV equivalents), This Week / Today, Indian rows, genres. Magnets labelled with quality, size and seeders. |
| `plugins/torrin` | — | Curated dashboard and "Latest on Netflix / Hotstar / ZEE5 / SonyLIV" rows, Torrentio + debrid playback. |
| `plugins/torrin-mdblist` | MDBList | "Latest Movies" / "Latest Shows" rows plus trending. |
| `plugins/torrin-trakt` | Trakt | "Latest Movies" / "Latest Episodes" rows from the public Trakt calendar. |
| `plugins/prowlarr` | your own Prowlarr server | Movies: queries every indexer configured on your Prowlarr instance through its API and emits magnets (rebuilt from the info hash) for debrid playback. Needs the server address + API key in Settings → Player → Prowlarr. |
| `plugins/anime` | AniList | AniList catalogue (source only — not published as a repository). |

Playback note: many of these hosts answer `403` unless the request carries the
embed's `Referer`, and the player's default HTTP stack (Cronet) drops it. Those
providers return an OkHttp `Interceptor` from `getVideoInterceptor`, which moves
playback onto the data source that does send it.

## Building

The plugins compile against the CloudStream library module, which comes from the
[`cloudstream`](https://github.com/sandeep1027/cloudstream-extend) repository
pulled in as a **git submodule** — only that module is built here, not the app.

```bash
git clone --recurse-submodules <this repo>
cd cloudstream-extend-plugins
echo "sdk.dir=C\:\\Android\\Sdk" > local.properties   # or set ANDROID_HOME
./gradlew :plugins:yts:compileKotlin                  # compile one module
```

Build a `.cs3` (the zip of `classes*.dex` + `manifest.json` the app loads):

+ Linux/macOS: `plugins/<module>/build_cs3.sh`
+ Windows: `plugins/<module>/build_cs3.bat`

Both scripts work in any module directory — they derive the module name from
their own location and the archive name from `manifest.json`. Each prints the
`fileSize` and sha256 `fileHash` to publish.

Packaging is reproducible: `tools/package_cs3.py` and `tools/package_cs3.ps1` pin
every field the zip format would otherwise copy from the filesystem, and write the
same values .NET's `ZipArchive` does. Building the same sources twice gives you
the same bytes and the same hash, on Windows and on the Linux runner alike, so a
moved `fileHash` always means the code actually changed.

### Publishing a new build

Tag the commit and let the workflow do it:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

`.github/workflows/publish-plugins.yml` builds every published plugin, bumps each
version, copies the `.cs3` files into `plugins/<module>/repo/`, rewrites
`repo/plugins.json` with the matching `fileSize`/`fileHash`, commits the result to
`main` and attaches the archives to a GitHub release. The same script runs by
hand from Actions → Publish plugins, where you can publish a subset
(`hianime,anikoto`) or point the published URLs at another branch.

The version bump is the part that matters. The app replaces an installed plugin
only when `plugins.json` advertises a higher version than the one recorded inside
the `.cs3`, so a rebuild published with the same version leaves users on the build
they already have. `tools/publish_plugins.py` bumps `manifest.json` before
compiling (so the number is baked into the archive), copies the same number into
`repo/plugins.json`, and refuses to finish if the two ever disagree.

To do it by hand instead:

```bash
tools/publish_plugins.py                       # every published plugin
tools/publish_plugins.py --modules hianime     # just one
tools/publish_plugins.py --check               # verify what is published, change nothing
```

`--check` is the useful one after editing a manifest by hand: it confirms each
`repo/plugins.json` is still a JSON array, that the URL points at the archive next
to it, that `fileSize` and `fileHash` match the bytes on disk, and that the version
inside the `.cs3` matches the version advertised. The app verifies every download
against that hash, so a `.cs3` and a manifest must always ship together.

If you prefer the raw steps: run the module's `build_cs3` script, copy
`build/cs3/<Name>.cs3` into `plugins/<module>/repo/`, bump the version in both
`manifest.json` and `repo/plugins.json`, and copy the printed `fileSize` /
`fileHash` into `repo/plugins.json`.

## Testing without publishing

Every `.cs3`/`.zip` in `<external storage>/Cloudstream3/plugins/` is loaded at
launch, and there is a hot-reload intent that needs no restart:

```bash
adb push plugins/yts/build/cs3/YTS.cs3 /sdcard/Cloudstream3/plugins/
adb shell am start -a android.intent.action.VIEW -d "cloudstreamapp:"
adb logcat | grep -i pluginmanager
```

On Android 11+ the app needs all-files access to read that folder; if plugins
seem ignored, grant it once with
`adb shell appops set <package> MANAGE_EXTERNAL_STORAGE allow`.

## Credits

+ App: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) (GPL-3.0)
+ Fork and debrid integrations: `sandeep1027`
+ HiAnime / AniKoto / AnimeCube / HDHub4u: ported to the CloudStream plugin API
  from the community provider scrapers by **Spyou** (MIT)
+ Torrin API: [torrin.app](https://torrin.app) · TorBox: [torbox.app](https://torbox.app) ·
  Real-Debrid: [real-debrid.com](https://real-debrid.com) ·
  TMDB: [themoviedb.org](https://www.themoviedb.org/) ·
  Trakt: [trakt.tv](https://trakt.tv) · MDBList: [mdblist.com](https://mdblist.com)

These plugins are not affiliated with or endorsed by any of the sites they read.
They only ever fetch public pages; no copyrighted media is hosted or served here.

## DMCA Disclaimer

This project and its plugins do not host, store, or serve any copyrighted media
content. All plugins only index and aggregate publicly available sources on the
internet, similar to a search engine. No video files, streams, or copyrighted
material are hosted on this project's servers or repositories.

**For Copyright Holders:**

If you believe that your copyrighted work has been infringed upon by any content
indexed by these plugins, please note that:

1. **We do not host any content.** The plugins only provide links to third-party
   sources. Any content is hosted by those third-party sites, not by this project.

2. **Contact the hosting site directly.** If you want content removed, you should
   contact the website that is hosting the content directly. They are responsible
   for the content on their servers.

3. **Debrid services are user accounts.** Plugins that use Torrin, TorBox,
   Real-Debrid, or similar services access content through the user's own account.
   The plugins do not provide access to copyrighted material; users must have
   their own accounts with these services.

4. **We respond to valid legal requests.** If you have a legitimate legal concern
   about a specific plugin's functionality (not the content it indexes), please
   open an issue on this repository or contact the maintainers directly.

**No Liability:**

This project is provided "as is" without warranty of any kind. The maintainers
are not responsible for any misuse of these plugins or any copyright violations
committed by users. Users are responsible for ensuring their use of these plugins
complies with their local laws and regulations.

**Educational Purpose:**

These plugins are developed for educational purposes to demonstrate how to build
CloudStream extensions. Users should only access content they have the legal right
to view in their jurisdiction.