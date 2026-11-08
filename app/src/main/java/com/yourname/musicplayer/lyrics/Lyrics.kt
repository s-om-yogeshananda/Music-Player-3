package com.yourname.musicplayer.lyrics

import android.content.Context
import com.yourname.musicplayer.AppContainer
import com.yourname.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlin.math.min

data class LrcLine(val timeMs: Long, val text: String)

object Lyrics {
    private val tagRe = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    /** Returns synced lines, or null when the text has no timestamps (plain lyrics). */
    fun parseLrc(text: String): List<LrcLine>? {
        val out = ArrayList<LrcLine>()
        for (raw in text.lines()) {
            val matches = tagRe.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val body = raw.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracS = m.groupValues[3]
                val ms = when (fracS.length) { 0 -> 0L; 1 -> fracS.toLong() * 100; 2 -> fracS.toLong() * 10; else -> fracS.take(3).toLong() }
                out.add(LrcLine(min * 60_000 + sec * 1000 + ms, body))
            }
        }
        return if (out.isEmpty()) null else out.sortedBy { it.timeMs }
    }

    fun stripTimestamps(text: String): String =
        text.lines().joinToString("\n") { tagRe.replace(it, "").trim() }

    /** DB text -> .lrc next to the file -> embedded ID3 USLT. */
    suspend fun load(c: AppContainer, s: Song): String? = withContext(Dispatchers.IO) {
        c.lyricsAll.value[s.id]?.let { return@withContext it }
        try {
            val f = File(s.path.substringBeforeLast('.') + ".lrc")
            if (f.exists() && f.canRead()) return@withContext f.readText()
        } catch (_: Throwable) {
        }
        try { readId3(c.app, s) } catch (_: Throwable) { null }
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun readId3(ctx: Context, s: Song): String? {
        if (s.ext != "mp3") return null
        ctx.contentResolver.openInputStream(s.uri)?.use { ins ->
            val head = ByteArray(10)
            var got = 0
            while (got < 10) { val r = ins.read(head, got, 10 - got); if (r < 0) return null; got += r }
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
            val ver = head[3].toInt()
            if (ver != 3 && ver != 4) return null
            val size = min(syncsafe(head, 6), 3_000_000)
            val tag = ByteArray(size)
            var r0 = 0
            while (r0 < size) { val r = ins.read(tag, r0, size - r0); if (r < 0) break; r0 += r }
            var p = 0
            while (p + 10 <= r0) {
                val id = String(tag, p, 4, Charsets.ISO_8859_1)
                if (tag[p].toInt() == 0) break
                val fsize = if (ver == 4) syncsafe(tag, p + 4) else int32(tag, p + 4)
                if (fsize <= 0 || p + 10 + fsize > r0) break
                if (id == "USLT") return decodeUslt(tag, p + 10, fsize)
                p += 10 + fsize
            }
        }
        return null
    }

    private fun decodeUslt(b: ByteArray, start: Int, len: Int): String? {
        if (len < 5) return null
        val enc = b[start].toInt()
        var i = start + 4 // skip encoding + 3 bytes language
        val end = start + len
        val wide = enc == 1 || enc == 2
        // skip descriptor up to terminator
        if (wide) {
            while (i + 1 < end && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < end && b[i].toInt() != 0) i++
            i += 1
        }
        if (i >= end) return null
        val cs = when (enc) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        return String(b, i, end - i, cs).trim('\u0000').ifBlank { null }
    }

    /** LRCLIB lookup. Returns synced lyrics if available, otherwise plain. */
    suspend fun fetchOnline(c: AppContainer, s: Song): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", s.artist)
                .addQueryParameter("track_name", s.title)
                .addQueryParameter("album_name", s.album)
                .addQueryParameter("duration", (s.duration / 1000).toString())
                .build()
            c.http.newCall(Request.Builder().url(url).header("User-Agent", "MusicPlayer/1.0").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val j = JSONObject(r.body?.string() ?: return@withContext null)
                val synced = j.optString("syncedLyrics", "")
                val plain = j.optString("plainLyrics", "")
                if (synced.isNotBlank() && synced != "null") synced else if (plain.isNotBlank() && plain != "null") plain else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
