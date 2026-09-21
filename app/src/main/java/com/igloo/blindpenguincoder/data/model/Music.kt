package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SimpleAlbum(
    val id: Long,
    val title: String,
    val cover: SqlNullString,
    val musician: SqlNullString,
    val year: SqlNullInt64,
)

/**
 * One row of the library track list (`GET /music/tracks`, `/music/tracks/liked`,
 * `/music/tracks/shuffle`). [duration] is in **milliseconds**. The album and musician columns
 * are LEFT JOINs, so each arrives wrapped and is absent when the join found nothing.
 */
@Serializable
data class TrackListItem(
    val id: Long,
    val title: String,
    val duration: Long,
    val codec: String,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("album_title") val albumTitle: SqlNullString,
    @SerialName("album_cover") val albumCover: SqlNullString,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
    @SerialName("musician_name") val musicianName: SqlNullString,
)

/** One `GET /music/musicians` list entry; the server sorts by a `sort_name` it does not send. */
@Serializable
data class SimpleMusician(
    val id: Long,
    val name: String,
    val thumb: SqlNullString,
    @SerialName("album_count") val albumCount: Long,
    @SerialName("track_count") val trackCount: Long,
)

/** Payload of `MusicStatsEnvelope.data`. */
@Serializable
data class MusicStats(
    @SerialName("total_albums") val totalAlbums: Long,
    @SerialName("total_tracks") val totalTracks: Long,
    @SerialName("total_musicians") val totalMusicians: Long,
)

/** Payload of `LatestAlbumsEnvelope.data`. */
@Serializable
data class LatestAlbumsData(
    val albums: List<SimpleAlbum>,
)

/** Payload of `AlbumsEnvelope.data`. */
@Serializable
data class AlbumsData(
    val albums: List<SimpleAlbum>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** The full album row, as `GET /music/albums/details/{id}` returns it. */
@Serializable
data class Album(
    val id: Long,
    val title: String,
    @SerialName("sort_title") val sortTitle: String,
    @SerialName("spotify_id") val spotifyId: SqlNullString,
    @SerialName("spotify_popularity") val spotifyPopularity: SqlNullFloat64,
    val musician: SqlNullString,
    @SerialName("release_date") val releaseDate: SqlNullString,
    val year: SqlNullInt64,
    @SerialName("total_tracks") val totalTracks: SqlNullInt64,
    val cover: SqlNullString,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * A track as the album details payload carries it; only the fields the detail page reads are
 * typed. [duration] is in **milliseconds**.
 */
@Serializable
data class AlbumTrack(
    val id: Long,
    val title: String,
    @SerialName("track_index") val trackIndex: Long,
    val duration: Long,
    val disc: Long,
    val codec: String,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("channel_layout") val channelLayout: String,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
)

/** An album's artist — a musician row on the wire; only what the detail page reads is typed. */
@Serializable
data class AlbumArtist(
    val id: Long,
    val name: String,
    val thumb: SqlNullString,
)

/** One track→genre-tag association from the album details payload. */
@Serializable
data class TrackGenre(
    @SerialName("track_id") val trackId: Long,
    val tag: String,
)

/** Payload of `AlbumDetailsEnvelope.data`. [totalDuration] is in milliseconds. */
@Serializable
data class AlbumDetailsData(
    val album: Album,
    val tracks: List<AlbumTrack>,
    val artists: List<AlbumArtist>,
    @SerialName("track_genres") val trackGenres: List<TrackGenre>,
    @SerialName("album_genres") val albumGenres: List<String>,
    @SerialName("total_duration") val totalDuration: Double,
)

/** Payload of `MusiciansEnvelope.data`. */
@Serializable
data class MusiciansData(
    val musicians: List<SimpleMusician>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** The full musician row, as `GET /music/musicians/{id}` returns it under `musician`. */
@Serializable
data class Musician(
    val id: Long,
    val name: String,
    @SerialName("sort_name") val sortName: String,
    val summary: SqlNullString,
    @SerialName("spotify_id") val spotifyId: SqlNullString,
    @SerialName("spotify_popularity") val spotifyPopularity: SqlNullFloat64,
    @SerialName("spotify_followers") val spotifyFollowers: SqlNullInt64,
    val thumb: SqlNullString,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** One album of a musician's discography, newest release first. */
@Serializable
data class MusicianAlbum(
    val id: Long,
    val title: String,
    val cover: SqlNullString,
    val year: SqlNullInt64,
    @SerialName("release_date") val releaseDate: SqlNullString,
    @SerialName("track_count") val trackCount: Long,
)

/**
 * One track across a musician's albums. Carries its album but no musician columns — the
 * musician is the resource being read. [duration] is in **milliseconds**.
 */
@Serializable
data class MusicianTrack(
    val id: Long,
    val title: String,
    val duration: Long,
    val codec: String,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("album_title") val albumTitle: SqlNullString,
    @SerialName("album_cover") val albumCover: SqlNullString,
)

/** Payload of `MusicianDetailsEnvelope.data`. [totalDuration] is in milliseconds. */
@Serializable
data class MusicianDetailsData(
    val musician: Musician,
    val albums: List<MusicianAlbum>,
    val tracks: List<MusicianTrack>,
    val genres: List<String>,
    @SerialName("total_duration") val totalDuration: Double,
)

/** Payload of `TracksEnvelope.data`. */
@Serializable
data class TracksData(
    val tracks: List<TrackListItem>,
    val total: Long,
    val offset: Long,
    val limit: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

/** Payload of `ShuffleTracksEnvelope.data`. */
@Serializable
data class ShuffleTracksData(
    val tracks: List<TrackListItem>,
)

/** The full stored track row (`Track`); `channels` is a string on the wire. */
@Serializable
data class Track(
    val id: Long,
    val title: String,
    @SerialName("sort_title") val sortTitle: String,
    @SerialName("file_name") val fileName: String,
    val container: String,
    @SerialName("mime_type") val mimeType: String,
    val codec: String,
    val size: Long,
    @SerialName("track_index") val trackIndex: Long,
    val duration: Long,
    val disc: Long,
    val channels: String,
    @SerialName("channel_layout") val channelLayout: String,
    @SerialName("bit_rate") val bitRate: Long,
    val profile: String,
    @SerialName("release_date") val releaseDate: SqlNullString,
    val year: SqlNullInt64,
    val composer: SqlNullString,
    val copyright: SqlNullString,
    val language: SqlNullString,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `TrackDetailsEnvelope.data`. */
@Serializable
data class TrackDetailsData(
    val track: Track,
)

/** Payload of `TrackLikeToggleEnvelope.data`. */
@Serializable
data class TrackLikeToggleData(
    @SerialName("track_id") val trackId: Long,
    @SerialName("is_liked") val isLiked: Boolean,
)

/** Payload of `LikedTracksEnvelope.data`. */
@Serializable
data class LikedTracksData(
    val tracks: List<TrackListItem>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

/** Payload of `LikedTrackIDsEnvelope.data`. */
@Serializable
data class LikedTrackIdsData(
    @SerialName("liked_track_ids") val likedTrackIds: List<Long>,
)
