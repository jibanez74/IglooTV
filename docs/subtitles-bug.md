# Bitmap subtitle state can survive an HLS switch

- Status: Open / deferred
- Recorded: 2026-08-28
- Scope: Movie playback subtitle selection during Direct-to-HLS source changes

## Reproduction

1. Start a movie in Direct mode whose container includes a bitmap subtitle stream such as PGS,
   DVD, or DVB subtitles and at least one text subtitle stream.
2. Select the bitmap subtitle in the in-player Subtitles menu.
3. Switch Quality to Remux or another HLS profile.
4. Inspect the selected subtitle, then switch back to Direct or allow the player to be
   reconstructed after a lifecycle stop.

The HLS source may auto-select a text subtitle even though the previous bitmap stream cannot be
served there. The old bitmap selection may also return when Direct playback is reconstructed.

## Cause

Direct playback exposes the container's bitmap subtitle group, so selecting it stores its
type-relative ordinal in `currentSubtitleTypeIndex`. HLS construction deliberately filters
image-based entries from `textSubtitleConfigs`, but the stored ordinal is not cleared or otherwise
resolved when the new source has no matching `sub:<index>` track.

Because the text track type remains enabled while `currentSubtitleTypeIndex` is non-null, Media3
can select another available sideloaded text track. At the same time, the engine still reports the
stale bitmap ordinal to the host. Persisting or reconstructing that state can therefore auto-select
an unrelated text track under HLS or resurrect the bitmap track after returning to Direct.

## Constraints

The design system requires image-based PGS/DVD/DVB rows to remain inert where the backend cannot
serve them. HLS supports only text subtitle tracks extracted by the backend as WebVTT. Any fix must
preserve the original type-relative stream ordinals because those ordinals are also backend track
indexes; filtering the request model would shift later subtitle URLs.

This issue is documentation-only in the current work. No subtitle implementation or test behavior
is changed here.

## Future acceptance criteria

- Entering HLS with a selected bitmap subtitle produces an explicit, deterministic subtitle state;
  it never relies on Media3 auto-selection.
- `currentSubtitleTypeIndex`, the visible selected row, and the rendered player state agree
  after every Direct/HLS source swap.
- An unavailable bitmap choice cannot cause an unrelated VTT track to become selected.
- Returning to Direct or reconstructing the engine does not resurrect a subtitle unless that is an
  explicitly chosen and documented product policy.
- Text subtitle ordinals and generated `/subtitles/{trackIndex}/web.vtt` URLs remain correct when
  bitmap and text streams are interleaved.
- Instrumentation coverage exercises Direct bitmap selection, the HLS transition, the return to
  Direct, and lifecycle reconstruction with TalkBack-visible menu state intact.
