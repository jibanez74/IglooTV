# Music Shuffle Playback

This guide describes how a native Igloo client should implement music shuffle. It covers two different queue models:

- Whole-library shuffle is an endless, server-fed rolling queue.
- Album, musician, and playlist shuffle is a finite queue randomized by the client.

The constants and race rules below intentionally match the web client. Examples are language-neutral and do not assume a particular UI framework.

## Shuffle API

Use the authenticated endpoint:

```http
GET /api/music/tracks/shuffle?limit=50&exclude=12,34,56
Authorization: Bearer igd_...
```

A web session cookie is also accepted. Native device clients normally use the bearer token obtained through Quick Connect or device login. The endpoint inherits the API's authentication requirement and returns `401` when the credential is missing or invalid.

### Query parameters

`limit` is the requested sample size. It defaults to 50 and is capped at 200. The documented valid range is 1 through 200. The current handler also falls back to 50 for a missing, non-numeric, zero, or negative value, but clients should not depend on that tolerance.

`exclude` identifies tracks the client already holds. Send at most 200 positive track IDs as one comma-separated value, or repeat the query parameter. The server removes duplicates and ignores invalid, non-positive, and excess IDs rather than failing playback. URL-encode query values using the platform's normal URL builder.

The server randomly samples eligible track IDs after exclusions, joins their album and musician metadata, and randomizes the returned order. Random selection happens on the server; a whole-library client should not fetch the alphabetical library and shuffle it locally.

### Success response

The response uses the shared JSON envelope:

```json
{
  "error": false,
  "data": {
    "tracks": [
      {
        "id": 42,
        "title": "Example Track",
        "duration": 213000,
        "codec": "flac",
        "bit_rate": 921600,
        "file_path": "/srv/music/example.flac",
        "album_id": { "Int64": 7, "Valid": true },
        "album_title": { "String": "Example Album", "Valid": true },
        "album_cover": { "String": "/covers/example.jpg", "Valid": true },
        "musician_id": { "Int64": 3, "Valid": true },
        "musician_name": { "String": "Example Musician", "Valid": true }
      }
    ]
  }
}
```

`duration` is an integer number of milliseconds. Album and musician fields are always present, but each uses Go's nullable SQL wrapper. Read the value only when `Valid` is `true`; the zero or empty value carried by an invalid wrapper is not real metadata.

An empty result is still a successful response:

```json
{ "error": false, "data": { "tracks": [] } }
```

For an initial request, it means the library has no tracks. For a refill with exclusions, it means the server found no track outside the currently excluded set.

The returned `file_path` is the server's local filesystem path. Never open it from a native client or expose it as a user-selectable URL. Play track `42` through authenticated `GET /api/music/tracks/42/stream`. Send the cookie or bearer credential on the stream request and on later byte-range requests used for seeking. The stream can return `200`, `206`, or `304`; handle `400`, `401`, `404`, `416`, and `500` as documented in the OpenAPI contract.

### Failures

Shuffle returns the standard error envelope:

```json
{ "error": true, "message": "..." }
```

The documented failures are `401 Unauthorized` and `500 Internal Server Error`. Treat both a non-successful HTTP status and an envelope with `error: true` as failure. Preserve an existing queue when a refill fails; it may continue playing and a later queue-state change may retry. An initial failure should leave the prior queue untouched and present a retryable error to the user.

## Whole-Library Endless Shuffle

Use these values for parity with the web player:

| Behavior | Value |
| --- | ---: |
| Fetch size | 50 tracks |
| Refill threshold | Fewer than 10 queue entries remain, counting the current track |
| Excluded held IDs | Up to 200, newest first when truncation is needed |
| Retained history | At most 50 tracks before the current track |
| Concurrent refills | One per queue generation |

Every installed queue has a monotonically increasing generation. Associate refills and playlist extensions with the generation for which they started, and reject their results if that generation is no longer current. An initial shuffle load starts before its queue exists, so give it a separate queue-start token; every later action that starts or requests another queue must invalidate that token.

The current web client applies this generation check to refills and paginated playlist extensions, but its initial whole-library shuffle request is not guarded: a slow initial response can replace a queue the user started while it was loading. Native clients should guard the initial request too.

After every successful batch:

1. Remove duplicate IDs within the batch.
2. Remove IDs already present anywhere in the current rolling queue.
3. Store per-track album and musician metadata by track ID.
4. Before appending, discard enough old entries to retain no more than 50 tracks before the current one.
5. Append the remaining tracks only if the generation is still current.

Send IDs from the queue as `exclude` on each refill. If more than 200 are held, prefer the newest 200 because they are closest to the playhead and most likely to remain in the rolling queue.

Trimming is important for bounded memory and previous-track navigation, but it changes the meaning of deduplication. A dropped track is no longer excluded and may be selected again later. Whole-library shuffle prevents duplicates in the current rolling queue; it does not promise that a track will occur only once across an unlimited listening session.

### Exhaustion

Latch an empty refill once per generation. Notify the user once, allow the already queued tail to finish, and do not issue another refill for that generation. Creating a new shuffle queue clears exhaustion because it creates a new generation. An empty initial response is a separate empty-library state and should not install an empty queue.

### Endless queue pseudocode

```text
constant BATCH_SIZE = 50
constant REFILL_WHEN_REMAINING_BELOW = 10
constant MAX_EXCLUSIONS = 200
constant MAX_HISTORY = 50

function startLibraryShuffle():
    startToken = beginAsyncQueueStart()
    result = GET shuffle(limit = BATCH_SIZE)

    if startToken != latestQueueStartToken:
        return
    if result is failure:
        showRetryableError()
        return

    batch = dedupeById(result.tracks)
    if batch is empty:
        showEmptyLibrary()
        return

    generation = nextQueueGeneration()
    installQueue(generation, batch, current = batch[0], mode = endlessShuffle)

function maybeRefill():
    remaining = queue.length - indexOf(queue.current)  // includes current
    if queue.mode != endlessShuffle or remaining < 0 or
       remaining >= REFILL_WHEN_REMAINING_BELOW:
        return
    if queue.exhausted or refillInFlightFor(queue.generation):
        return

    generation = queue.generation
    heldIds = uniqueIds(queue)
    exclusions = newest(heldIds, MAX_EXCLUSIONS)
    markRefillInFlight(generation)
    result = GET shuffle(limit = BATCH_SIZE, exclude = exclusions)
    clearRefillInFlightOnlyIfOwnedBy(generation)

    if generation != currentGeneration or result is failure:
        return
    if result.tracks is empty:
        queue.exhausted = true
        notifyExhaustedOnce()
        return

    batch = dedupeById(result.tracks)
    batch = batch excluding every ID currently in queue
    if batch is empty:
        return

    trimEntriesBeforeCurrentTo(MAX_HISTORY)
    append(batch)
```

The in-flight marker is generation-scoped. A request for an abandoned generation must not block a refill for the new queue, and a stale request must not clear the new generation's marker when it completes.

## Finite Shuffle

Albums, musicians, and playlists have a known finite membership. Randomize these queues on the client with Fisher-Yates rather than calling the whole-library shuffle endpoint:

```text
function fisherYates(items):
    shuffled = copy(items)
    for i from shuffled.length - 1 down to 1:
        j = randomIntegerInclusive(0, i)
        swap(shuffled[i], shuffled[j])
    return shuffled
```

Do not mutate cached API collections in place; shuffle a copy. Deduplicate by track ID before installing the queue because player navigation commonly locates the current item by ID.

Albums and musician detail responses contain their complete track lists, so load the response, shuffle all playable tracks once, and start at the shuffled first track. Retain the per-track metadata supplied by musician lists; album-wide metadata can be used as the fallback for album queues.

Playlists are paginated and should start promptly:

1. Deduplicate and shuffle the already loaded first page.
2. Install it immediately and save the returned queue generation.
3. Fetch all remaining pages in the background.
4. Drop the result if the user has replaced the queue.
5. Deduplicate new tracks against the live queue.
6. Keep the current and already played prefix fixed, then Fisher-Yates shuffle the combined unplayed tail and newly loaded tracks.

Appending each later page as a separately shuffled block does not produce a shuffle of the full playlist. Reshuffling only the unplayed tail mixes late arrivals through the remaining queue without moving the current track or breaking previous-track history. If background pagination fails or stops early, keep playing the tracks already loaded and tell the user that only part of the playlist was queued.

## Metadata and Playback State

Keep these concepts separate:

- Queue identity: a generation used to reject stale asynchronous work.
- Playback identity: the track ID used to construct `/api/music/tracks/{id}/stream`.
- Display metadata: title plus nullable album cover, album title, and musician name stored per track when available.
- Server storage metadata: `file_path`, which is not a client playback location.

When advancing tracks, update displayed metadata from the new track rather than retaining the first track's album and musician. Remove metadata for history entries when those entries are trimmed. If the current track disappears unexpectedly from the queue, do not refill until queue state is repaired; a negative current index is an invalid runway, not an invitation to fetch.

## Failure and Race Checklist

- Guard initial shuffle, refill, and playlist background loads with queue generation checks.
- Allow only one refill in flight for a given generation.
- Do not let a stale completion clear a newer generation's in-flight state.
- Keep the existing queue when a refill fails or returns only duplicate IDs.
- Latch and announce an empty refill once per generation.
- Ignore late playlist pages after the user starts another queue.
- Use authenticated stream URLs, never `file_path`.
- Interpret nullable values through `Valid`, and treat duration as milliseconds.

## Implementation References

- [OpenAPI contract](openapi.json)
- [Shuffle handler](../server/cmd/api/track_handler.go) and [random-track query](../server/sqlc/queries/tracks.sql)
- [Web queue controller](../web/src/context/AudioPlayerContext.tsx)
- [Endless queue refill hook](../web/src/hooks/useEndlessQueueRefill.ts)
- [Web audio player stream URL](../web/src/components/playback/AudioPlayer.tsx)
- [Playlist background queue extension](../web/src/routes/_auth/music/playlist.$id.tsx)
- [Media behavior](ffmpeg.md)
