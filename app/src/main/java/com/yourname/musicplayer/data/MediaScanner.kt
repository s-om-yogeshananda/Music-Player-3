package com.yourname.musicplayer.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object MediaScanner {
    private val exts = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")

    /**
     * Manual "reload": walks shared storage for audio files that MediaStore does not know yet
     * and asks the system to index them; also re-checks entries whose file has disappeared.
     * Best effort only - any failure is ignored and the normal MediaStore scan still runs.
     */
    fun refreshStorage(ctx: Context, known: List<Song>) {
        try {
            val have = HashSet<String>(known.size * 2)
            known.forEach { have.add(it.path) }
            val toScan = ArrayList<String>()
            known.forEach { if (!File(it.path).exists()) toScan.add(it.path) }

            fun walk(dir: File, depth: Int) {
                if (depth > 8 || toScan.size > 4000) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        if (depth == 0 && f.name == "Android") continue
                        walk(f, depth + 1)
                    } else if (f.extension.lowercase() in exts && f.absolutePath !in have) {
                        toScan.add(f.absolutePath)
                    }
                }
            }
            walk(Environment.getExternalStorageDirectory(), 0)
            if (toScan.isEmpty()) return

            val left = AtomicInteger(toScan.size)
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, toScan.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() <= 0) latch.countDown()
            }
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    fun scan(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val legacyGenres: Map<Long, String> = if (Build.VERSION.SDK_INT < 30) legacyGenres(ctx) else emptyMap()
        val proj = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID); add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DATE_ADDED); add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENRE)
        }
        val sel = "${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.DURATION}>0"
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj.toTypedArray(), sel, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in exts) continue
                val id = c.getLong(iId)
                val title = c.getString(iTitle)?.takeIf { it.isNotBlank() }
                    ?: File(path).nameWithoutExtension
                val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                val album = c.getString(iAlbum)?.takeIf { it.isNotBlank() } ?: "Unknown album"
                val genre = (if (iGenre >= 0) c.getString(iGenre) else legacyGenres[id])
                    ?.takeIf { it.isNotBlank() } ?: "Unknown genre"
                out.add(
                    Song(
                        id = id, title = title, artist = artist, album = album,
                        albumId = c.getLong(iAlbumId), genre = genre, year = c.getInt(iYear),
                        folder = File(path).parent ?: "", path = path, duration = c.getLong(iDur),
                        dateAdded = c.getLong(iDate), size = c.getLong(iSize),
                        mime = c.getString(iMime) ?: "audio/*"
                    )
                )
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun legacyGenres(ctx: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null
            )?.use { g ->
                while (g.moveToNext()) {
                    val gid = g.getLong(0)
                    val name = g.getString(1) ?: continue
                    val uri = MediaStore.Audio.Genres.Members.getContentUri("external", gid)
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { m ->
                        while (m.moveToNext()) map[m.getLong(0)] = name
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return map
    }
}
