package com.yourname.musicplayer.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yourname.musicplayer.data.Song
import java.io.File
import java.nio.ByteBuffer

/** Simple audio cutter: copies compressed samples (no re-encode). Works for AAC/M4A sources. */
object Trimmer {
    /** Returns the content/file Uri of the saved cut, or throws. */
    fun trim(ctx: Context, s: Song, startMs: Long, endMs: Long): Uri {
        val tmp = File(ctx.cacheDir, "cut_${System.currentTimeMillis()}.m4a")
        val ex = MediaExtractor()
        ex.setDataSource(ctx, s.uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); throw IllegalStateException("No audio track") }
        val format = ex.getTrackFormat(track)
        ex.selectTrack(track)
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val out = muxer.addTrack(format)
            muxer.start()
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                info.set(0, size, (t - startUs).coerceAtLeast(0), ex.sampleFlags)
                muxer.writeSampleData(out, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Throwable) {}
            ex.release()
        }
        val name = "${s.title}_cut.m4a".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Ringtones/")
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Cannot create file")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            tmp.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
            dir.mkdirs()
            val dest = File(dir, name)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
            Uri.fromFile(dest)
        }
    }
}
