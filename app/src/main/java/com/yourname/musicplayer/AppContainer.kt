package com.yourname.musicplayer

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.room.Room
import com.yourname.musicplayer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val SLEEP_NONE = -1L
const val SLEEP_END_OF_SONG = -2L

data class FxInfo(val bands: Int, val min: Int, val max: Int, val freqs: List<Int>)

private data class FilterKey(val hide: Boolean, val minSec: Int, val inc: Set<String>, val exc: Set<String>)

class AppContainer(val app: Application) {
    val db: AppDb = Room.databaseBuilder(app, AppDb::class.java, "music.db")
        .fallbackToDestructiveMigration().build()
    val dao: AppDao = db.dao()
    val settings = SettingsRepo(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    }

    val rawSongs = MutableStateFlow<List<Song>>(emptyList())
    val scanning = MutableStateFlow(false)
    /** true only while a manual reload (refresh button) is running - drives the "Scanning…" indicator */
    val reloading = MutableStateFlow(false)
    val artVersion = MutableStateFlow(0)
    val sleepState = MutableStateFlow(SLEEP_NONE)
    val fxInfo = MutableStateFlow<FxInfo?>(null)

    @Volatile var songMap: Map<Long, Song> = emptyMap()
    @Volatile var artMap: Map<Long, String> = emptyMap()

    private val filterKey = settings.flow
        .map { FilterKey(it.hideShort, it.minSec, it.included, it.excluded) }
        .distinctUntilChanged()

    val songs: StateFlow<List<Song>> = combine(rawSongs, filterKey, dao.tags()) { raw, fk, tags ->
        val tm = tags.associateBy { it.songId }
        raw.filter { s ->
            (!fk.hide || s.duration >= fk.minSec * 1000L) &&
                (fk.inc.isEmpty() || fk.inc.any { s.folder == it || s.folder.startsWith("$it/") }) &&
                fk.exc.none { s.folder == it || s.folder.startsWith("$it/") }
        }.map { s ->
            val t = tm[s.id]
            if (t == null) s else s.copy(
                title = t.title ?: s.title, artist = t.artist ?: s.artist, album = t.album ?: s.album,
                genre = t.genre ?: s.genre, year = t.year ?: s.year
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<Long>> = dao.favorites().map { l -> l.map { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val favSet: StateFlow<Set<Long>> = favorites.map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    val stats: StateFlow<Map<Long, StatEntity>> = dao.stats().map { l -> l.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val playlists: StateFlow<List<PlaylistEntity>> = dao.playlists()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val playlistSongs: StateFlow<List<PlaylistSongEntity>> = dao.playlistSongs()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val lyricsAll: StateFlow<Map<Long, String>> = dao.lyricsAll().map { l -> l.associate { it.songId to it.text } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch { songs.collect { l -> songMap = l.associateBy { it.id } } }
        scope.launch { dao.arts().collect { l -> artMap = l.associate { it.songId to it.path }; artVersion.value++ } }
    }

    fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    }

    private val scanLock = Mutex()

    /** deep = true is the manual reload button: also indexes new files on storage and re-tries artwork. */
    fun rescan(deep: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (!hasAudioPermission()) return@launch
            if (deep) {
                reloading.value = true
                scanLock.lock() // wait for a running background scan instead of ignoring the tap
            } else if (!scanLock.tryLock()) return@launch
            val t0 = SystemClock.elapsedRealtime()
            scanning.value = true
            try {
                if (deep) {
                    MediaScanner.refreshStorage(app, rawSongs.value)
                    ArtLoader.clearMissing()
                }
                rawSongs.value = MediaScanner.scan(app)
                if (deep) artVersion.value++
            } catch (e: Throwable) {
                Log.e("Scan", "failed", e)
            } finally {
                if (deep) {
                    // keep the "Scanning..." indicator visible long enough to be noticed
                    val rest = 1300L - (SystemClock.elapsedRealtime() - t0)
                    if (rest > 0) delay(rest)
                }
                scanning.value = false
                if (deep) reloading.value = false
                scanLock.unlock()
            }
        }
    }

    fun startObserving() {
        val handler = Handler(Looper.getMainLooper())
        val r = Runnable { rescan() }
        try {
            app.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacks(r); handler.postDelayed(r, 2500)
                    }
                })
        } catch (_: Throwable) {
        }
    }

    // ---- settings helpers ----
    fun <T> set(key: Preferences.Key<T>, v: T) {
        scope.launch { try { settings.edit { it[key] = v } } catch (e: Throwable) { Log.e("Settings", "set", e) } }
    }

    fun editPrefs(block: (MutablePreferences) -> Unit) {
        scope.launch { try { settings.edit(block) } catch (e: Throwable) { Log.e("Settings", "edit", e) } }
    }

    // ---- favorites / stats ----
    fun toggleFavorite(id: Long) {
        scope.launch {
            try {
                if (favSet.value.contains(id)) dao.removeFav(id)
                else dao.addFav(FavoriteEntity(id, System.currentTimeMillis()))
            } catch (e: Throwable) { Log.e("Fav", "toggle", e) }
        }
    }

    fun recordPlay(id: Long) {
        scope.launch {
            try {
                val s = dao.stat(id)
                dao.putStat(StatEntity(id, (s?.playCount ?: 0) + 1, System.currentTimeMillis()))
            } catch (e: Throwable) { Log.e("Stats", "record", e) }
        }
    }

    fun clearRecent() { scope.launch { try { dao.clearRecent() } catch (_: Throwable) {} } }

    // ---- playlists ----
    fun createPlaylist(name: String, then: (Long) -> Unit = {}) {
        scope.launch {
            try {
                val id = dao.insertPlaylist(PlaylistEntity(name = name.trim().ifBlank { "Playlist" }, createdAt = System.currentTimeMillis()))
                withContext(Dispatchers.Main) { then(id) }
            } catch (e: Throwable) { Log.e("PL", "create", e) }
        }
    }

    fun renamePlaylist(id: Long, name: String) { scope.launch { try { dao.renamePlaylist(id, name.trim()) } catch (_: Throwable) {} } }

    fun deletePlaylist(id: Long) {
        scope.launch { try { dao.clearPlaylist(id); dao.deletePlaylist(id) } catch (_: Throwable) {} }
    }

    fun addToPlaylist(pid: Long, songIds: List<Long>) {
        scope.launch {
            try {
                val existing = playlistSongs.value.filter { it.playlistId == pid }
                val have = existing.map { it.songId }.toSet()
                var pos = (existing.maxOfOrNull { it.position } ?: -1) + 1
                val add = songIds.filter { it !in have }.distinct().map { PlaylistSongEntity(pid, it, pos++) }
                if (add.isNotEmpty()) dao.putPlaylistSongs(add)
            } catch (e: Throwable) { Log.e("PL", "add", e) }
        }
    }

    fun removeFromPlaylist(pid: Long, songId: Long) {
        scope.launch { try { dao.removePlaylistSong(pid, songId) } catch (_: Throwable) {} }
    }

    fun reorderPlaylist(pid: Long, orderedIds: List<Long>) {
        scope.launch {
            try { dao.putPlaylistSongs(orderedIds.mapIndexed { i, id -> PlaylistSongEntity(pid, id, i) }) }
            catch (_: Throwable) {}
        }
    }

    fun setPlaylistCover(pid: Long, path: String?) { scope.launch { try { dao.setCover(pid, path) } catch (_: Throwable) {} } }

    fun playlistSongList(pid: Long): List<Song> =
        playlistSongs.value.filter { it.playlistId == pid }.sortedBy { it.position }.mapNotNull { songMap[it.songId] }

    // ---- lyrics / tags / art ----
    fun saveLyrics(id: Long, text: String) {
        scope.launch { try { if (text.isBlank()) dao.deleteLyric(id) else dao.putLyric(LyricsEntity(id, text)) } catch (_: Throwable) {} }
    }

    fun saveTag(t: TagEntity) { scope.launch { try { dao.putTag(t) } catch (_: Throwable) {} } }

    fun saveArt(songId: Long, path: String) {
        scope.launch { try { ArtLoader.invalidate(songId); dao.putArt(ArtEntity(songId, path)) } catch (_: Throwable) {} }
    }

    // ---- backup ----
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val sm = songMap
        val o = JSONObject()
        o.put("version", 1)
        o.put("favorites", JSONArray(dao.favorites().first().mapNotNull { sm[it.songId]?.path }))
        val st = JSONArray()
        dao.stats().first().forEach { s ->
            sm[s.songId]?.let { st.put(JSONObject().put("path", it.path).put("count", s.playCount).put("last", s.lastPlayed)) }
        }
        o.put("stats", st)
        val pls = JSONArray()
        val ps = dao.playlistSongs().first()
        dao.playlists().first().forEach { p ->
            val paths = ps.filter { it.playlistId == p.id }.sortedBy { it.position }.mapNotNull { sm[it.songId]?.path }
            pls.put(JSONObject().put("name", p.name).put("songs", JSONArray(paths)))
        }
        o.put("playlists", pls)
        val ly = JSONArray()
        dao.lyricsAll().first().forEach { l -> sm[l.songId]?.let { ly.put(JSONObject().put("path", it.path).put("text", l.text)) } }
        o.put("lyrics", ly)
        val set = JSONObject()
        settings.snapshot().asMap().forEach { (k, v) ->
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Long -> e.put("t", "l").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            set.put(k.name, e)
        }
        o.put("settings", set)
        o.toString(2)
    }

    suspend fun importBackup(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(text)
            val byPath = songMap.values.associateBy { it.path }
            val fav = o.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until fav.length()) byPath[fav.getString(i)]?.let { dao.addFav(FavoriteEntity(it.id, System.currentTimeMillis() - i)) }
            val st = o.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until st.length()) {
                val e = st.getJSONObject(i)
                byPath[e.getString("path")]?.let { dao.putStat(StatEntity(it.id, e.getInt("count"), e.getLong("last"))) }
            }
            val pls = o.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until pls.length()) {
                val e = pls.getJSONObject(i)
                val name = e.getString("name")
                val existing = dao.playlists().first().firstOrNull { it.name == name }
                val pid = existing?.id ?: dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
                val arr = e.getJSONArray("songs")
                val ids = (0 until arr.length()).mapNotNull { byPath[arr.getString(it)]?.id }
                dao.putPlaylistSongs(ids.mapIndexed { idx, id -> PlaylistSongEntity(pid, id, idx) })
            }
            val ly = o.optJSONArray("lyrics") ?: JSONArray()
            for (i in 0 until ly.length()) {
                val e = ly.getJSONObject(i)
                byPath[e.getString("path")]?.let { dao.putLyric(LyricsEntity(it.id, e.getString("text"))) }
            }
            val set = o.optJSONObject("settings")
            if (set != null) {
                settings.edit { p ->
                    set.keys().forEach { name ->
                        val e = set.getJSONObject(name)
                        when (e.getString("t")) {
                            "s" -> p[stringPreferencesKey(name)] = e.getString("v")
                            "i" -> p[intPreferencesKey(name)] = e.getInt("v")
                            "b" -> p[booleanPreferencesKey(name)] = e.getBoolean("v")
                            "f" -> p[floatPreferencesKey(name)] = e.getDouble("v").toFloat()
                            "l" -> p[longPreferencesKey(name)] = e.getLong("v")
                            "set" -> {
                                val a = e.getJSONArray("v")
                                p[stringSetPreferencesKey(name)] = (0 until a.length()).map { a.getString(it) }.toSet()
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Throwable) {
            Log.e("Backup", "import failed", e); false
        }
    }
}
