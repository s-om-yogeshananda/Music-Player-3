package com.yourname.musicplayer.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yourname.musicplayer.AppContainer
import com.yourname.musicplayer.MusicApplication
import com.yourname.musicplayer.data.K
import com.yourname.musicplayer.data.Song
import com.yourname.musicplayer.player.PlaybackService
import com.yourname.musicplayer.youtube.YtResult
import com.yourname.musicplayer.youtube.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class NowPlaying(val mediaId: String, val title: String, val artist: String, val album: String, val song: Song?, val artUrl: String?)
data class QueueEntry(val mediaId: String, val title: String, val artist: String, val song: Song?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c: AppContainer = (app as MusicApplication).container
    private var controller: MediaController? = null

    val now = MutableStateFlow<NowPlaying?>(null)
    val isPlaying = MutableStateFlow(false)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(0)
    val queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queueIndex = MutableStateFlow(0)
    val duration = MutableStateFlow(0L)
    val abA = MutableStateFlow(-1L)
    val abB = MutableStateFlow(-1L)
    val message = MutableStateFlow<String?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        viewModelScope.launch {
            try {
                val ctl = MediaController.Builder(app, token).buildAsync().await()
                controller = ctl
                ctl.addListener(listener)
                sync(true)
                restore(ctl)
            } catch (_: Throwable) {
                message.value = "Player connection failed"
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(120)
                val a = abA.value; val b = abB.value
                if (a >= 0 && b > a) {
                    val p = controller?.currentPosition ?: 0L
                    if (p >= b) controller?.seekTo(a)
                }
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }

    // ------------------------------------------------------------ state sync

    private fun toNow(item: MediaItem): NowPlaying {
        val song = item.mediaId.toLongOrNull()?.let { c.songMap[it] }
        val md = item.mediaMetadata
        return NowPlaying(
            item.mediaId, song?.title ?: md.title?.toString() ?: "", song?.artist ?: md.artist?.toString() ?: "",
            song?.album ?: md.albumTitle?.toString() ?: "", song,
            if (song == null) md.artworkUri?.toString() else null
        )
    }

    private fun sync(rebuildQueue: Boolean) {
        val p = controller ?: return
        isPlaying.value = p.isPlaying
        shuffle.value = p.shuffleModeEnabled
        repeat.value = p.repeatMode
        duration.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
        queueIndex.value = p.currentMediaItemIndex
        now.value = p.currentMediaItem?.let { toNow(it) }
        if (rebuildQueue) {
            queue.value = (0 until p.mediaItemCount).map {
                val n = toNow(p.getMediaItemAt(it))
                QueueEntry(n.mediaId, n.title, n.artist, n.song)
            }
        }
    }

    private suspend fun restore(ctl: MediaController) {
        if (ctl.mediaItemCount > 0) return
        val st = c.settings.flow.first()
        val ids = st.lastQueue.split(",").mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        withTimeoutOrNull(10_000) { c.songs.first { it.isNotEmpty() } }
        val songs = ids.mapNotNull { c.songMap[it] }
        if (songs.isEmpty()) return
        val curId = ids.getOrNull(st.lastIndex)
        val idx = songs.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        ctl.setMediaItems(songs.map { toItem(it) }, idx, st.lastPos)
        ctl.prepare()
    }

    // ------------------------------------------------------------ items

    fun toItem(s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.id.toString())
        .setUri(s.uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                .setArtworkUri(Uri.parse("songart://${s.id}")).build()
        ).build()

    private fun window(list: List<Song>, index: Int): Pair<List<Song>, Int> {
        val max = 1500
        if (list.size <= max) return list to index
        val start = (index - 200).coerceAtLeast(0).coerceAtMost(list.size - max)
        return list.subList(start, start + max) to (index - start)
    }

    // ------------------------------------------------------------ commands

    fun position(): Long = controller?.currentPosition ?: 0L

    fun playSongs(list: List<Song>, index: Int) {
        val ctl = controller ?: return
        if (list.isEmpty()) return
        val (win, idx) = window(list, index.coerceIn(0, list.size - 1))
        abA.value = -1; abB.value = -1
        ctl.setMediaItems(win.map { toItem(it) }, idx, 0L)
        ctl.prepare(); ctl.play()
    }

    fun shuffleAll(list: List<Song>) {
        val ctl = controller ?: return
        if (list.isEmpty()) return
        ctl.shuffleModeEnabled = true
        playSongs(list, Random.nextInt(list.size))
    }

    fun playUri(uri: Uri) {
        val ctl = controller ?: return
        val item = MediaItem.Builder().setMediaId("ext:${uri}").setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Audio").build()).build()
        ctl.setMediaItem(item); ctl.prepare(); ctl.play()
    }

    fun playNext(s: Song) {
        val ctl = controller ?: return
        if (ctl.mediaItemCount == 0) playSongs(listOf(s), 0)
        else ctl.addMediaItem(ctl.currentMediaItemIndex + 1, toItem(s))
    }

    fun addToQueue(s: Song) {
        val ctl = controller ?: return
        if (ctl.mediaItemCount == 0) playSongs(listOf(s), 0) else ctl.addMediaItem(toItem(s))
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun stop() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        now.value = null
        queue.value = emptyList()
        queueIndex.value = 0
        duration.value = 0L
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun prev() { controller?.seekToPrevious() }
    fun seek(ms: Long) { controller?.seekTo(ms) }
    fun seekBy(deltaMs: Long) {
        val ctl = controller ?: return
        val d = ctl.duration
        val max = if (d == C.TIME_UNSET || d <= 0L) Long.MAX_VALUE else d
        ctl.seekTo((ctl.currentPosition + deltaMs).coerceIn(0L, max))
    }
    fun skipTo(i: Int) { controller?.seekTo(i, 0L); controller?.play() }
    fun moveQueue(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun removeQueue(i: Int) { controller?.removeMediaItem(i) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setAB() {
        val p = position()
        when {
            abA.value < 0 -> abA.value = p
            abB.value < 0 && p > abA.value -> abB.value = p
            else -> { abA.value = -1; abB.value = -1 }
        }
    }

    fun playOnline(r: YtResult, enqueue: Boolean = false) {
        viewModelScope.launch {
            try {
                message.value = null
                val url = YouTube.streamUrl(c, r.url)
                val item = MediaItem.Builder().setMediaId("yt:${r.id}").setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(r.title).setArtist(r.uploader)
                            .setArtworkUri(r.thumb?.let { Uri.parse(it) }).build()
                    ).build()
                val ctl = controller ?: return@launch
                if (enqueue && ctl.mediaItemCount > 0) ctl.addMediaItem(item)
                else { ctl.setMediaItem(item); ctl.prepare(); ctl.play() }
            } catch (e: Throwable) {
                message.value = "YouTube: ${e.message ?: "failed"}"
            }
        }
    }
}
