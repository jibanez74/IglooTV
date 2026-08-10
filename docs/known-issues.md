# Known issues

Contract and behaviour gaps found but deliberately not fixed yet, with enough detail to act on
without rediscovering them. Delete an entry when it lands.

---

## `UpdateMovieWatchProgressRequest` is missing the two fields the backend requires

**Found:** 2026-08-10, while seeding watch progress by hand to verify the Home rails.
**Status:** open. Harmless today, blocking the moment playback saves progress.
**Files:** `app/src/main/java/com/igloo/blindpenguincoder/data/model/Movies.kt`,
`app/src/test/java/com/igloo/blindpenguincoder/data/model/ApiModelsSerializationTest.kt`

### The gap

`PUT /api/movies/{id}/watch-progress` requires four fields. The app's model declares two:

```kotlin
// Movies.kt
@Serializable
data class UpdateMovieWatchProgressRequest(
    @SerialName("progress_sec") val progressSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
)
```

`docs/openapi.json` (`components.schemas.UpdateMovieWatchProgressRequest`) requires
`progress_sec`, `duration_sec`, **`save_session_id`**, and **`save_sequence`**, and sets
`additionalProperties: false`.

The backend enforces this before it touches the database — a body without them is rejected
outright:

```
$ curl -X PUT .../api/movies/408/watch-progress \
    -d '{"progress_sec":1500,"duration_sec":7200}'
HTTP/1.1 400 Bad Request
{"error":true,"message":"save_session_id is required"}
```

So this is not a lenient-server situation that might work in practice. Every progress save the
app makes with the current model returns 400.

### Why it is not breaking anything today

Nothing sends it. The only references are the declaration itself and one case in
`ApiModelsSerializationTest.encodesPlaybackModeAndRequestsWithWireNames`. The model was written
ahead of the playback work; the contract has since grown two fields and nothing caught the drift.

Worse than not catching it, that test **pins the wrong shape in place**:

```kotlin
assertEquals("""{"progress_sec":30.0,"duration_sec":7200.0}""", progress)
```

It asserts on exact encoded JSON, so it passes today and will *fail* when the fields are added.
Whoever fixes the model must update this assertion — it is expected breakage, not a regression.

### What the two fields mean

From `../Igloo/server/cmd/api/watch_progress_handler.go` and the `UpsertMovieWatchProgress`
query. They exist to make out-of-order progress saves safe:

| Field | Type | Rule |
| --- | --- | --- |
| `save_session_id` | UUID string | Identifies one continuous playback session. Validated as a real UUID (36 chars, correct dashes, hex). |
| `save_sequence` | int64 | Monotonically increasing counter **within** a session. Must be `> 0`. |

The upsert applies a write only when:

```sql
WHERE movie_watch_progress.save_session_id <> excluded.save_session_id
   OR movie_watch_progress.save_sequence   <  excluded.save_sequence
```

A different session always wins; within the same session only a strictly higher sequence wins.
A late-arriving save from earlier in the same session is **silently dropped** — no error, so the
client cannot detect it and must not treat a 200 as "my value is now stored".

Other server-side behaviour worth knowing before wiring this up:

- `progress_sec` is clamped server-side to `[0, duration_sec]`.
- `duration_sec` must be `> 0`; both values must be finite (NaN/Inf are rejected).
- At `progress_sec / duration_sec >= 0.98` the server marks the movie **watched** instead of
  storing progress, and responds `{"watched": true}`. The app's `MovieWatchProgressUpdateData`
  already models that response correctly.

### What the fix looks like

1. Add both fields to `UpdateMovieWatchProgressRequest`.
2. Own the session identity in the player, not in the model: one UUID minted per playback
   session, with a counter incremented on each save. AGENTS.md sets the cadence — save every 15
   seconds, first save only after ~15s of real playback, so in practice around 30s.
3. The sequence must keep increasing across retries of the *same* save, or a retried write can
   be dropped by the `<` comparison.
4. Give it a test that asserts the encoded JSON against the contract's required list, not just a
   round trip through the app's own model — the existing test shape is what let this drift.

### Related, already checked and fine

- `SetMovieWatchedRequest` (`PUT /api/movies/{id}/watch-progress/watched`) matches its schema:
  `watched` only.
- `MovieWatchProgress` (the `GET` response) matches.
- The two Home endpoints this branch added, `/api/movies/latest` and
  `/api/movies/continue-watching`, match their schemas exactly.
