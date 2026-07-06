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

/** Payload of `AlbumDetailsEnvelope.data`. Album and track shapes are untyped in the spec. */
@Serializable
data class AlbumDetailsData(
    val album: JsonObject,
    val tracks: List<JsonObject>,
    val artists: List<JsonObject>,
    @SerialName("track_genres") val trackGenres: List<JsonObject>,
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
