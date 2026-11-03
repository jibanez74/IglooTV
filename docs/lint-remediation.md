# Lint remediation and API 37 migration

This work is split into three local review patches in `/tmp/iglootv-lint-stages/`.
They exclude the pre-existing detail-loading/header cleanup. No lint baseline or global lint
suppression was added. The only exception added is the activity-local `DiscouragedApi` ignore
for the intentional landscape lock on this Leanback-required TV app.

## Stages

| Stage | Changes | Verification |
| --- | --- | --- |
| 1: Code/configuration | Composable helper names; chain-preserving `Modifier.heroHeaderModifier(BoxScope)`; four saveable primitive Double states; 160×90dp banner with unchanged viewport/paths; explicit backup exclusions; landscape rationale | 1,188 unit tests, debug/release APKs, instrumentation APK, lint: 0 errors, 24 warnings |
| 2a: Toolchain | Full Gradle 9.8.2 wrapper and distribution SHA-256; AGP 9.5.0; Kotlin plugins/compiler 2.4.21 | Same build/unit/instrumentation/lint checks passed; lint: 20 warnings |
| 2b: AndroidX/Compose | BOM 2026.09.00; Core 1.19.1; Splashscreen 1.2.0; DataStore 1.3.0; Navigation 2.10.2 | Same checks passed; lint: 15 warnings |
| 2c: Networking/images/serialization | Ktor 3.6.1; Coil 3.6.3; JSON 1.12.0 | Same checks passed; lint: 6 warnings |
| 2d: Playback | All five Media3 modules 1.11.1 | Same checks passed; lint: 1 warning (old target SDK) |
| 3: Android 17 | Target 37 and local-network permission coordinator, shared transport gates, denial/recovery UI and tests, subtitle error race fix, setup documentation | 1,204 unit tests, debug/release APKs, instrumentation APK, debug/release lint: no issues |

The toolchain keeps built-in Kotlin, JDK/JVM 17 and minSdk 28. Resolved
`kotlinCompilerClasspath` and `kotlinCompilerPluginClasspathDebug` both use 2.4.21.
Gradle/AGP are beyond Kotlin's [fully tested compatibility range](https://kotlinlang.org/docs/gradle-configure-project.html);
all local checks above passed without disabling compatibility checks. Both debug and release
Kotlin class files were also checked for JVM class version 61 (Java 17), and the wrapper JAR
was verified against Gradle's published 9.8.2 checksum. Kotlin 2.4's new compiler
diagnostics were addressed for a redundant Unit expression, nullable Java reflection metadata,
experimental serialization descriptor inspection, and redundant non-null assertions in auth tests.
Unrelated dependency pins are retained. The remaining compiler diagnostics in `HomeHero`,
`AlbumDetailsSections`, `DetailsScroll`, and `AudioCapability` predate this work (constant
conditions and deprecated library APIs); final Android lint is clean.

## Permission behavior

On API 37+, setup waits before probing a LAN server and saved-session restoration waits before
loading its credential into an authenticated session. The injectable coordinator also gates Ktor,
Coil, and Media3's stream/HLS/music/artwork sources. Local IPs (including IPv6), `.local` names,
single-label names, and DNS names resolving to a local IP are recognized off the main thread.
Both HTTP and HTTPS use the same policy. Public URLs continue without a LAN grant.
Ktor checks both initial requests and redirects with its
[SendingRequest hook](https://ktor.io/docs/client-custom-plugins.html#handlers). Android's
network stack remains the final authority for redirects and DNS changes between classification
and socket connection, as described in the [platform guidance](https://developer.android.com/privacy-and-security/local-network-permission).

The first blocked operation requests permission through Activity Result. Denial retains pending
work, the server setting and credentials, and offers Retry, Open Settings, and Use another server.
Later requests do not automatically prompt again. Explicit cancellation releases the pending
setup/restore work so another server can connect. On resume and on the permission callback the
grant is read again. Pending coroutines resume once; failed media engines are rebuilt once at
the saved position. The established pause-on-background behavior is retained.

No server/API contracts change. Repositories and transport factories default to unrestricted
access in isolated tests; the production container injects the same permission coordinator into
all of them.

## Configuration inspection

The packaged manifest was inspected with `aapt2 dump xmltree`. Backup is disabled with
`allowBackup=false`, `fullBackupContent=false`, and `dataExtractionRules` referencing explicit
exclusions for all nine supported credential-protected, device-protected, and external storage domains, under
both cloud backup and device transfer. Landscape remains set; HDMI-related `configChanges`
remain `keyboard|keyboardHidden|navigation|screenLayout|screenSize|smallestScreenSize|density`
(`0x1d70`).

The banner was rendered at its xhdpi size (320×180px) and visually inspected: the existing
wordmark and paths remain intact. Only the intrinsic dp dimensions changed. The API 34 layout
suites cover the existing 960×540dp TV viewport and supported UI scales. A manual change from
1920×1080 at 320dpi to 1280×720 at 240dpi preserved the Activity record, process, and
`DecorView` identity, kept profile-picker focus, and rendered correctly in landscape without a
splash replay. The original size/density were restored. This simulates configuration changes;
physical HDMI negotiation still needs a TV device check.

## Android 17 compatibility review

The [target-37 changes](https://developer.android.com/about/versions/17/behavior-changes-17)
and [all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all)
were reviewed against the source. The LAN migration is implemented. The app has no widgets,
SMS/contact access, Bluetooth sockets, downloaded native code, private MessageQueue reflection,
static-final reflection writes, or background activity-launch opt-ins requiring migration.
Players release on background; background audio remains outside the supported feature set.
Playback keeps its existing heap-bounded buffer policy. System TLS trust and certificate
transparency remain enabled; HTTPS servers still need a device-trusted certificate.
Memory-limit behavior, actual platform permission enforcement, and TLS behavior require the
API 37 device validation below.

## Device validation

The existing `Google_TV_API_34_1080p` AVD was booted headlessly. The initial 321-test selection
covered the five affected layout suites, movie/show/album/musician focus/accessibility suites,
music/video screens, ExoPlayer engines and media sessions. It passed 320 tests; the subtitle
fallback test timed out waiting for video to continue. That test passed in isolation immediately
afterwards. The upgraded build reproduced the same failure in a 340-test run (339 passed), while the
39-test ExoPlayer class passed by itself. Logs showed a subtitle 404 arriving as a terminal player
error after the load-failure callback had already rebuilt the source without subtitles. The engine
now remembers that exact discarded IOException and ignores a later player error carrying it;
unrelated source errors still fail normally. Three unit tests cover the identity-based recovery
guard. The combined selection is rerun after this fix below.

**Blocked: API 37 TV validation.** SDK Manager lists no API 37 TV/Google TV system image in this
environment, no such AVD is configured, and no API 37 TV device is attached. API 34 tests and
injected coordinator tests do not validate Android 17's runtime enforcement. Before release,
run on an API 37 TV target:

- First connection and saved-token restoration with grant, denial and permanent denial.
- Retry and return from Settings, with exactly one resume and no automatic prompt loop.
- Permission revocation while browsing, playing video/HLS, and playing music; verify credentials
  remain stored and recovery keeps the saved position and transport intent.
- Public servers while denied, private IPv4/IPv6, `.local` and other LAN DNS hostnames, HTTP and
  HTTPS, redirected resources, artwork, direct streams, HLS manifests/segments, and music.
- Landscape rendering and physical HDMI mode changes without Activity recreation, plus
  memory-constrained playback and trusted HTTPS certificate behavior.

## Reproduction

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest lint lintRelease
./gradlew :app:dependencies --configuration kotlinCompilerClasspath
./gradlew :app:dependencies --configuration kotlinCompilerPluginClasspathDebug
```

The selected TV suites can be rerun on the booted API 34 emulator with:

```bash
adb -s emulator-5554 shell am instrument -w -r \
  -e class "$(cat /tmp/iglootv-lint-stages/final-device-classes.txt)" \
  com.igloo.blindpenguincoder.test/androidx.test.runner.AndroidJUnitRunner
```

Build logs, dependency alignment reports, device logs, the banner rendering, original lint report,
and review patches are retained in `/tmp/iglootv-lint-stages/` for this workspace session.

## Final results

- Full unit suite: **1,204 passed**, no failures, errors, or skips.
- Debug and release APKs and instrumentation APK: **built successfully** on JDK 17.
- Debug and release lint: **No issues found**.
- Permission denial/recovery UI and saved music/video position recovery: **4 focused tests passed**.
- All 11 pre-existing modified/new files were verified byte-for-byte unchanged.
- Final upgraded API 34 TV selection: **340 passed**, including the subtitle fallback, auth,
  restoration, layouts, focus/accessibility, player screens, engines and media sessions.
- Manual resolution/density changes: **passed**, same Activity/view and retained focus;
  original display settings restored.
- API 37 TV enforcement and physical HDMI validation: **blocked by target availability**.
