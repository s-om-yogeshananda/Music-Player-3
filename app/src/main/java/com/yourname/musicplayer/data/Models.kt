package com.yourname.musicplayer.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val year: Int,
    val folder: String,
    val path: String,
    val duration: Long,
    val dateAdded: Long,
    val size: Long,
    val mime: String
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val bitrateKbps: Int get() = if (duration > 0) (size * 8 / duration).toInt() else 0
    val ext: String get() = path.substringAfterLast('.', "").lowercase()
}

enum class SortOrder(val key: String) {
    NAME_ASC("name_asc"), NAME_DESC("name_desc"), DATE_NEW("date_new"), DATE_OLD("date_old"),
    DURATION("duration"), ARTIST("artist"), ALBUM("album"), COUNT("count");

    companion object {
        fun from(k: String?): SortOrder = values().firstOrNull { it.key == k } ?: NAME_ASC
    }
}

fun List<Song>.sortedBy(o: SortOrder): List<Song> = when (o) {
    SortOrder.NAME_ASC -> sortedWith(Comparator { a, b -> a.title.compareTo(b.title, true) })
    SortOrder.NAME_DESC -> sortedWith(Comparator { a, b -> b.title.compareTo(a.title, true) })
    SortOrder.DATE_NEW -> sortedByDescending { it.dateAdded }
    SortOrder.DATE_OLD -> sortedBy { it.dateAdded }
    SortOrder.DURATION -> sortedByDescending { it.duration }
    SortOrder.ARTIST -> sortedWith(Comparator { a, b -> a.artist.compareTo(b.artist, true) })
    SortOrder.ALBUM -> sortedWith(Comparator { a, b -> a.album.compareTo(b.album, true) })
    SortOrder.COUNT -> sortedWith(Comparator { a, b -> a.title.compareTo(b.title, true) })
}

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val songId: Long, val addedAt: Long)

@Entity(tableName = "stats")
data class StatEntity(@PrimaryKey val songId: Long, val playCount: Int, val lastPlayed: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongEntity(val playlistId: Long, val songId: Long, val position: Int)

@Entity(tableName = "lyrics")
data class LyricsEntity(@PrimaryKey val songId: Long, val text: String)

@Entity(tableName = "art_override")
data class ArtEntity(@PrimaryKey val songId: Long, val path: String)

@Entity(tableName = "tag_override")
data class TagEntity(
    @PrimaryKey val songId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?
)

@Dao
interface AppDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC") fun favorites(): Flow<List<FavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFav(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE songId = :id") suspend fun removeFav(id: Long)

    @Query("SELECT * FROM stats") fun stats(): Flow<List<StatEntity>>
    @Query("SELECT * FROM stats WHERE songId = :id") suspend fun stat(id: Long): StatEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStat(s: StatEntity)
    @Query("UPDATE stats SET lastPlayed = 0") suspend fun clearRecent()

    @Query("SELECT * FROM playlists ORDER BY createdAt") fun playlists(): Flow<List<PlaylistEntity>>
    @Insert suspend fun insertPlaylist(p: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :n WHERE id = :id") suspend fun renamePlaylist(id: Long, n: String)
    @Query("UPDATE playlists SET coverPath = :p WHERE id = :id") suspend fun setCover(id: Long, p: String?)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlist_songs WHERE playlistId = :id") suspend fun clearPlaylist(id: Long)
    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position") fun playlistSongs(): Flow<List<PlaylistSongEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlaylistSongs(items: List<PlaylistSongEntity>)
    @Query("DELETE FROM playlist_songs WHERE playlistId = :pid AND songId = :sid") suspend fun removePlaylistSong(pid: Long, sid: Long)

    @Query("SELECT * FROM lyrics") fun lyricsAll(): Flow<List<LyricsEntity>>
    @Query("SELECT * FROM lyrics WHERE songId = :id") suspend fun lyric(id: Long): LyricsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLyric(l: LyricsEntity)
    @Query("DELETE FROM lyrics WHERE songId = :id") suspend fun deleteLyric(id: Long)

    @Query("SELECT * FROM art_override") fun arts(): Flow<List<ArtEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putArt(a: ArtEntity)

    @Query("SELECT * FROM tag_override") fun tags(): Flow<List<TagEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTag(t: TagEntity)
}

@Database(
    entities = [FavoriteEntity::class, StatEntity::class, PlaylistEntity::class, PlaylistSongEntity::class,
        LyricsEntity::class, ArtEntity::class, TagEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
}
