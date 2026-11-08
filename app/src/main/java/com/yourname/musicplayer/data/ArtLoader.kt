package com.yourname.musicplayer.data

import android.content.ContentUris
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.yourname.musicplayer.AppContainer
import java.util.Collections
import kotlin.math.abs

object ArtLoader {
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private val missing: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    private val palette = listOf(
        0xFF6C5CE7.toInt() to 0xFF00CEC9.toInt(), 0xFFFF7675.toInt() to 0xFFFDCB6E.toInt(),
        0xFF0984E3.toInt() to 0xFF74B9FF.toInt(), 0xFFE84393.toInt() to 0xFFFD79A8.toInt(),
        0xFF00B894.toInt() to 0xFF55EFC4.toInt(), 0xFF6D214F.toInt() to 0xFFB33771.toInt(),
        0xFF273C75.toInt() to 0xFF7158E2.toInt(), 0xFFEE5A24.toInt() to 0xFFF79F1F.toInt()
    )

    fun gradientFor(seed: String): Pair<Int, Int> = palette[abs(seed.hashCode()) % palette.size]

    fun clearMissing() { missing.clear() }

    fun invalidate(id: Long) {
        cache.remove(id); missing.remove(id)
    }

    fun load(c: AppContainer, s: Song, size: Int = 512): Bitmap? {
        cache.get(s.id)?.let { return it }
        if (missing.contains(s.id)) return null
        val b = try { loadUncached(c, s, size) } catch (_: Throwable) { null }
        if (b != null) cache.put(s.id, b) else missing.add(s.id)
        return b
    }

    private fun loadUncached(c: AppContainer, s: Song, size: Int): Bitmap? {
        c.artMap[s.id]?.let { p -> decodeFile(p, size)?.let { return it } }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c.app, s.uri)
            r.embeddedPicture?.let { bytes -> decodeBytes(bytes, size)?.let { return it } }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try { return c.app.contentResolver.loadThumbnail(s.uri, Size(size, size), null) } catch (_: Throwable) {}
        } else {
            try {
                val uri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), s.albumId
                )
                c.app.contentResolver.openInputStream(uri)?.use { ins ->
                    BitmapFactory.decodeStream(ins)?.let { return it }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun sample(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    fun decodeFile(path: String, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        o.inSampleSize = sample(o.outWidth, o.outHeight, size); o.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, o)
    }

    fun decodeBytes(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        o.inSampleSize = sample(o.outWidth, o.outHeight, size); o.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)
    }

    fun placeholder(seed: String, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val (a, b) = gradientFor(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), a, b, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        val w = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        val cx = size * 0.42f; val cy = size * 0.68f; val r = size * 0.11f
        cv.drawCircle(cx, cy, r, w)
        cv.drawRect(cx + r - size * 0.03f, size * 0.24f, cx + r, cy, w)
        cv.drawRect(cx + r - size * 0.03f, size * 0.24f, cx + size * 0.30f, size * 0.32f, w)
        return bmp
    }
}
