# Bitmap subtitle state can survive an HLS switch

- Status: Fixed (2026-08-28)
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

## Resolution

Product policy, chosen explicitly: **the user's subtitle choice is remembered and restored, never
silently substituted.**

- `currentSubtitleTypeIndex` is redefined as the user's *chosen* ordinal, not the rendered one
  (`MoviePlayerEngine` KDoc carries the contract). It survives in-place source swaps, host
  persistence, and engine reconstruction unchanged.
- Whether the choice can render on the current source is decided per prepare by
  `MoviePlayRequest.subtitleRenderableInMode`: under HLS a bitmap (or out-of-range) ordinal
  disables the text track type outright and clears any text override, so nothing renders and
  Media3 has no room to auto-select a sideloaded VTT. Returning to Direct re-enables the text
  type and the swap-apply restores the bitmap group override.
- The in-player Subtitles menu under HLS is built from the wire list
  (`hlsSubtitleTrackOptions`): text rows map to their sideloaded `sub:<ordinal>` groups, and
  image-based rows appear inert — focusable and TalkBack-announced with the same
  `(image-based)` suffix as the pre-play dialog, never activatable — with a remembered bitmap
  choice shown as the selected row.
- Selecting "None" or a real VTT row under HLS is an explicit new choice and replaces the
  remembered bitmap ordinal. An option id that resolves to no wire ordinal is a no-op and can
  no longer corrupt the remembered state.
- The wire list is never filtered, so text ordinals and generated
  `/subtitles/{trackIndex}/web.vtt` URLs stay correct with interleaved bitmap and text streams.

## Acceptance criteria → coverage

- Entering HLS with a selected bitmap subtitle produces an explicit, deterministic subtitle
  state; never Media3 auto-selection —
  `ExoMoviePlayerEngineTest.switchingToHlsWithABitmapSubtitleGoesDeterministicallyOffAndKeepsTheChoice`,
  `…reconstructingIntoHlsWithABitmapOrdinalStaysOffButKeepsTheOrdinal`,
  `MoviePlayRequestTest` (`subtitleRenderableInMode` cases).
- `currentSubtitleTypeIndex`, the visible selected row, and the rendered player state agree
  after every Direct/HLS source swap —
  `TrackOptionsTest.aRememberedBitmapChoiceMarksItsInertRowSelected`,
  `ExoMoviePlayerEngineTest.aTextOrdinalKeepsTextEnabledUnderHls`.
- An unavailable bitmap choice cannot cause an unrelated VTT track to become selected —
  the deterministic-off engine tests above, plus
  `ExoMoviePlayerEngineTest.anUnresolvableSubtitleOptionIdIsANoOp`.
- Restoring the choice on return to Direct is the documented product policy —
  `ExoMoviePlayerEngineTest.returningToDirectReenablesTextForTheRememberedChoice`,
  `MoviePlayerScreenTest.aBitmapSubtitleChoiceSurvivesSavedStateRecreationUnderHls`.
- Text subtitle ordinals and generated `/subtitles/{trackIndex}/web.vtt` URLs remain correct
  when bitmap and text streams are interleaved —
  `TrackOptionsTest.hlsSubtitleRowsInterleaveInertBitmapRowsInWireOrder`,
  `…aSideloadedSubtitleIsFoundByItsStampedIdNotItsGroupPosition`.
- Menu state is visible and TalkBack-reachable through the transition —
  `MoviePlayerScreenTest.anInertImageBasedSubtitleRowIsFocusableSelectedAndNotActivatable`,
  `…selectingARealVttUnderHlsReplacesTheBitmapMemory`.
