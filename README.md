# LocalBeam

**Play a video from your phone on a DLNA/UPnP TV — no cloud, no accounts, no ads.**

Your phone does the work: it serves the file over your local network and controls the TV
with standard UPnP commands. Nothing leaves the LAN.

<div align="center">

<br>

<!-- Core Status & License Badges -->
[![Release](https://img.shields.io/github/v/release/viswachaitanyasai/LocalBeam?style=flat-square&color=blue&include_prereleases)](https://github.com/viswachaitanyasai/LocalBeam/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

<!-- Key Tech Stack Badges -->
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Android SDK](https://img.shields.io/badge/Android%20SDK-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![DLNA/UPnP](https://img.shields.io/badge/Protocol-DLNA%2FUPnP-orange?style=flat-square)](https://en.wikipedia.org/wiki/Digital_Living_Network_Alliance)

<br>

<!-- Main Download Call to Action -->
[📥 **Download Latest APK**](https://github.com/viswachaitanyasai/LocalBeam/releases/latest)

</div>

---

## Features

- Finds DLNA/UPnP TVs on the local network by itself (SSDP discovery).
- Streams any video from your phone's library over an embedded HTTP server.
- Byte-range support, so you can **seek** on the TV — and a repositioning fallback for
  TVs that refuse UPnP seeks on containers like MKV.
- Remote screen with play/pause, ±10 s, a progress slider and stop.
- Keeps streaming while the screen is off (foreground service).
- No transcoding, no analytics, no cloud service — the only network traffic is on your LAN.

## Requirements

| | |
|---|---|
| Android Studio | Ladybug (2024.2) or newer |
| JDK | 17+ |
| Android SDK | Platform 37 |
| Min Android | 8.0 (API 26) |
| Target Android | 37 |
| Device | A real phone on the same network as the TV (hotspot or shared Wi-Fi) |

Built with Jetpack Compose (Material 3), Kotlin Coroutines/Flow, AndroidX Lifecycle,
[NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (embedded server) and
[OkHttp](https://square.github.io/okhttp/) (SSDP description fetches + SOAP calls).

## Build and run

```bash
git clone <this repo>
cd LocalBeam
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected phone
```

Or open the folder in Android Studio and press **Run**.

An emulator will not work: emulated networking has no SSDP multicast, so it never sees a
real TV.

## Setting it up

1. Put the phone and the TV on the same network:
   - **Hotspot** — enable the phone's hotspot and connect the TV to it.
   - **Shared Wi-Fi** — join both to the same router/access point.
2. Enable UPnP/DLNA on the TV
   (Samsung: *General ▸ Network ▸ Expert Settings ▸ DLNA*;
   LG: *Connection ▸ TV Name / Mobile Connection Management*;
   Android TV / Google TV: usually always on).
3. Grant the permission prompts when the app asks — video access for the library and
   notifications so the streaming notification stays visible.

## Using the app

TV → folder → video → remote.

| Screen | What it does |
|---|---|
| **Scan for TVs** | Shows the address the TV must dial, then lists every MediaRenderer found. A plain MediaRenderer search runs first; a wider `ssdp:all` search follows if nothing answers. |
| **Choose a folder** | Lists the folders that actually contain videos, with a preview frame, video count and total size. "All videos" is offered first. |
| **Choose a video** | Lists the videos with thumbnail, file name, duration, size and container. Tapping one starts the session; picking another switches the TV over. |
| **Remote** | Play/pause, −10 s / +10 s, progress slider, Stop, End session. |

The session survives leaving the app or turning the screen off; the notification has its
own Stop action.

## How it works

```
Phone                                              TV
─────                                              ──
SSDP M-SEARCH for MediaRenderer:1  ──────────────▶ replies with a description URL
fetch + parse description.xml      ◀──────────────  extracts the AVTransport control URL
NanoHTTPD serves the file on <lan ip>:8080
SetAVTransportURI(url, DIDL-Lite)  ──────────────▶  TV starts pulling the file
Play / GetTransportInfo / GetPositionInfo  ◀─────▶  transport state + position, polled 1×/s
```

- **Seeking** is tried as a UPnP `Seek(REL_TIME)` first. If the TV refuses (error 701 is
  common for MKV), the app stops the item, serves the file from the requested offset on a
  **fresh URL** and restarts it, then verifies the TV actually started playing.
- Video rows are labelled with the **file's own name**, not MediaStore's `TITLE`
  (metadata titles are often a single word). `title` is still used on the TV itself.
- The server only ever binds to the hotspot/Wi-Fi address, never `127.0.0.1`, and answers
  `HEAD` and `Range` requests with the DLNA headers renderers expect.

## Project layout

```
app/src/main/java/com/viswa/localbeam
├── LocalBeamApplication.kt   Application, owns the AppContainer
├── MainActivity.kt           Single activity, hosts the Compose tree
├── core/                     AppContainer (manual DI), protocol constants, permissions,
│                             network + time helpers
├── data/
│   ├── local/                MediaStore queries (videos and their folders)
│   ├── model/                LocalVideo, VideoFolder(s), DlnaDevice, TransportState, …
│   └── remote/               SSDP, description parsing, SOAP envelopes, DIDL-Lite
├── server/                   Range/time-seek math, NanoHTTPD server, foreground service
├── cast/                     CastSession: server + UPnP behind one state flow
└── ui/                       Screens (scanner, folders, picker, remote), ViewModels, theme
```

Layers point one way: `ui` → `cast`/`core`, `cast` → `data`/`server`, `data`/`server` →
`core`. Nothing in `data` or `server` touches Compose, and all protocol logic that can be
pure Kotlin is pure — which is why it is unit tested.

## Tests

```bash
./gradlew testDebugUnitTest    # 146 unit tests, no device needed
```

Covered: range and time-seek maths, the real HTTP server on a socket (status codes,
headers, byte-exact bodies, ranges, time seeks), the repositioned-stream byte/time maths,
the SOAP/DIDL round-trip, SSDP parsing, folder grouping, file-name handling and the
transport-state watch.

Not covered, because it needs hardware: SSDP against a real TV and the TV's own playback
behaviour. Those paths are written defensively (fallback searches, retries, short
timeouts) but expect a round of on-device tuning per TV brand.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| No TVs found | Phone and TV are on different networks, or the AP has client isolation. A TV in standby often stops answering SSDP — wake it first. |
| TV shows an error on play | The URL was unreachable or the codec/container is unsupported by that TV. MP4/H.264 is the safest bet. |
| Seeking lands a few seconds off | The TV refused the UPnP seek and the app repositioned the stream; the offset comes from `duration / size`, so it is approximate on variable-bitrate files. Remuxing with `ffmpeg -i in.mkv -c copy -movflags +faststart out.mp4` restores exact seeks. |
| Streaming dies when the screen turns off | Battery optimisation is killing the app. Set it to *Unrestricted* (Settings ▸ Apps ▸ LocalBeam ▸ Battery). |

## Limitations

- One file at a time — no playlist or queue.
- No transcoding: the TV must support the file's codec and container.
- No subtitles and no audio-only playback; volume is the TV's own remote.
- MediaStore only — files not indexed by the media scanner will not appear.
- DLNA/UPnP only — no Chromecast, AirPlay or Miracast.
- On Android 15+ the `dataSync` foreground service is capped at ~6 hours per session.

## Launcher icon

`res/mipmap-anydpi/ic_launcher.xml` is an adaptive icon: a flat brand colour
(`ic_launcher_background.xml`) plus a white glyph (`ic_launcher_foreground.xml`). There are
no raster fallbacks, because they are only read below API 26, which this app does not
support. To replace it, right-click `res` in Android Studio ▸ *New ▸ Image Asset*.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew testDebugUnitTest` before
opening a PR, and keep protocol logic in pure, testable classes.

## License

[MIT](LICENSE) — do whatever you like with the code, including use it commercially. The
only condition is that the copyright notice and permission notice stay with any copy or
substantial portion of it.
