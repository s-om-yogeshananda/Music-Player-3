package com.yourname.musicplayer

import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.yourname.musicplayer.data.Song
import java.io.File
import java.io.OutputStream

/** Activity-bound helpers (pickers, delete flow, share, ringtone). */
class Actions(private val activity: ComponentActivity, private val c: AppContainer) {
    private var onOpen: ((Uri) -> Unit)? = null
    private var onCreate: ((Uri) -> Unit)? = null
    private var onImage: ((Uri) -> Unit)? = null
    private var onDelete: ((Boolean) -> Unit)? = null

    private val openL = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onOpen?.invoke(it) }; onOpen = null
    }
    private val createL = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { onCreate?.invoke(it) }; onCreate = null
    }
    private val imageL = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImage?.invoke(it) }; onImage = null
    }
    private val deleteL = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        onDelete?.invoke(r.resultCode == android.app.Activity.RESULT_OK); onDelete = null
    }

    fun toast(msg: String) { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }

    fun openDocument(mimes: Array<String>, cb: (Uri) -> Unit) { onOpen = cb; openL.launch(mimes) }

    fun createDocument(name: String, write: (OutputStream) -> Unit) {
        onCreate = { uri ->
            try { activity.contentResolver.openOutputStream(uri)?.use(write); toast("Saved / সেভ হয়েছে") }
            catch (e: Throwable) { toast("Failed / ব্যর্থ") }
        }
        createL.launch(name)
    }

    fun pickImage(cb: (Uri) -> Unit) { onImage = cb; imageL.launch("image/*") }

    fun readText(uri: Uri): String =
        activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""

    fun displayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }

    /** Center-crops the picked image to a square and stores it inside app storage. */
    fun saveImage(uri: Uri, name: String): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / (sample * 2) >= 800 && o.outHeight / (sample * 2) >= 800) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        if (bmp == null) null else {
            val side = minOf(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = if (side > 800) Bitmap.createScaledBitmap(sq, 800, 800, true) else sq
            val dir = File(activity.filesDir, "art").apply { mkdirs() }
            val f = File(dir, "$name.jpg")
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            f.absolutePath
        }
    } catch (_: Throwable) { null }

    fun share(s: Song) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = s.mime; putExtra(Intent.EXTRA_STREAM, s.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(i, s.title))
        } catch (_: Throwable) { toast("Cannot share") }
    }

    fun delete(s: Song, done: (Boolean) -> Unit) {
        val cr = activity.contentResolver
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(cr, listOf(s.uri))
                onDelete = done
                deleteL.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                done(cr.delete(s.uri, null, null) > 0)
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                onDelete = { ok -> done(if (ok) (try { cr.delete(s.uri, null, null) > 0 } catch (_: Throwable) { false }) else false) }
                deleteL.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else done(false)
        } catch (_: Throwable) {
            done(false)
        }
    }

    fun setRingtone(s: Song) = setRingtoneUri(s.uri)

    fun setRingtoneUri(u: Uri) {
        if (!Settings.System.canWrite(activity)) {
            toast("Allow \"Modify system settings\" / সিস্টেম সেটিংস পরিবর্তনের অনুমতি দিন")
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}")))
            } catch (_: Throwable) {}
            return
        }
        try {
            RingtoneManager.setActualDefaultRingtoneUri(activity, RingtoneManager.TYPE_RINGTONE, u)
            toast("Ringtone set / রিংটোন সেট হয়েছে")
        } catch (_: Throwable) { toast("Failed / ব্যর্থ") }
    }
}
