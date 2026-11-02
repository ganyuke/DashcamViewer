# DashcamViewer

DashcamViewer is an Android 12+ app and Kotlin client library for the WANLIPO A13 dash camera. It connects locally to the camera, reads status and recordings, supports foreground live viewing, and saves user-selected media to the phone. The project has three sibling Gradle modules: [`app/`](app/), [`adapter/`](adapter/), and [`protocol/`](protocol/).

The app is intentionally limited to the investigated A13 protocol. Features that the camera does not advertise remain unavailable; the app does not use Viidure, cloud services, firmware updates, or arbitrary camera endpoints.

## Build

Requires JDK 17 and Android SDK platform/build-tools 36.

```sh
./gradlew :protocol:test
./gradlew :protocol:jar :adapter:assembleRelease
./gradlew :app:assembleDebug
```

Build outputs are generated locally and are not committed. See [docs/ANDROID.md](docs/ANDROID.md) for the detailed module guide and compatibility notes.

## Releases

The root GitHub Actions workflow publishes a signed, attested APK for a pushed tag matching the `versionName` in `app/build.gradle.kts`. Releases need the stable signing-key secrets `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD`. The NextColor Apps listing lives under `fastlane/metadata/android/en-US/` and is imported after the app repository is added to the NextColor registry.
