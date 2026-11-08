package com.yourname.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yourname.musicplayer.*
import com.yourname.musicplayer.data.AppSettings
import com.yourname.musicplayer.data.K
import com.yourname.musicplayer.widget.MusicWidgetProvider
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile var instance: PlaybackService? = null
        const val CMD_CLOSE = "com.yourname.musicplayer.CLOSE"
        private const val MAX_SAVED = 1500
    }

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private lateinit var c: AppContainer
    private val fx = AudioFx()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var cfg: AppSettings = AppSettings.DEFAULT
    private var baseVolume = 1f
    private var crossfadeMs = 0L
    private var sleepEndAt = 0L
    private var sleepEndSong = false
    private var lastSave = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (t: Throwable) { Log.w("Playback", "tick", t) }
            handler.postDelayed(this, 200)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (player.playWhenReady) mediaItem?.mediaId?.toLongOrNull()?.let { c.recordPlay(it) }
            saveState(); MusicWidgetProvider.refresh(this@PlaybackService)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveState()
            MusicWidgetProvider.refresh(this@PlaybackService)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndSong) {
                cancelSleep()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            fx.attach(audioSessionId, c, cfg)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { c.set(K.SHUFFLE, shuffleModeEnabled) }
        override fun onRepeatModeChanged(repeatMode: Int) { c.set(K.REPEAT, repeatMode) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        c = (application as MusicApplication).container

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(c.http)))
            )
            .setAudioAttributes(attrs, true)          // audio focus handling
            .setHandleAudioBecomingNoisy(true)       // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)

        // Notification: prev / play-pause / next (+ close button from the custom layout) with our own small icon
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).also { it.setSmallIcon(R.drawable.ic_stat_music) }
        )

        val closeCmd = SessionCommand(CMD_CLOSE, Bundle.EMPTY)
        val closeBtn = CommandButton.Builder()
            .setDisplayName(getString(R.string.notif_close))
            .setIconResId(R.drawable.ic_close)
            .setSessionCommand(closeCmd)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(ArtBitmapLoader(c))
            .setCustomLayout(ImmutableList.of(closeBtn))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(closeCmd).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(cmds).build()
                }

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_CLOSE) closePlayback()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()

        scope.launch {
            try { c.settings.uiFlow.collect { applySettings(it) } } catch (t: Throwable) { Log.w("Playback", "settings", t) }
        }
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) fx.attach(player.audioSessionId, c, cfg)
        handler.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (cfg.keepPlaying) super.onTaskRemoved(rootIntent)
        else closePlayback()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        saveState()
        fx.release()
        session?.run { release() }
        session = null
        player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ settings

    private var restored = false

    private fun applySettings(s: AppSettings) {
        cfg = s
        if (!restored) {
            restored = true
            player.repeatMode = s.repeat
            player.shuffleModeEnabled = s.shuffle
        }
        val pp = PlaybackParameters(s.speed, s.pitch)
        if (player.playbackParameters != pp) player.playbackParameters = pp
        player.setSkipSilenceEnabled(s.skipSilence)
        if (!sleepEndSong) player.setPauseAtEndOfMediaItems(s.playMode == "single")
        crossfadeMs = s.crossfade * 1000L
        baseVolume = min(s.boost, 100) / 100f
        fx.apply(s)
    }

    // ------------------------------------------------------------------ volume / fades / sleep

    private fun tick() {
        var f = 1f
        val cf = crossfadeMs
        if (cf > 0 && player.isPlaying) {
            val pos = player.currentPosition
            val dur = player.duration
            if (pos < cf) f = min(f, pos.toFloat() / cf)
            if (dur != C.TIME_UNSET && dur - pos < cf && player.repeatMode != Player.REPEAT_MODE_ONE) {
                f = min(f, max(0L, dur - pos).toFloat() / cf)
            }
        }
        if (sleepEndAt > 0) {
            val remaining = sleepEndAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                player.pause()
                cancelSleep()
            } else {
                if (remaining < 10_000) f = min(f, remaining / 10_000f)
                c.sleepState.value = (remaining / 1000) * 1000
            }
        }
        player.volume = (baseVolume * f.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        if (player.isPlaying && now - lastSave > 15_000) { lastSave = now; saveState() }
    }

    fun startSleep(minutes: Int) {
        sleepEndSong = false
        player.setPauseAtEndOfMediaItems(cfg.playMode == "single")
        sleepEndAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        c.sleepState.value = minutes * 60_000L
        c.set(K.SLEEP_LAST, minutes)
    }

    fun startSleepEndOfSong() {
        sleepEndAt = 0
        sleepEndSong = true
        player.setPauseAtEndOfMediaItems(true)
        c.sleepState.value = SLEEP_END_OF_SONG
    }

    fun cancelSleep() {
        sleepEndAt = 0
        sleepEndSong = false
        player.setPauseAtEndOfMediaItems(cfg.playMode == "single")
        c.sleepState.value = SLEEP_NONE
    }

    // ------------------------------------------------------------------ helpers

    fun closePlayback() {
        try {
            saveState()
            player.pause()
            player.stop()
            player.clearMediaItems()
            cancelSleep()
            MusicWidgetProvider.refresh(this)
            stopSelf()
        } catch (t: Throwable) { Log.w("Playback", "close", t) }
    }

    private fun saveState() {
        try {
            val n = player.mediaItemCount
            if (n == 0 || n > MAX_SAVED) return
            val ids = (0 until n).map { player.getMediaItemAt(it).mediaId }
            if (ids.any { it.toLongOrNull() == null }) return
            val q = ids.joinToString(",")
            val idx = player.currentMediaItemIndex
            val pos = player.currentPosition
            c.editPrefs { p ->
                p[K.LAST_QUEUE] = q; p[K.LAST_INDEX] = idx; p[K.LAST_POS] = pos
            }
        } catch (t: Throwable) { Log.w("Playback", "save", t) }
    }
}
