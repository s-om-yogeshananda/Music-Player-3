package com.yourname.musicplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.yourname.musicplayer.MainActivity
import com.yourname.musicplayer.MusicApplication
import com.yourname.musicplayer.R
import com.yourname.musicplayer.data.ArtLoader
import com.yourname.musicplayer.player.PlaybackService

class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val p = PlaybackService.instance?.player
        when (intent.action) {
            ACT_PLAY -> if (p != null) { if (p.isPlaying) p.pause() else p.play() } else launchApp(context)
            ACT_NEXT -> p?.seekToNext()
            ACT_PREV -> p?.seekToPrevious()
        }
    }

    private fun launchApp(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val ACT_PLAY = "com.yourname.musicplayer.widget.PLAY"
        const val ACT_NEXT = "com.yourname.musicplayer.widget.NEXT"
        const val ACT_PREV = "com.yourname.musicplayer.widget.PREV"

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, MusicWidgetProvider::class.java))
                ids.forEach { update(ctx, mgr, it) }
            } catch (_: Throwable) {
            }
        }

        private fun pi(ctx: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, action.hashCode(),
                Intent(ctx, MusicWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            try {
                val width = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val small = width < 230
                val rv = RemoteViews(ctx.packageName, if (small) R.layout.widget_music_small else R.layout.widget_music)
                val player = PlaybackService.instance?.player
                val item = player?.currentMediaItem
                val c = (ctx.applicationContext as MusicApplication).container
                val song = item?.mediaId?.toLongOrNull()?.let { c.songMap[it] }
                val title = item?.mediaMetadata?.title?.toString() ?: ctx.getString(R.string.widget_idle)
                rv.setTextViewText(R.id.w_title, title)
                if (!small) rv.setTextViewText(R.id.w_artist, item?.mediaMetadata?.artist?.toString() ?: "")
                val bmp = (song?.let { ArtLoader.load(c, it, 256) }) ?: ArtLoader.placeholder(title, 256)
                rv.setImageViewBitmap(R.id.w_art, bmp)
                rv.setImageViewResource(R.id.w_play, if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
                rv.setOnClickPendingIntent(R.id.w_play, pi(ctx, ACT_PLAY))
                if (!small) {
                    rv.setOnClickPendingIntent(R.id.w_next, pi(ctx, ACT_NEXT))
                    rv.setOnClickPendingIntent(R.id.w_prev, pi(ctx, ACT_PREV))
                }
                val open = PendingIntent.getActivity(
                    ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                rv.setOnClickPendingIntent(R.id.widget_root, open)
                mgr.updateAppWidget(id, rv)
            } catch (_: Throwable) {
            }
        }
    }
}
