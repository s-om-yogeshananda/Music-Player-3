package com.yourname.musicplayer.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.yourname.musicplayer.AppContainer
import com.yourname.musicplayer.data.ArtLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import okhttp3.Request

/** Loads notification / lock-screen artwork for "songart://<id>" and http(s) URIs. */
class ArtBitmapLoader(private val c: AppContainer) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        c.scope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: ArtLoader.placeholder("x")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        c.scope.future(Dispatchers.IO) {
            try {
                if (uri.scheme == "songart") {
                    val id = uri.host?.toLongOrNull()
                    val song = id?.let { c.songMap[it] }
                    (song?.let { ArtLoader.load(c, it, 512) }) ?: ArtLoader.placeholder(song?.title ?: "x")
                } else {
                    val resp = c.http.newCall(Request.Builder().url(uri.toString()).build()).execute()
                    resp.use { r ->
                        val bytes = r.body?.bytes()
                        (bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }) ?: ArtLoader.placeholder(uri.toString())
                    }
                }
            } catch (_: Throwable) {
                ArtLoader.placeholder("x")
            }
        }
}
