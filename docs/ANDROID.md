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

The Compose BOM is pinned to `2026.06.01`, resolving Compose UI/Foundation/Animation 1.11.4. Material 3 is explicitly pinned to `1.5.0-alpha18` to expose the Material 3 Expressive APIs on API 36 and AGP 8.13.2. The newer `1.5.0-beta02` requires API 37 and AGP 9.1; upgrade those together rather than changing this version alone. Compose 1.12.0 raised its requirement to API 37, so the September 2026 BOM cannot be used without upgrading the Android toolchain. See [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation), [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping), and [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

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

The debug APK and instrumentation APK build, Android lint passes, and protocol tests and Compose instrumentation tests run on the API 36 emulator. The UI tests cover connection-state replacement, large-font compact-screen disconnect access, password visibility, navigation, and confirmation gates; they also render populated light/dark screens for visual review. All camera runtime behavior is **Not tested on physical hardware**. Emulator/unit tests do not establish dual-Wi-Fi support, actual A13 live decoding, rear-feed behavior, parking control, or camera power-mode behavior. No camera operation was performed.

## Scope and data handling

The app requests only network/Wi-Fi, API-level Wi-Fi, foreground data-sync, and notification permissions (optional coarse and fine location requested together on every supported Android version to list Wi-Fi names; manual connections use nearby-device access on Android 13+). Credentials are optional, unchecked by default, encrypted with an Android Keystore AES-GCM key before DataStore storage, and backup is disabled. Downloads are stored under `Movies/DashcamViewer/` or `Pictures/DashcamViewer/`. It does not use process-wide network binding, cloud services, external runtime destinations, account/analytics services, firmware update flows, automatic clock sync, or broad media-library access.

## Compose design and review

The app uses `MaterialExpressiveTheme`, system wallpaper colors in light/dark mode, Expressive motion, morphing primary actions, tonal toggle controls, loading indicators and wavy transfer progress. Material Symbols Rounded are compiled as Compose ImageVectors; their Apache license ships in app assets. Android XML resources are limited to platform configuration, startup theme and launcher icons.

`AppShell` uses a short navigation bar on phones and a wide rail from 600 dp. Camera controls and preview use separate columns from 720 dp of available content width. Media lists scroll as a whole, file actions use a bottom sheet, and camera settings group related controls with advanced actions behind a disclosure. The camera connection status is independent of whether Android reports internet access.

Build and run the UI checks with a connected emulator:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :protocol:test
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.nextcolor.dashcamviewer.debug.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/org.nextcolor.dashcamviewer.debug/files/design-review /tmp/dashcam-design-review
```

The review images use explicit camera/media fixtures and do not imply a physical camera connection or successful video decoding.

## Wi-Fi discovery flow

Find camera Wi-Fi requests permissions before discovery, with an explanation of Android's precise-location requirement for Wi-Fi names. The app does not derive a physical location. A user-triggered scan fills an editable SSID dropdown; results are deduplicated by exact SSID and sorted by signal. Hidden networks can be typed manually. Scan throttling, location services disabled, Wi-Fi disabled and permission denial have explicit fallback messages and Android settings links.

Already-connected networks display their SSIDs from location-inclusive network callbacks. If Android redacts a name, the UI explicitly labels it as hidden and offers permission/settings recovery rather than inventing numbered network names. Choosing an existing network checks the A13 identity before showing camera-connected status; discovery never auto-connects.

## Workflow audit

The app observes saved-file history as a stream, so newly published downloads appear without restarting. Transfer completion, failure and cancellation remain visible after cleanup. Saving opens Downloads, with an in-app progress link from other destinations; notification access is optional and offered inline while a transfer is active. Notification taps return to Downloads. Disconnecting during a transfer asks whether to cancel it.

Recordings load when the connected user first opens that destination. Empty results and read failures have different UI, failures have a retry action, and stale selections are cleared when camera verification is lost. Camera mutations disable conflicting controls while awaiting a result.

The media viewer exposes cancellable photo loading, video buffering and playback errors with a way back to files. Live view reports waiting/stalled video based on decoded output. The diagnostic sample picker explains that Android may pause live view and reports how to resume capture rather than swallowing a file-opening error.

SSID/password drafts remain in ViewModel memory across activity recreation; changing the SSID clears the previous password. Optional saved credentials remain encrypted. Tests include actual MediaStore publication into an isolated history store, failure/cancellation feedback, missing-file ExoPlayer errors, loading recovery, activity recreation and network/password changes. These emulator checks do not establish physical A13 compatibility or exhaust every OEM/permission/codec combination.

Connection input validation distinguishes joining an existing Wi-Fi network (normal printable WPA2 passphrases, including spaces/punctuation) from the firmware’s restricted Wi-Fi-setting endpoint. Unsupported input is explained inline. Status-refresh results are ignored after a connection generation changes; failed camera verification/communication does not re-enable stale controls. Live sessions are cancelled and joined before replacement.

## Connection and keyboard corrections

Camera HTTP is permitted only for `192.168.169.1` by the Android network-security configuration. The acquired network is published before the request waiter resumes. Control requests reuse one network-bound HTTP client and cancel through response-body reads. Individual control calls are bounded, optional status reads have a 1-second budget, and initial verification has a 20-second deadline. Unsupported optional firmware replies (`{result: 98}`) do not reject a verified A13 identity.

HTTP failures are communication errors, not evidence that Wi-Fi disconnected. Verification failures retain the selected network. Verify camera retries on that network, Cancel verification retains it, and a session requiring a new check does not display an endless verification spinner. Passive Wi-Fi observers do not require internet/trusted capability transitions.

Screen headings contain only their names; taglines and decorative heading icons are removed. Tab replacement is immediate. The IME inset is applied once, content consumes Scaffold padding, and navigation is hidden while typing. SSID results use inline Material list items, open only on explicit interaction/search, and close on selection/navigation. SSID Next focuses the password; password Done dismisses the keyboard.

Regression sources include CameraNetworkPolicyTest, ConnectionVerificationTest, KeyboardUiTest, CancellableHttpTest, and the protocol tests. Physical A13 connectivity is not verified from this workspace; its read-only endpoint was not reachable here.

Final validation: debug app/test APK builds, all 24 unit tests, and lint pass. The five targeted API 36 emulator tests pass: camera HTTP policy, verification timeout/retry, verification cancellation, Gboard keyboard layout, and returning to Camera without reopening SSID suggestions. Logs are under `build/ui-review/final/`. Physical A13 connection remains unverified.
