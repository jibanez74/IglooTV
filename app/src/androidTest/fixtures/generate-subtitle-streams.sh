#!/usr/bin/env bash
# Regenerates app/src/androidTest/assets/subtitle-streams: a 12 s HLS session (fMP4, 2 s
# segments, VOD) and the subtitle streams the Igloo server would send next to it for a session
# starting at 0 — a PGS track and a SubRip track, each alone in a non-seekable Matroska stream
# with the server's primer (an empty Cluster at timestamp 0) right after Tracks.
#
# The cues match the server's own fixtures (server/cmd/internal/ffmpeg/subtitletestutil):
# 1.0-2.5, 3.0-4.5, 6.0-7.0 and 8.0-9.5 s. The PGS track is built by hand, a solid 64x16 box per
# cue, because FFmpeg encodes image-based subtitles only from other image-based subtitles.
#
# Needs the Jellyfin FFmpeg the server uses: FFMPEG defaults to $IGLOO_FFMPEG_PATH, else ffmpeg.
set -euo pipefail

FFMPEG="${FFMPEG:-${IGLOO_FFMPEG_PATH:-ffmpeg}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../assets/subtitle-streams"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 -I - "$WORK" <<'EOF'
import struct, sys
work = sys.argv[1]
cues = [(1.0, 2.5, "One"), (3.0, 4.5, "Straddle"), (6.0, 7.0, "Two"), (8.0, 9.5, "Three")]

def srt_time(t):
    ms = int(round(t * 1000))
    return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)

with open(work + "/cues.srt", "w") as f:
    for i, (start, end, text) in enumerate(cues, 1):
        f.write("%d\n%s --> %s\n%s\n\n" % (i, srt_time(start), srt_time(end), text))

def seg(pts, kind, data):
    return b"PG" + struct.pack(">IIBH", pts, 0, kind, len(data)) + data

def composition(number, state, objects):
    return struct.pack(">HHBHBBBB", 320, 180, 0x10, number, state, 0, 0, objects)

window = struct.pack(">BBHHHH", 1, 0, 10, 10, 64, 16)
line = bytes([0x00, 0xC0, 64, 1, 0x00, 0x00])
picture = struct.pack(">HH", 64, 16) + line * 16
obj = struct.pack(">HBB", 0, 0, 0xC0) + struct.pack(">I", len(picture))[1:] + picture
out, number = b"", 0
for start, end, _ in cues:
    pts = int(round(start * 90000))
    out += seg(pts, 0x16, composition(number, 0x80, 1) + struct.pack(">HBBHH", 0, 0, 0, 10, 10))
    out += seg(pts, 0x17, window) + seg(pts, 0x14, bytes([0, 0, 1, 235, 128, 128, 255]))
    out += seg(pts, 0x15, obj) + seg(pts, 0x80, b"")
    number += 1
    pts = int(round(end * 90000))
    out += seg(pts, 0x16, composition(number, 0x00, 0)) + seg(pts, 0x17, window) + seg(pts, 0x80, b"")
    number += 1
open(work + "/cues.sup", "wb").write(out)
EOF

"$FFMPEG" -y -v error -nostdin \
  -f lavfi -i testsrc2=size=320x180:rate=24:duration=12 -i "$WORK/cues.sup" -i "$WORK/cues.srt" \
  -map 0:v -map 1:s -map 2:s -c:v libx264 -preset ultrafast -crf 45 -pix_fmt yuv420p -g 48 -keyint_min 48 \
  -sc_threshold 0 -c:s copy -metadata:s:s:0 language=eng -metadata:s:s:1 language=spa -copyts \
  "$WORK/movie.mkv"

rm -rf "$OUT"
mkdir -p "$OUT"
"$FFMPEG" -y -v error -nostdin -i "$WORK/movie.mkv" -map 0:v -c:v copy \
  -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_type fmp4 \
  -hls_fmp4_init_filename init.mp4 -hls_segment_filename "$OUT/segment_%d.m4s" "$OUT/playlist.m3u8"

# The server's arguments for a session starting at 0 (docs/ffmpeg.md, "Subtitle Streams").
for track in "1 pgs" "2 srt"; do
  set -- $track
  "$FFMPEG" -v error -nostdin -i "$WORK/movie.mkv" -copyts -map "0:$1" -c:s copy \
    -map_chapters -1 -map_metadata:g -1 -flush_packets 1 -f matroska pipe:1 > "$WORK/$2.raw.mkv"
  # The server's primer: an empty Cluster at timestamp 0 right after Tracks.
  python3 -I - "$WORK/$2.raw.mkv" "$OUT/$2.mkv" <<'EOF'
import sys
data = open(sys.argv[1], "rb").read()

def vint(i, keep_marker):
    first = data[i]
    length = 1
    while not first & (0x80 >> (length - 1)):
        length += 1
    value = first if keep_marker else first & ((0x80 >> (length - 1)) - 1)
    for k in range(1, length):
        value = value << 8 | data[i + k]
    return value, length

i = 0
ebml_id, n = vint(i, True); size, m = vint(i + n, False); i += n + m + size
segment_id, n = vint(i, True); _, m = vint(i + n, False); i += n + m
assert ebml_id == 0x1A45DFA3 and segment_id == 0x18538067
while True:
    child, n = vint(i, True); size, m = vint(i + n, False)
    assert child != 0x1F43B675, "Cluster before Tracks"
    i += n + m + size
    if child == 0x1654AE6B:
        break
open(sys.argv[2], "wb").write(data[:i] + bytes.fromhex("1F43B67583E78100") + data[i:])
EOF
done
ls -la "$OUT"
