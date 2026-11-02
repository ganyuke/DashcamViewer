# DashcamViewer Android

Kotlin protocol and Android client for the WANLIPO A13 camera. The project is intentionally limited to this A13 profile; its implementation is original Kotlin.

## Modules

- `:protocol` — pure Kotlin/JVM A13 HTTP protocol, typed results, command preparation/safety policy, media path validation, transfer flow, and RTP/H.264 packet handling.
- `:adapter` — Android Wi-Fi network ownership, network-bound HTTP/RTSP, MediaCodec live rendering, encrypted credential preferences, and pending MediaStore transfer sink.
- `:app` — Compose UI with Camera, Recordings, Downloads, and Settings tabs; ViewModel; Wi-Fi permissions/consent; and the download foreground service.

The reusable entry points are `org.nextcolor.dashcamviewer.protocol.A13Client` and the Android adapter factory `org.nextcolor.dashcamviewer.android.A13ClientFactory`. The app ID is `org.nextcolor.dashcamviewer` (`.debug` suffix for debug builds). A caller must supply a selected Android `Network` and session provider. For remote playback, use `A13MediaPlayback.uri(media)` and `A13MediaPlayback.dataSourceFactory(network, media)` with an item returned by that same client's listing. There is no public arbitrary endpoint API.

Minimal integration sketch (the app owns connection/session state and supplies these callbacks):

```kotlin
val client: A13Client = A13ClientFactory.create(
    network = cameraNetwork,
    session = { sessionStore.current() },
    onSessionInvalidated = { sessionStore.clear() },
)
val snapshot = client.readSnapshot()
val firstLoopPage = client.listFiles(MediaFolder.LOOP, start = 0)
// A listed item can be passed to client.download(item, transferSink).
```

## Build and test

Requires JDK 17 and Android SDK platform/build-tools 36. The Gradle wrapper pins Gradle 8.13. The app targets API 36, supported by the Pixel 7 Pro on Android 16. `targetSdk` is an API level, not a hardware-model selector; this APK can also install on other Android 12+ devices.

```sh
./gradlew :protocol:test
./gradlew :protocol:jar :adapter:assembleRelease
./gradlew :app:assembleDebug
```

The Compose BOM is pinned to `2026.06.01`, resolving Compose UI/Foundation/Animation 1.11.4 and Material 3 1.4.0. This stable release was verified against API 36 and AGP 8.13.2. Compose 1.12.0 raised its requirement to API 37, so the September 2026 BOM cannot be used without upgrading the Android toolchain. See [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation), [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping), and [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

Build outputs are local/generated and ignored by Git: protocol JAR under `protocol/build/libs/`, Android adapter AAR under `adapter/build/outputs/aar/`, and APKs under `app/build/outputs/apk/`. Build commands above recreate them.

## NextColor releases

The repository-level GitHub Actions workflow builds a signed APK when a `v<versionName>` tag is pushed, attests the APK, and attaches it to a GitHub Release. `fastlane/metadata/android/en-US/` provides the store listing consumed by NextColor Apps after this repository is added to its registry. Publishing requires `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD` GitHub Actions secrets.

## Feature and evidence matrix

| Feature | Implementation/evidence | Limit |
|---|---|---|
| A13 identity and status reads | Fixed host, identity gates and typed parsers | Camera network/device not contacted by this implementation build |
| Camera Wi-Fi connection | Exact SSID/passphrase request, local-only request, optional explicit loss consent | Dual-Wi-Fi behavior requires a compatible physical phone |
| Borrow existing Wi-Fi | User-selected existing Wi-Fi `Network`; camera requests use that network | Camera reachability is determined by the A13 identity probe |
| Settings, snapshot, current-clip protect | Whitelisted operations, prepared action, confirmation and readback | Camera setting changes have not been run by this Kotlin app |
| Parking monitor | Always visible; write UI requires option and current-value advertisement | API absent in recorded A13 responses; physical menu remains the supported path |
| File browser | Five known folders, page size 50, listed paths only | Camera-reported dates and sizes are shown as potentially inaccurate/approximate |
| Downloads | One user-started service transfer to pending MediaStore rows; signature/length checks; app-owned history | No automatic retry/resume; physical background behavior not validated |
| Saved playback and photos | Local MediaStore files work offline; remote playback is bound to the selected camera `Network` | Codec/container acceptance depends on the source file and Android device |
| Live view | RTSP video only, channel-2 workaround, RTP/H.264 depacketization, MediaCodec surface | New Kotlin decoder/rendering path is not validated on the physical A13 |
| Clock, camera Wi-Fi credentials/restart, delete, format, reset | Visible experimental controls with fresh warning/typed gates and per-operation outcomes | Experimental; destructive actions were not run |
| Rear feed | Explicitly marked unverified in UI | Not tested on physical hardware |

The APK was built but not installed. All new Android runtime behavior is **Not tested on physical hardware**. Compose instrumentation sources compile, but were not executed on an emulator. Emulator/unit tests do not establish dual-Wi-Fi support, actual A13 live decoding, rear-feed behavior, parking control, or camera power-mode behavior. No camera operation was performed.

## Scope and data handling

The app requests only network/Wi-Fi, API-level Wi-Fi, foreground data-sync, and notification permissions (fine location only through API 32 for the Wi-Fi request). Credentials are optional, unchecked by default, encrypted with an Android Keystore AES-GCM key before DataStore storage, and backup is disabled. Downloads are stored under `Movies/DashcamViewer/` or `Pictures/DashcamViewer/`. It does not use process-wide network binding, cloud services, external runtime destinations, account/analytics services, firmware update flows, automatic clock sync, or broad media-library access.
