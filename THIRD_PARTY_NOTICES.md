# Third-party notices

Igloo TV is licensed under the [GNU General Public License v3.0 or later](LICENSE). The app bundles the third-party libraries listed below, each under its own license. Those licenses continue to apply to their components.

All libraries listed here are licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0), which is compatible with GPLv3. Versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Runtime libraries (included in the APK)

| Library | Group | License |
| --- | --- | --- |
| Jetpack Compose (foundation, ui, ui-graphics, ui-tooling-preview) | `androidx.compose` | Apache-2.0 |
| Compose for TV Material | `androidx.tv` | Apache-2.0 |
| AndroidX Activity, Core, Core SplashScreen | `androidx.activity`, `androidx.core` | Apache-2.0 |
| AndroidX Lifecycle, Navigation, DataStore | `androidx.lifecycle`, `androidx.navigation`, `androidx.datastore` | Apache-2.0 |
| Media3 (ExoPlayer, HLS, session, UI, UI Compose) | `androidx.media3` | Apache-2.0 |
| Coil 3 (compose, network-okhttp) | `io.coil-kt.coil3` | Apache-2.0 |
| Ktor client (core, OkHttp engine, content negotiation, kotlinx-json) | `io.ktor` | Apache-2.0 |
| OkHttp (transitive, via Ktor and Coil) | `com.squareup.okhttp3` | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | `org.jetbrains.kotlinx` | Apache-2.0 |
| Kotlin standard library | `org.jetbrains.kotlin` | Apache-2.0 |
| ZXing core | `com.google.zxing` | Apache-2.0 |

Test-only and debug-only dependencies (JUnit, AndroidX Test, UI Automator, Compose UI test, ui-tooling, coil-test, ktor-client-mock) are not part of release builds.

This table lists direct runtime dependencies and notable transitive ones. Release builds distributed to users, including through Google Play, should include the full license texts and notices of every bundled library, for example via an in-app "Open source licenses" screen generated at build time.

## Media codecs

The app does not currently bundle an FFmpeg decoder. If an FFmpeg-based Media3 decoder extension is added, its FFmpeg build is licensed under the LGPL or GPL, depending on how it is configured. That component and a link to its source must then be added to this file.
