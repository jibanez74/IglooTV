package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SimpleAlbum(
    val id: Long,
    val title: String,
    val cover: SqlNullString,
    val musician: SqlNullString,
    val year: SqlNullInt64,
)

@Serializable
data class TrackListItem(
    val id: Long,
    val title: String,
    val duration: Double,
    val codec: String,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("file_path") val filePath: String,
)

@Serializable
data class SimpleMusician(
    val id: Long,
    val name: String,
    @SerialName("sort_name") val sortName: String,
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
 * A track as the album details payload carries it. The wire row is the full `Track` schema;
 * only the fields the detail page reads are typed. [duration] is in **milliseconds**, unlike
 * [TrackListItem.duration]'s seconds.
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

/** Payload of `MusicianDetailsEnvelope.data`. Item shapes are untyped in the spec. */
@Serializable
data class MusicianDetailsData(
    val musician: JsonObject,
    val albums: List<JsonObject>,
    val tracks: List<JsonObject>,
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

/** Payload of `TrackDetailsEnvelope.data`. The full track shape is untyped in the spec. */
@Serializable
data class TrackDetailsData(
    val track: JsonObject,
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
