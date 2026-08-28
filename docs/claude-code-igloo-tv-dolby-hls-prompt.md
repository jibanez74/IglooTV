# Add Dolby multichannel HLS profiles to the TV client

Add support for the server’s new AC-3 and E-AC-3 HLS audio profiles in the TV client. The server implementation and `openapi.json` have already been updated on the Igloo `dev` branch.

The goal is to let the TV client request server-side conversion of multichannel AAC and DTS-family audio, including DTS-HD MA, instead of decoding those formats to PCM. Multichannel PCM is unreliable on the target TV/eARC/Sonos Arc setup and is being reported as PCM 2.0.

## API contract

The personal movie HLS manifest accepts these optional query parameters:

- `audio_codec`: `ac3` or `eac3`
- `audio_channels`: `2` or `6`

They are an all-or-nothing pair. For this TV feature, use `audio_channels=6`:

- E-AC-3 5.1: `audio_codec=eac3&audio_channels=6`
- AC-3 5.1: `audio_codec=ac3&audio_channels=6`

Do not send `audio_codec=aac`, and do not send a bitrate. The server owns the encoding profiles: AC-3 5.1 uses 640 kbps and E-AC-3 5.1 uses 768 kbps. Omitting both parameters preserves the existing legacy behavior.

The parameters belong on the existing personal movie HLS manifest request. The server propagates them to the initialization and media-segment URLs.

## Required behavior

The objective is to convert DTS audio streams to e-ac3 and aac with 5.1 channels into ac3 with also 5.1 channels so that the user can get a reliable experience with high quality audio.

  
## Playback Behavior

The following behavior should be the default until we add a settings screen to the application where we can change any of these:
1. If the user is in direct playback but we encounter an audio codec that we cant play reliably, we should pick either e-ac3 or ac3 to give the user the best experience.  This means that we should convert the audio but I want the original video quality playing.
2. When using different video quality, the behavior should be the same but only now with the video quality that the user selected.

## Testing

This task requires heavy testing.  This includes unit testing and real tests on the shield.

If we encounter failures, we must carefully evaluate if the issue is in the kotline implementation in our tv client or if it could be a server problem.

## Questions

In order to complete this task, ask me as many questions as you need.

If you need to look for information that is not available in this project, you may check the Igloo project located at `/home/jose-ibanez/projects/Igloo` or you may use the context 7 mcp tools for additional information.

You may also consult the docs directory for the openapi.json file or the ffmpeg.md for more info.