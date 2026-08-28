# Dolby multichannel HLS audio — status

_Last updated: 2026-08-28_

## What this feature does

Movies whose audio the TV setup can't play reliably — **DTS-family tracks** (DTS, DTS-HD,
DTS-HD MA, DTS:X) and **multichannel AAC** (more than stereo) — now play with server-side
audio conversion instead of collapsing to PCM 2.0:

- DTS-family → **E-AC-3 5.1** (`audio_codec=eac3&audio_channels=6`, 768 kbps)
- Multichannel AAC → **AC-3 5.1** (`audio_codec=ac3&audio_channels=6`, 640 kbps)

When "Original quality — plays the file as-is" (Direct) is selected and the track needs
conversion, the player automatically starts an "Original quality — audio adjusted" (remux)
session instead — original video, converted soundtrack — and the Quality menu shows the
effective mode honestly. On any chosen HLS quality, the video profile stays exactly what was
picked and only the audio pair is added. Everything else — TrueHD/Atmos, FLAC, PCM, AC-3,
E-AC-3, stereo AAC — behaves exactly as before; passthrough is never disturbed.

## Done

- **Conversion rules** live in one pure file, `playback/model/HlsAudioConversion.kt`:
  the unreliable-audio predicate and the fixed codec mapping. The `audio_codec`/
  `audio_channels` pair only exists as a single value, so a lone half (a server 400) is
  unrepresentable.
- **Wire-up**: `HlsSessionSpec` carries the profile; `hlsQueryParams` emits the pair; it
  rides every retry, the keepalive, lost-session recovery, and the Media3 playlist URL
  automatically. Segment URLs need nothing — the server rewrites them.
- **Direct auto-switch** in `ExoMoviePlayerEngine`: mode resolution at construction, on mode
  picks, on every restart path, and on audio-track switches (including the Direct in-place
  override path). Re-picking Direct while converted is a no-op instead of a session restart.
- **Gate change**: the pre-play refusal no longer blocks DTS/multichannel-AAC under Direct
  (the conversion handles them); TrueHD & co. keep the exact same refusal. The Playback
  Settings dialog announces "will be adjusted automatically" for covered tracks.
- **Graceful fallback**: if the server rejects the pair (400/422 — contract drift or broken
  audio metadata), the start retries once in legacy mode with a status message, so the movie
  still plays.
- **Tests** (all green):
  - JVM unit: 664 tests including new suites for the predicate/mapping, query params,
    response classification (400/422 flag), controller threading + one-shot fallback,
    repository wire format, gate behavior, dialog text, and details-screen launch behavior.
  - Instrumented on the real Shield: full suite 315 tests, 0 failures, including new engine
    tests (Direct+DTS → remux+eac3 spec & honest menu; reliable Direct never touches the
    backend; chosen ladder keeps its profile + pair; audio switches recompute the pair).
- **Server contract verified against the live backend** (curl): headers
  `X-Igloo-Effective-Audio-Codec/Channels/Bitrate` come back right (eac3/6/768k, ac3/6/640k),
  the pair propagates onto init/segment URLs, a lone parameter 400s, and ffprobe of a fetched
  segment confirms real AC-3 5.1 @ 48 kHz in the mux.
- **Verified end-to-end on the Shield + eARC chain** (playing real movies):
  - DTS-HD MA 5.1 movie ("2020 Texas Gladiators"), Direct default → plays; audio_flinger
    shows an active DIRECT output in **AUDIO_FORMAT_E_AC3, 6 ch** (true E-AC-3 passthrough).
  - AAC-LC 5.1 movie ("12 Angry Men"), Direct default → plays; **AUDIO_FORMAT_AC3, 6 ch**.
  - TrueHD + Atmos movie ("13 Hours"), Direct → untouched: **AUDIO_FORMAT_DOLBY_TRUEHD,
    8 ch**, no HLS session. This was the must-not-move regression.
  - Progress saving, resume dialog, and player exit all behaved normally throughout.

## Worth knowing

- For both test movies the server's remux-safety gate refused to copy the video and answered
  with `1080p_8mbps` instead of `remux` (12 Angry Men is HEVC 4K; 2020 Texas Gladiators is
  refused despite being H.264 1080p). The audio conversion rides along regardless, and the
  Quality menu honestly shows "1080p — best quality" as what's playing. So on this library,
  "original video + converted audio" often lands on a 1080p transcode — that's server
  policy, not the client. If original-video-with-converted-audio matters for those files,
  the remux-safety criteria on the server are the place to look.
- A converted "Original quality" session needs a transcode permit server-side, so it can hit
  the "Waiting for the server to free up…" path where a plain remux would not. The existing
  capacity retry machinery handles it.

## Remaining

- Nothing outstanding in the client for the agreed scope. Future candidates, deliberately
  not done now:
  - A settings screen to let users control/disable the automatic conversion (the current
    behavior is the agreed default until then).
  - Expanding the covered codecs (e.g. multichannel FLAC/PCM/Vorbis, which legacy HLS still
    downmixes to stereo AAC) — Jose wants to expand "a bit more in the future".
  - A Direct-mode mid-play audio switch onto a DTS track couldn't be exercised by the
    TEST-NET instrumented harness (needs real loaded track groups); the code path is the
    same `restartInPlace` one verified elsewhere, but a quick manual check on a multi-track
    movie would close that gap completely.
- The changes are uncommitted on `feature/hls-playback` — say the word and I'll commit
  (or move them to `dev` per the usual workflow).
