# HLS and Player Lifecycle Review

## Review summary

The lifecycle reconstruction loses selected tracks and can detach subtitle rendering or leave an open modal without focus. HLS recovery also mistakes subtitle 404 responses for lost media sessions, causing unnecessary restarts and eventual playback failure.

## 1. Carry track selections into the replacement engine

**Priority:** P1  
**Location:** `app/src/main/java/com/igloo/blindpenguincoder/feature/player/MoviePlayerScreen.kt:301`

### Review comment

When a viewer changes audio or subtitles in-player and then presses Home, the new `ON_STOP` release discards `currentAudioTypeIndex` and `currentSubtitleTypeIndex`. The host persists only quality, so the engine rebuilt on return receives the original `MoviePlayRequest` and silently reverts the track choices. Persist the engine-authoritative track ordinals, including the subtitles-off state, before reconstruction, as required by `AGENTS.md:142-144`.

### Human-readable explanation

The player is destroyed when the app goes into the background and rebuilt when the viewer returns. Before destroying it, the app remembers the selected video quality, but it does not remember any audio or subtitle changes made during playback. As a result, returning from the Home screen can switch the viewer back to the original audio language or subtitle setting without warning.

For example, a viewer may switch from English audio to Spanish audio and turn subtitles off. After pressing Home and returning to Igloo, the replacement player can start with English audio and the original subtitle choice again.

The app should read the active audio and subtitle track indexes from the current player engine before releasing it, save those values in the state used to create the next engine, and explicitly preserve the value that represents subtitles being off. The replacement engine should then receive those saved choices instead of the original request values.

## 2. Recreate the `SubtitleView` with the replacement engine

**Priority:** P2  
**Location:** `app/src/main/java/com/igloo/blindpenguincoder/feature/player/MoviePlayerScreen.kt:310`

### Review comment

On a non-configuration background return, incrementing `reloadKey` swaps the engine without replacing the surrounding composition. `VideoSurface`'s `AndroidView(factory = { subtitleView })` has no key or update callback, so Compose retains the released engine's view while the new engine sends cues to a different, unattached `SubtitleView`. Text subtitles therefore disappear after returning from Home. Key the surface or view by engine, consistent with `docs/design-system.md:1617-1622`.

### Human-readable explanation

Each player engine owns a subtitle view that displays text over the video. When the app returns from the background, it creates a new engine and a new subtitle view. However, Compose can keep showing the old engine's Android view because the surrounding UI was not recreated and the `AndroidView` does not have a key or update path that tells Compose its underlying view changed.

This leaves two disconnected pieces: the screen still contains the old subtitle view, while the new player sends subtitle text to its new subtitle view, which is not attached to the screen. Video and audio continue playing, but subtitles no longer appear.

The visible video surface or the `AndroidView` should be keyed to the current engine or subtitle-view instance so it is recreated whenever the engine is replaced. An appropriate update callback is another option if it safely replaces the attached view. The result must be that the subtitle view receiving cues from the active engine is also the one displayed over the video.

## 3. Restore dialog focus after rebuilding the engine

**Priority:** P2  
**Location:** `app/src/main/java/com/igloo/blindpenguincoder/feature/player/MoviePlayerScreen.kt:310`

### Review comment

If Home is pressed while the Audio, Subtitles, or Quality dialog is open, the dialog remains mounted, but engine replacement resets `state` and removes its focused option rows. `TrackMenuDialog` requests entry focus only in `LaunchedEffect(Unit)`, so neither the temporary empty list nor its later repopulation assigns a new D-pad target. Clear and reopen the menu or rerun focus assignment when the engine or options change, as required by `AGENTS.md:45`.

### Human-readable explanation

On Android TV, a dialog must always have a focused control so the viewer can navigate it with the remote. If the viewer presses Home while a track or quality dialog is open, the dialog itself stays on screen when the app returns. Rebuilding the player temporarily clears the options that were inside it, including the focused row. When the options load again, the dialog does not request focus a second time because its focus effect only ran when the dialog was first created.

The viewer can therefore return to an open dialog that looks normal but has no active D-pad target. Remote navigation may appear frozen, and TalkBack users may also lose a meaningful navigation position.

The lifecycle rebuild should either close the dialog and restore focus to the control that opened it, or deliberately rerun the dialog's entry-focus logic after the replacement engine has supplied a new non-empty option list. In either case, focus must remain contained while the dialog is open and return to the invoking player control when it closes.

## 4. Check the failing URI before treating a 404 as session loss

**Priority:** P2  
**Location:** `app/src/main/java/com/igloo/blindpenguincoder/playback/media3/ExoMoviePlayerEngine.kt:580-581`

### Review comment

With HLS and a selected text subtitle, a 404 from the sideloaded WebVTT request reaches this path too, but the status-only check rebuilds the video session three times and ultimately reports that the session was lost. The subtitle endpoint explicitly permits 404 responses (`docs/openapi.json:2309-2315,2337-2338`), so inspect the failing `DataSpec.uri` and recover only HLS manifest or segment requests.

### Human-readable explanation

The video player downloads several kinds of resources: the HLS manifest, HLS video and audio segments, and a separate WebVTT file for text subtitles. Any of those requests can return HTTP 404. The current recovery code looks only at the status code, so it assumes every 404 means the HLS playback session has expired or disappeared.

A missing subtitle file does not necessarily mean the video session is invalid. The API contract explicitly allows the subtitle endpoint to return 404. Restarting the entire HLS session in response does not fix the missing subtitle; instead, it interrupts otherwise valid playback, repeats the restart several times, and can eventually show a misleading session-lost error.

The error handler should inspect the failed request URI available from `DataSpec.uri`. Session recovery should run only when the failed resource belongs to the active HLS manifest or one of its media segments. A 404 from the sideloaded WebVTT subtitle endpoint should follow subtitle-specific handling and must not consume the HLS session-recovery retries or terminate otherwise playable video.
