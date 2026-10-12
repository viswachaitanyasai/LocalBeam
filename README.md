# LocalBeam

A small, ad-free Android app that plays a video from this phone on a DLNA/UPnP TV.

It does three things:

1. finds DLNA MediaRenderers on the local network (SSDP/M-SEARCH),
2. serves a chosen video file from the phone over an embedded HTTP server (with byte
   ranges, so the TV can seek),
3. controls the TV over UPnP AVTransport (play, pause, seek, stop).

No cloud, no accounts, no analytics. The phone is the server, the TV is the player.

---

## 1. Requirements

| | |
|---|---|
| Android Studio | Ladybug (2024.2) or newer – the project uses AGP 9.x |
| JDK | 17+ (bundled with Android Studio) |
| Android SDK | Platform 37, build tools installed |
| Min Android | 8.0 (API 26) |
| Target Android | 37 |
| Device | A phone that can act as a hotspot **or** the same Wi-Fi network as the TV |

Libraries used: Jetpack Compose (Material 3), Kotlinx Coroutines/Flow, AndroidX
Lifecycle (ViewModel + Compose), **NanoHTTPD** (embedded server), **OkHttp** (device
description fetches + SOAP calls). Total networking footprint is a few hundred KB.

## 2. Setup in Android Studio

```bash
git clone <this repo>
# Android Studio: File ▸ Open ▸ select the LocalBeam folder
```

1. Let Gradle sync finish (it downloads NanoHTTPD and OkHttp on first sync).
2. Pick a device: a real phone is required. **An emulator cannot test this**, because
   emulated networking has no SSDP multicast between the emulator and a TV.
3. `Run ▸ Run 'app'` (or `./gradlew installDebug` from the terminal).
4. Put the phone and the TV on the same network:
   * **Hotspot mode** – turn on the phone's hotspot and connect the TV to it. The app
     will bind its server to the hotspot address (typically `192.168.x.1`).
   * **Shared Wi-Fi** – join both to the same router/AP.
5. Make sure UPnP/DLNA is enabled on the TV (Samsung: *General ▸ Network ▸ Expert
   Settings ▸ DLNA*; LG: *Connection ▸ TV Name / Mobile Connection Management*;
   Android TV / Google TV: usually always on).

Runtime permissions: `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE`
(Android 8–12) for the video list, and `POST_NOTIFICATIONS` (Android 13+) so the
"streaming" notification stays visible. Both are requested at the right moment by the
UI, not at launch.

## 3. Using the app

| Screen | What it does |
|---|---|
| **Scan for TVs** | Shows the address the TV must dial, then lists every MediaRenderer found (friendly name, manufacturer/model, IP, port). A plain MediaRenderer search runs first; if nothing answers, a wider `ssdp:all` search runs automatically. |
| **Choose a folder** | Lists the folders that actually contain videos, with a preview frame, video count and total size. "All videos" is offered first for browsing the whole library at once. |
| **Choose a video** | Lists the videos inside the chosen folder (or all of them) with thumbnail, the file's full name (wrapping onto up to three lines), duration, size and container. Tapping the row starts the session; picking another video while one is playing switches the TV over to it. |
| **Remote** | Video title, live transport state, play/pause, −10 s / +10 s, a progress slider, Stop (stops the TV, keeps the server) and End session (stops everything). |

The picker is folder-first: TV → folder → video → remote. MediaStore is queried once and
both picker screens share the same ViewModel, so opening a folder is instant and the
chosen folder is remembered when you come back from a finished session.

Video rows are labelled with the **file's own name** (`LocalVideo.displayName`), not with
MediaStore's `TITLE`. `TITLE` is filled from the container's metadata when a file has any,
and a one-word title tag is common – that is how a list ends up showing "Big" for
"Big Buck Bunny 2010 1080p.mkv". `title` is still used where a presentation name is
better: the DIDL-Lite title shown on the TV, and the streaming notification.

The session survives leaving the app or turning the screen off: the server runs in a
foreground service with a notification, and a "Stop" action in that notification.

## 4. Code structure

```
com.example.localbeam
├── LocalBeamApplication.kt        Application; owns the AppContainer
├── MainActivity.kt                Single activity, hosts the Compose tree
├── core/
│   ├── AppContainer.kt            Manual DI (one OkHttp client, one CastSession)
│   ├── CastConstants.kt           SSDP/UPnP/DLNA protocol constants
│   ├── MediaPermissions.kt        API-level aware permission helpers
│   ├── NetworkUtils.kt            Finds the hotspot/Wi-Fi IPv4 address to bind + advertise
│   └── TimeUtils.kt               HH:MM:SS parsing/formatting for UPnP
├── data/
│   ├── local/MediaStoreVideoSource.kt      Queries the library and each file's folder
│   ├── model/                              DlnaDevice, LocalVideo, VideoFolder(s),
│   │                                       TransportState, VideoFormats, PlaybackStartWatch
│   └── remote/
│       ├── SsdpParser.kt                   Parses SSDP datagrams (pure)
│       ├── SsdpDiscoveryClient.kt          MulticastSocket + MulticastLock + description fetch
│       ├── DeviceDescriptionParser.kt      XmlPullParser over description.xml -> controlURL
│       ├── UrlResolver.kt                  Resolves relative control URLs
│       ├── SoapXml.kt                      Escaping, response value + fault extraction (pure)
│       ├── SoapEnvelope.kt                 SOAP request bodies for AVTransport actions
│       ├── DidlLite.kt                     DIDL-Lite metadata for SetAVTransportURI
│       └── UpnpControlPoint.kt             OkHttp SOAP client (Play/Pause/Seek/…)
├── server/
│   ├── RangeSpec.kt                       HTTP Range parsing (pure)
│   ├── DlnaTimeSeek.kt                    TimeSeekRange (npt) <-> byte offset (pure)
│   ├── StreamContent.kt                   What is being served (file + resume position)
│   ├── StreamWindow.kt                    The served slice of the file, in bytes and time (pure)
│   ├── VideoByteSource.kt                 Random access to the file (+ ContentResolver impl)
│   ├── VideoHttpServer.kt                 NanoHTTPD server: HEAD, ranges, DLNA headers
│   ├── LocalServerBridge.kt               Server state as a StateFlow (service -> UI)
│   └── StreamingService.kt                Foreground service owning the server
├── cast/CastSession.kt            Orchestrates server + UPnP into one session state
└── ui/                            Screens (scanner, folders, picker, remote), ViewModels,
                                   shared components, theme
```

Layer rules: `ui` → `cast`/`core`, `cast` → `data`/`server`, `data`/`server` → `core`.
Nothing in `data` or `server` knows about Compose, and every piece of protocol logic
that can be pure Kotlin is pure, which is why it is unit tested.

## 5. How the protocol part works

**Discovery.** `SsdpDiscoveryClient` takes a `WifiManager.MulticastLock` (hotspots drop
multicast otherwise), binds a `MulticastSocket` to an ephemeral port on the
hotspot/Wi-Fi interface, joins `239.255.255.250:1900` and sends an `M-SEARCH` for
`urn:schemas-upnp-org:device:MediaRenderer:1` three times, plus a copy to the subnet
broadcast address for access points that filter link-local multicast. Replies are
de-duplicated by the `uuid:` prefix of their `USN`.

**Device description.** Each reply's `LOCATION` is fetched and parsed with the
platform `XmlPullParser`. The AVTransport `controlURL` is resolved against the
description URL (relative, root-relative and absolute forms all occur in the wild) and
the renderer's friendly name is taken from the embedded device that actually exposes
AVTransport, not necessarily the root device.

**Starting playback.**
```
StreamingService.start(video)            → foreground service, binds server on <hotspot ip>:8080
LocalServerBridge.state == Ready         → CastSession waits for the URL (8 s timeout)
SetAVTransportURI(url, DIDL-Lite)        → SOAP POST to the control URL
Play(Speed=1)                            → 350 ms later, some TVs ignore it otherwise
GetTransportInfo + GetPositionInfo       → polled every second for the UI
```

**Serving the file.** The server binds to the hotspot/Wi-Fi address only, never
`127.0.0.1`. It answers `HEAD` with the real size (renderers probe first), answers
single `Range` requests with `206` + `Content-Range` (this is what makes seeking work),
translates DLNA `TimeSeekRange.dlna.org` (`npt=`) into a byte offset when a renderer
seeks by time, and always sends `contentFeatures.dlna.org` / `transferMode.dlna.org`.
The DIDL-Lite metadata advertises `DLNA.ORG_OP=01`, i.e. byte-seek support, and includes
`duration` and `size` so the TV can map a `Seek(REL_TIME)` target onto the file.

Each request opens its own file descriptor: a renderer routinely starts the next range
before dropping the previous socket, and two streams sharing one file position would
corrupt each other.

**Seeking when the TV refuses.** Plenty of renderers answer `Seek` with UPnP error 701
("transition not available") for containers they cannot index, Matroska in particular,
and the app used to be stuck with an unseekable film. Now a seek has two stages:

```
1. Seek(REL_TIME, target)          → instant and exact when the TV supports it
   └─ refused? wait for PLAYING, retry once (the 701 is often "still starting up")
2. otherwise: reposition the stream
   Stop → settle → serve the file from <target> on a NEW url → SetAVTransportURI → Play
   └─ verify it actually started; if not, serve the whole file again and say why
```

`StreamContent.startSeconds` is what makes stage 2 possible: the server then behaves as
if the file began at that timestamp (`StreamWindow`), so byte 0 of the stream is the
requested position, `HEAD`/`Content-Range` describe what is left of the film, and the TV
receives a brand-new item that starts exactly there. The DIDL metadata reports the
remaining `duration`/`size`, and `CastSessionState.streamStartSeconds` offsets everything
the renderer reports back into film time.

The byte offset is proportional to `duration / size`, so it is approximate on
variable-bitrate files – which is why stage 1 is always tried first.

Three details in stage 2 come from real renderers, not from the spec:

* **A fresh URL per stream.** `StreamContent.token` puts a short random token in the
  file name (`video-a1b2c3d4.mkv`), keeping the extension last because TVs sniff it.
  Renderers key item state on the URL, and Samsung TVs answer *"this file format is not
  supported"* when they are handed a URL they have already played and stopped – which is
  precisely what a repositioned stream used to be. The token is new for every cast and
  every reposition, so switching videos gets the same protection.
* **A beat between `Stop` and the next item**, since renderers still hold the previous
  item's resources for a moment; the handoff is retried once if it is refused anyway.
* **A refusal is detected, not assumed.** A SOAP `200` is not proof of playback, so after
  a reposition `PlaybackStartWatch` watches `GetTransportInfo`: `TRANSITIONING`/`PLAYING`
  means it took the item, while a *persistent* `STOPPED`/`NO_MEDIA` means it will not play
  it. In that case the app serves the whole file again (so the film is never left on the
  TV's error screen) and explains that the file can be played but not sought.

## 6. Tests

```bash
./gradlew testDebugUnitTest     # 146 unit tests
./gradlew assembleDebug         # builds app-debug.apk
```

Covered without a device:

* `RangeSpec`, `DlnaTimeSeek` – range/time-seek maths, including suffix ranges,
  over-long ranges and garbage headers;
* `VideoHttpServerTest` – starts the **real server on a socket** and asserts status
  codes, headers and byte-exact bodies for `HEAD`, full `GET`, `Range`, suffix range,
  `416`, multi-range fallback, time seek and the debug index page, plus a second server
  per repositioned stream (window start, in-window ranges, in-window time seek, the
  no-duration fallback and a seek to the very end);
* `StreamWindow` / `StreamContent` – the byte+time maths of a repositioned stream: the
  proportional offset, what is left to serve, the mapping of window positions back to
  film positions, and the clamps that keep at least one byte to send;
* `CastSessionState` – when entering the remote has to (re)start a stream, i.e. the
  difference between "already playing this" and "the user picked something else";
* `LocalVideo` – the name the picker shows: the file's own name without its extension,
  kept intact when MediaStore's `TITLE` holds only a word, with fallbacks when there is no
  file name, plus which extension the stream will advertise;
* `PlaybackStartWatch` – the difference between a renderer that is about to play and one
  that took the command and refused the media: stopped-then-playing, a persistent stop,
  buffering that clears an earlier stop, and a renderer that only ever answers `UNKNOWN`;
* `SsdpParser` – reply parsing, de-duplication key, MediaRenderer detection, M-SEARCH body;
* `SoapXml` / `SoapEnvelope` / `DidlLite` – escaping, envelope shape, fault messages and a
  title-with-`&`-round-trip through envelope → DIDL → title, and the metadata a
  repositioned stream is described with;
* `NetworkUtils` – address ranking (hotspot beats Wi-Fi, cellular/p2p never win);
* `VideoFolders` – folder grouping plus the path handling behind it: full paths, Android
  10+ relative paths, bucket-name fallbacks, SD cards, and videos sitting in the root of a
  volume. Both picker screens are driven by this one ViewModel state, so the grouping is
  where the folder feature's logic lives;
* `UrlResolver`, `TimeUtils`, `VideoFormats`, `TransportState`.

Not covered by tests, because it needs hardware: SSDP against a real TV, and the TV's
own playback behaviour. Those paths are written defensively (fallback searches, retry
burst, short timeouts) but expect a round of on-device tuning per TV brand.

## 7. Troubleshooting

**No TVs found.** Nine times out of ten the phone and TV are on different networks, or
the AP has client isolation. Check the address shown at the top of the scan screen. A
TV in standby often stops answering SSDP – wake it first. Some TVs only advertise while
on their home screen.

**Found the TV, but it shows an error when playing.** The URL was unreachable or the
format was rejected. Check that the address in the server card matches the network the
TV is on, and that the file is a container/codec the TV supports.

**Seeking is slow, or lands a few seconds off.** The TV refused the UPnP `Seek`, so the
app repositioned the stream instead: playback restarts a moment later at a position
derived from `duration / size`. That is exact for constant-bitrate files and a few
seconds out for variable-bitrate ones. Remuxing to MP4 with
`ffmpeg -i in.mkv -c copy -movflags +faststart out.mp4` usually gets the exact behaviour
back, because the TV can then seek by itself.

**Seeking shows "this file format is not supported" on the TV.** That is the renderer
refusing to open the resumed stream, not a problem with the file – it plays fine from the
start. The app answers by serving the whole file again and telling you so. If it happens
on every seek, the film is one this TV can decode but not random-access; remuxing to MP4
(`-c copy -movflags +faststart`) is the fix. If it happens *only* on the first seek after
a cast, it is usually a TV that needs a moment: the app already waits 600 ms and retries
the handoff once, so please report the model.

**Playback dies when the screen turns off.** It should not: the server runs in a
foreground service with a partial wake lock, and `android:stopWithTask="false"` keeps it
alive. Make sure battery optimisation is not killing the app (Settings ▸ Apps ▸ LocalBeam
▸ Battery ▸ Unrestricted on aggressive OEM builds).

## 8. Known limitations

* One file at a time, no playlist, no queue.
* No transcoding: the TV must support the file's codec and container (MP4/H.264 is the
  safest bet). That is deliberate – transcoding would need an encoder and much more CPU.
* No subtitles, no audio-only support, and no playback volume control – use the TV's own
  remote for that.
* A repositioned stream (see §5) restarts the file at an offset; the landing point is
  approximate on variable-bitrate files, and the TV's own progress bar then covers only
  what is left of the film.
* MediaStore only. Videos not indexed by the media scanner (or only reachable through
  the Storage Access Framework) will not appear.
* DLNA/UPnP only – no Chromecast, AirPlay or Miracast.
* Non-Android-11+ devices report fewer interfaces to `NetworkInterface`, in which case
  the address shown on the scan screen is the best available guess; use the hotspot.
* The service declares `mediaPlayback|dataSync` foreground types. On Android 15+ a
  `dataSync` service is capped at ~6 hours, after which the session ends cleanly rather
  than being killed mid-stream.

## 9. The launcher icon

The icon is an **adaptive icon**, which is the only kind you need at `minSdk 26`:

```
AndroidManifest.xml      android:icon="@mipmap/ic_launcher"
                         android:roundIcon="@mipmap/ic_launcher_round"
mipmap-anydpi/ic_launcher.xml        <adaptive-icon>: background + foreground + monochrome
mipmap-anydpi/ic_launcher_round.xml  same art; the launcher applies the round mask
drawable/ic_launcher_background.xml  flat brand colour (#6650A4, the app's primary)
drawable/ic_launcher_foreground.xml  the cast glyph, white
```

There are deliberately no `mipmap-*dpi` PNG/WebP rasters: they are only read below API 26,
which this app does not support.

**Replacing the artwork**, easiest first:

1. **Android Studio's wizard** – right-click `res` ▸ *New ▸ Image Asset*, name it
   `ic_launcher`, choose your PNG/SVG as the *Foreground layer*, pick a background colour
   or image, tick *Legacy* + *Round*, Finish. It rewrites the two adaptive XMLs and adds
   the density rasters back; the manifest already points at them, so nothing else changes.
2. **Edit the two vectors by hand** – swap the `<path>` in `ic_launcher_background.xml`
   for a flat colour and replace the glyph in `ic_launcher_foreground.xml`.
3. **Use a bitmap** – `<foreground android:drawable="@drawable/my_logo" />` in the adaptive
   XML, sizing and insetting it yourself.

Keep the glyph inside the middle 72×72 of the 108×108 canvas, ideally inside a
66 dp-diameter circle: everything outside is cropped, so a square logo loses its corners
on a round mask. `ic_launcher_foreground.xml` carries the transform that puts the cast
glyph in that safe area.

Two details that are easy to get wrong:

* **Keep the foreground a solid silhouette.** Android 13+ themed icons are generated from
  the `<monochrome>` entry, which points at the foreground, and a multi-colour or gradient
  logo tints badly.
* **The notification icon is a separate file** – `drawable/ic_stat_cast.xml`, white on
  transparent, because Android tints notification small icons. Do not point it at the
  launcher icon.

After installing a new icon, uninstall/reinstall (or restart the launcher) if the home
screen still shows the old one: launchers cache icons.
