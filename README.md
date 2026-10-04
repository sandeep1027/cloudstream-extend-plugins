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
| **BollyFlix** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/bollyflix/repo/repository.json` | a debrid account |
| **YTS** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/yts/repo/repository.json` | a debrid account |
| **Torrin** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin/repo/repository.json` | debrid + TMDB key |
| **Torrin MDBList** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-mdblist/repo/repository.json` | debrid + TMDB key + MDBList key |
| **Torrin Trakt** | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-trakt/repo/repository.json` | debrid + TMDB key + Trakt id |

The first four stream directly and need no key. **BollyFlix** and **YTS** are
metadata/torrent sources: the site serves no video, so every link is a magnet
resolved through your **Torrin / TorBox / Real-Debrid** account (Settings → Player
→ Debrid). The Torrin plugins additionally want a free TMDB key in
Settings → Player → Metadata.

Home rows are shown for **one provider at a time** — tap the provider chip at the
bottom of Home and pick the one you want. Home also filters by content type, and
only **Movies** and **TV Series** are selected by default, so the anime-only
sources (HiAnime, AniKoto, AnimeCube) need the **Anime** chip enabled.

## Plugins

| Module | Source | What it does |
|---|---|---|
| `plugins/hianime` | hianime.at | Anime: home rows, search, detail, Sub/Dub episodes. Streams via VidPlay/MegaPlay `getSources` and Zoko's player blob into m3u8/MP4 with subtitles. |
| `plugins/anikoto` | anikototv.to | Anime: home rows, search, sub/dub, streams through the site's server chain including MegaPlay's AES-encrypted sources. |
| `plugins/animecube` | animecube.live | Anime: listings and episodes from the site's Next.js payload, streams from its sources endpoint, Dailymotion/Rumble expanded to HLS/MP4. |
| `plugins/hdhub4u` | HDHub4u | Movies and series (Hindi/Hollywood): home rows, search, episodes, streams through the site's link-bypass hops. |
| `plugins/bollyflix` | new.bollyflix.vote | Bollywood/Hollywood/dual-audio/Korean catalogue from the site's WordPress REST API, with the per-title quality table. Emits magnets for debrid playback. |
| `plugins/yts` | en.yts.lu | Movies and shows with home rows per streaming service (Netflix, Prime Video, Disney+, Max, Hulu + TV equivalents), This Week / Today, Indian rows, genres. Magnets labelled with quality, size and seeders. |
| `plugins/torrin` | — | Curated dashboard and "Latest on Netflix / Hotstar / ZEE5 / SonyLIV" rows, Torrentio + debrid playback. |
| `plugins/torrin-mdblist` | MDBList | "Latest Movies" / "Latest Shows" rows plus trending. |
| `plugins/torrin-trakt` | Trakt | "Latest Movies" / "Latest Episodes" rows from the public Trakt calendar. |
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

### Publishing a new build

1. Run the module's `build_cs3` script.
2. Copy `build/cs3/<Name>.cs3` into `plugins/<module>/repo/`.
3. Copy the printed `fileSize` / `fileHash` into `plugins/<module>/repo/plugins.json`.
4. Commit and push. Users get the update on the next plugin sync.

The app verifies every download against that hash, so steps 2–3 must ship together.

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