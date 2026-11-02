# Requirement-to-evidence checklist

This checklist distinguishes checked implementation behavior from hardware acceptance. Automated test coverage is fixture/fake based; it never targets the camera.

## Project and protocol

- [x] Three modules (`protocol`, `android`, `app`), pinned catalog, Gradle 8.13 wrapper, Java 17, min SDK 31, compile/target SDK 36, `org.nextcolor.dashcamviewer` package root and app ID.
- [x] App, adapter, and protocol packages use the `org.nextcolor.dashcamviewer` namespace family.
- [x] Tag-triggered release workflow builds the release APK, applies GitHub build attestation, and attaches the APK to a GitHub Release.
- [x] NextColor-compatible Fastlane listing and release setup instructions are present.
- [ ] Central NextColor Apps registry entry: requires the canonical source repository URL; no public repository is configured for this workspace yet.
- [ ] Production signing: configure stable GitHub Actions keystore secrets before creating a release. Local release build used the debug key fallback and is not a distributable signed release.
- [x] Public typed camera, media, feature-support, evidence, outcome, and read-error models; `A13Client` has no arbitrary HTTP operation.
- [x] Fixed A13 identity validation and sanitized snapshot fixture.
- [x] Allowed endpoint map and listed `/mnt/card/` file validation; no arbitrary endpoint/path entry.
- [x] No decompiled proprietary or AGPL implementation source copied.
- [x] Protocol fixture and RTP suites: `:protocol:test` passes (15 tests at last completed run).
- [ ] Expand protocol fake-server coverage to every endpoint encoding and every specified RTP edge case.

## Network ownership and security

- [x] Local-only `WifiNetworkSpecifier`, exact SSID/passphrase entry, explicit unsupported-concurrency loss-consent gate, selected-network HTTP socket factory, explicitly network-bound RTSP socket.
- [x] No `bindProcessToNetwork`, no proxy/redirect/retry, no external connectivity probe, no fallback network in the Android camera transports.
- [x] Borrowed vs requested connection is represented separately; app releases only its own callback.
- [x] Wi-Fi/network permissions are limited; fine location is capped at API 32 and nearby-device permission has `neverForLocation`.
- [x] Remembered credentials are optional and AES-GCM encrypted with Keystore; remember defaults unchecked; application backup disabled.
- [ ] Verify callback cleanup, permission denial, timeout, and network loss with Android instrumented fake-network tests.
- [ ] Verify concurrent Wi-Fi on supporting and non-supporting physical phones.

## Camera operations and safety

- [x] Settings whitelist, advertised-value checks, prepared actions with UUID/generation/expiry/single-use, confirmation checks, mutation serialization, and no automatic mutation retry.
- [x] Resolution mismatch remains visible and prevents resolution writes.
- [x] Parking stays visible; setter requires both advertised options and current value; absent-state text is exact; no bypass.
- [x] Experimental clock/Wi-Fi/maintenance/delete controls stay visible with fresh acknowledgement and typed phrases where required.
- [x] Format/reset require recording confirmed off and no media activity; application does not stop recording as cleanup.
- [x] Snapshot, lock-current readback, setting readback, delete absence check, and unconfirmed-on-timeout outcomes are represented.
- [ ] Full Compose confirmation/recomposition and mutation-zero-write instrumentation coverage.
- [ ] Physical operation acceptance for non-destructive controls (separately authorized only).

## Media and downloads

- [x] Folder listing pages are 50 items, exact listed media paths are tied to camera identity/session, supported file extensions are constrained.
- [x] Remote range playback uses selected Android `Network` and one listing-issued URI; local playback does not need camera connectivity.
- [x] MediaStore writes use pending rows, collision-safe names, signature checks, publish-on-success, and cleanup of the transfer's own pending row.
- [x] A single foreground `dataSync` service shows progress and cancellation; service timeout cancels the transfer.
- [x] JPEG remote view uses the protocol's listing-issued download flow and a bounded in-memory sink.
- [x] Live receiver is video-only and implements SDP/RTP H.264 packet forms plus channel 2; Android decoder targets a Surface.
- [ ] Instrumented tests for MediaStore interruption/recovery and foreground-service lifecycle.
- [ ] Physical tests for full 4K transfer, local audio/video, screen-off background transfer, power loss, and 720p live decoding.

## Completion gates / unresolved blockers

- [x] JAR produced: `protocol/build/libs/protocol-0.1.0.jar` (generated, ignored output).
- [x] AAR produced: `adapter/build/outputs/aar/adapter-release.aar` (generated, ignored output).
- [x] Compose instrumentation-test sources compile; tests were not executed on an emulator.
- [x] Debug APK built for API 36 / Android 16: `app/build/outputs/apk/debug/app-debug.apk` (generated, ignored output).
- [x] Compatible stable Compose pin: BOM 2026.06.01 resolves Compose UI 1.11.4 / Material 3 1.4.0 and passes AAR metadata with AGP 8.13.2 and compile SDK 36. Compose 1.12 is excluded because it requires API 37.
- [ ] Physical Android runtime acceptance: **Not tested on physical hardware**.
- [ ] Dual Wi-Fi, rear video, parking writes, and parking power behavior: **Not verified on physical hardware**.
