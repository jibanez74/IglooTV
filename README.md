# Igloo TV

IglooTV is an Android TV and Fire TV client for [Igloo](https://github.com/jibanez74/Igloo),
a self-hosted media center. Built with Kotlin and Jetpack Compose for TV, it connects to your
Igloo server so you can browse and play your library using a TV remote.

## Features and status

- Browse Home, Movies, TV Shows, and Music, including movie, show, album, and musician details,
  with a show's seasons and episodes.
- Play movies and resume episodes from Home's Continue Watching section, with Direct or
  server-produced HLS playback, audio/subtitle selection, and saved watch progress.
- Play music from albums, musicians, or the track library, including shuffle.
- Sign in with Quick Connect or email/password, save multiple household profiles, and unlock
  PIN-protected profiles. Sign-out applies to the selected profile.
- Navigate with a D-pad and remote, with focus treatments and TalkBack support.

**Development is ongoing; the app is pre-production.** The show details page is browse-only for
now: its Play/Resume button, episodes, and extra videos do not play yet, and episodes cannot be
marked watched from it. Search, Photos, and Settings are placeholder destinations. Playlists, episode up-next, music play-stat reporting, and background
music playback are also unfinished. See [known issues](docs/known-issues.md) and the
[development guide](docs/development.md#feature-status-and-reference-documents) for more context.

## Requirements

- A running [Igloo server](https://github.com/jibanez74/Igloo) reachable from the TV, and an
  account on that server. The server is set up separately.
- An Android TV / Google TV device or emulator, or a compatible Fire TV device, running API 28
  (Android 9) or later. Use a TV system image for an emulator.
- Android Studio compatible with the Android Gradle Plugin in
  [the version catalog](gradle/libs.versions.toml).
- JDK 17 or a newer JDK compatible with the Gradle wrapper and Android Gradle Plugin. Java source
  and target compatibility are 17; AGP's minimum is documented in its
  [compatibility requirements](https://developer.android.com/build/releases/agp-9-2-0-release-notes#compatibility).
- Android SDK Platform 37 and SDK Build-Tools, plus Android SDK Platform-Tools (`adb`). Install
  Android Emulator and a TV system image if using an emulator.
- Git to clone the repository.

The build uses `compileSdk = 37`, `targetSdk = 36`, and `minSdk = 28`. Build settings live in
[app/build.gradle.kts](app/build.gradle.kts); dependency versions live in the version catalog.
Use the included Gradle wrapper.

## Build and run

### Android Studio

1. Clone the repository:

   ```bash
   git clone https://github.com/jibanez74/IglooTV.git
   cd IglooTV
   ```

2. In Android Studio, choose **Open** and select the `IglooTV` directory.
3. Open **SDK Manager** and install Android SDK Platform 37, SDK Build-Tools, and Platform-Tools.
   For an emulator, also install Android Emulator and create an Android TV / Google TV AVD in
   **Device Manager**, using an API level of at least 28.
4. In **Settings → Build, Execution, Deployment → Build Tools → Gradle**, select a compatible
   **Gradle JDK** (see Requirements). Let Gradle sync finish and install any requested SDK components.
5. Start the TV emulator, or enable debugging on your physical TV and connect it with `adb`.
   Accept the debugging authorization prompt on the device.
6. Select the **app** run configuration and your TV target, then click **Run**.

Once the app opens, continue with [First launch](#first-launch).

### Command line

After cloning and installing the prerequisites above, run these commands from the repository root.
Set `JAVA_HOME` to your compatible JDK and put the SDK's `platform-tools` directory on `PATH`.
Android Studio normally records your SDK location in the untracked `local.properties` file;
without it, set `ANDROID_HOME` to your Android SDK directory.

```bash
adb devices
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.igloo.blindpenguincoder/.MainActivity
```

Start the emulator or connect the TV before running `adb devices`; it should show a target with
status `device`. If several targets are connected, use `adb -s <serial>` for each device command.
On Windows, use `gradlew.bat` in place of `./gradlew`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The install command updates an existing installation while retaining its local data.

## First launch

1. Continue through the welcome screen and enter your Igloo server address, including its scheme
   (`http://` or `https://`) and port if needed. The app checks the server before continuing.
2. On **Quick Connect**, scan the QR code or open the displayed address in a browser. Sign in
   to Igloo's Account settings if asked, then enter and approve the six-character TV code.
   Alternatively, choose **Use email & password instead** to sign in on the TV.
3. Enter your profile PIN if prompted, then browse your library.

For an Android Emulator connecting to a server on your development computer, use `10.0.2.2` as
the host address. For example, **if your server listens on port 8080**:

```text
http://10.0.2.2:8080
```

Port `8080` is only an example; use your server's actual port. `localhost` and `127.0.0.1` inside
the emulator refer to the emulator itself.

On a physical TV, use a LAN IP address or hostname reachable from that device, with the server's
actual port. Check server binding and firewall rules if it only accepts connections from the
development computer. Both HTTP and HTTPS are supported; HTTPS needs a certificate trusted by
the device. The server address is saved in the app, with no build-time URL to configure.

## Troubleshooting

- **SDK not found or Gradle fails on Java configuration:** Check the Gradle JDK in Android Studio
  and `JAVA_HOME` in your terminal. Confirm SDK Platform 37 and the requested Build-Tools are
  installed, and that `local.properties` (`sdk.dir`) or `ANDROID_HOME` points to the SDK.
- **No target in Android Studio or `adb devices`:** Boot the TV AVD, or enable debugging on the
  TV and connect it. Accept the device authorization prompt if the status is `unauthorized`.
  Select the intended serial if multiple devices are listed.
- **Installation or target compatibility problems:** Use a TV target running API 28 or later.
  The manifest requires `android.software.leanback`; phones, tablets, and generic emulator images
  are outside the supported targets.
- **Server check or sign-in cannot connect:** Verify the URL and port, that the server is running,
  and that the TV can reach it. Use `10.0.2.2` for the emulator's host computer and a reachable
  LAN address or hostname for a physical TV. Check firewall, DNS/VPN access, and HTTPS trust.

## Further reading

- [Development guide](docs/development.md): architecture, source organization, and contributing.
- [Testing guide](docs/testing.md): test commands, device validation, and manual troubleshooting.
- [Design system](docs/design-system.md): visual and interaction specifications.
- [Known issues](docs/known-issues.md) and [cleanup backlog](docs/cleanup-backlog.md).

## License

Copyright (C) 2026 Jose Ibañez. SPDX license identifier: `GPL-3.0-or-later`.

Igloo TV is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

Igloo TV is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](LICENSE) for more details.

Third-party libraries bundled in the app and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
