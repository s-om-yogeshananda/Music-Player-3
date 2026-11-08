package com.yourname.musicplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.audiofx.Visualizer
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.yourname.musicplayer.*
import com.yourname.musicplayer.data.K
import com.yourname.musicplayer.data.Song
import com.yourname.musicplayer.lyrics.Lyrics
import com.yourname.musicplayer.player.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ================================================================= mini player

@Composable
fun MiniPlayer() {
    val vm = LocalVm.current
    val ui = LocalUi.current
    val np by vm.now.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val n = np ?: return
    val off = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    val tick = rememberTick()
    val accent = LocalAccent.current

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .graphicsLayer { translationX = off.value; alpha = 1f - min(abs(off.value) / 700f, 0.6f) }
            .glass(RoundedCornerShape(20.dp), 1.2f)
            .clickable { ui.playerOpen = true }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val x = dx; val y = dy
                        scope.launch {
                            when {
                                abs(x) > abs(y) && x > 140f -> { off.animateTo(420f, tween(120)); vm.prev(); off.snapTo(-420f); off.animateTo(0f, spring(0.7f)) }
                                abs(x) > abs(y) && x < -140f -> { off.animateTo(-420f, tween(120)); vm.next(); off.snapTo(420f); off.animateTo(0f, spring(0.7f)) }
                                y > 120f && abs(y) > abs(x) -> {
                                    off.animateTo(520f, tween(180))
                                    vm.stop()
                                }
                                y < -80f && abs(y) > abs(x) -> { off.snapTo(0f); ui.playerOpen = true }
                                else -> off.animateTo(0f, spring(0.7f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { off.animateTo(0f, spring()) } }
                ) { ch, d ->
                    ch.consume()
                    dx += d.x
                    dy += d.y
                    if (abs(dx) > abs(dy)) scope.launch { off.snapTo(dx.coerceIn(-180f, 180f)) }
                }
            }
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NowArt(n, Modifier.size(46.dp), RoundedCornerShape(12.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(n.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                }
                IconButton(onClick = { tick(); vm.toggle() }) { PlayPauseIcon(playing, LocalText.current, Modifier.size(28.dp)) }
            }
            MiniProgress(n.mediaId, accent)
        }
    }
}

/** Thin progress line. The fraction is only read while drawing, so ticking never recomposes the mini player. */
@Composable
private fun MiniProgress(mediaId: String, accent: Color) {
    val vm = LocalVm.current
    val frac = remember { mutableStateOf(0f) }
    LaunchedEffect(mediaId) {
        while (true) {
            val d = vm.duration.value
            frac.value = if (d > 0) (vm.position().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Box(
        Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(0.12f))
            .drawBehind { drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width * frac.value, size.height)) }
    )
}

@Composable
fun NowArt(n: NowPlaying, modifier: Modifier, shape: Shape) {
    if (n.song != null || n.artUrl == null) SongArt(n.song, modifier, shape)
    else AsyncImage(n.artUrl, null, modifier.clip(shape), contentScale = ContentScale.Crop)
}

// ================================================================= full player

@Composable
fun FullPlayer() {
    val vm = LocalVm.current
    val ui = LocalUi.current
    val c = LocalContainer.current
    val s = LocalSettings.current
    val np by vm.now.collectAsState()
    val n = np
    if (n == null) { LaunchedEffect(Unit) { ui.playerOpen = false }; return }
    val playing by vm.isPlaying.collectAsState()
    val favSet by c.favSet.collectAsState()
    val pal by produceState<List<Color>?>(null, n.mediaId) { value = paletteColors(c, n.song) }
    val themeColors = LocalThemeColors.current
    val p0 = pal ?: themeColors
    val c0 by animateColorAsState(lerp(p0[0], Color.Black, 0.30f), tween(700), label = "c0")
    val c1 by animateColorAsState(lerp(p0.getOrElse(1) { p0[0] }, Color.Black, 0.55f), tween(700), label = "c1")
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showLyrics by remember { mutableStateOf(false) }
    var sleepDlg by remember { mutableStateOf(false) }
    var speedDlg by remember { mutableStateOf(false) }
    val tick = rememberTick()
    val accent = LocalAccent.current

    CompositionLocalProvider(LocalText provides Color.White, LocalContentColor provides Color.White, LocalIsDark provides true) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c0, c1, Color.Black)))) {
            if (s.playerStyle == "blur") {
                NowArt(n, Modifier.fillMaxSize().blur(50.dp).graphicsLayer { alpha = 0.45f }, RoundedCornerShape(0.dp))
            }
            val dragDown = Modifier.pointerInput(Unit) {
                var tot = 0f
                detectVerticalDragGestures(onDragStart = { tot = 0f }, onDragEnd = { if (tot > 160f) ui.playerOpen = false },
                    onVerticalDrag = { _, d -> tot += d })
            }

            @Composable
            fun TopBar() {
                Row(Modifier.fillMaxWidth().then(dragDown), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { ui.playerOpen = false }) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(32.dp)) }
                    Text(tr("Now playing", "এখন বাজছে"), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { n.song?.let { ui.menuSong = it } }, enabled = n.song != null) { Icon(Icons.Default.MoreVert, null) }
                }
            }

            @Composable
            fun ArtBlock(mod: Modifier) {
                Column(mod) {
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (showLyrics) Modifier else dragDown)) {
                        if (showLyrics && n.song != null) LyricsPanel(n.song, Modifier.fillMaxSize())
                        else ArtArea(n, playing, s.playerStyle, Modifier.fillMaxSize())
                    }
                    if (s.visualizer && !showLyrics) {
                        AudioVisualizer(s.visType, playing, accent, Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp))
                    }
                }
            }

            @Composable
            fun InfoBlock() {
                val sl by c.sleepState.collectAsState()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            n.artist, fontSize = 15.sp, color = Color.White.copy(0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = n.song != null) {
                                ui.playerOpen = false; ui.push(Route.Artist(n.artist))
                            }
                        )
                        if (sl > 0) Text("⏲ " + fmt(sl), fontSize = 12.sp, color = accent)
                        else if (sl == SLEEP_END_OF_SONG) Text("⏲ " + tr("stops after this song", "এই গানের পর বন্ধ হবে"), fontSize = 12.sp, color = accent)
                    }
                    n.song?.let { sg -> HeartButton(sg.id in favSet) { tick(); c.toggleFavorite(sg.id) } }
                }
            }

            @Composable
            fun Controls() {
                val shuffle by vm.shuffle.collectAsState()
                val repeat by vm.repeat.collectAsState()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { tick(); vm.toggleShuffle() }) {
                        Icon(Icons.Default.Shuffle, null, tint = if (shuffle) accent else Color.White.copy(0.8f))
                    }
                    IconButton(onClick = { tick(); vm.prev() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(38.dp)) }
                    Box(Modifier.size(76.dp).glass(CircleShape, 1.6f).clickable { tick(); vm.toggle() }, contentAlignment = Alignment.Center) {
                        PlayPauseIcon(playing, Color.White, Modifier.size(40.dp))
                    }
                    IconButton(onClick = { tick(); vm.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, null, Modifier.size(38.dp)) }
                    IconButton(onClick = { tick(); vm.cycleRepeat() }) {
                        Icon(
                            if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                            tint = if (repeat == Player.REPEAT_MODE_OFF) Color.White.copy(0.8f) else accent
                        )
                    }
                }
            }

            @Composable
            fun ActionRow() {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    @Composable
                    fun Act(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = false, click: () -> Unit) {
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = click).padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon, null, tint = if (on) accent else Color.White)
                            Text(label, fontSize = 10.sp, color = if (on) accent else Color.White.copy(0.8f), maxLines = 1)
                        }
                    }
                    Act(Icons.Default.QueueMusic, tr("Queue", "কিউ")) { ui.playerOpen = false; ui.push(Route.Queue) }
                    Act(Icons.Default.Subject, tr("Lyrics", "লিরিক্স"), showLyrics) { showLyrics = !showLyrics }
                    Act(Icons.Default.Equalizer, tr("Equalizer", "ইকুয়ালাইজার")) { ui.playerOpen = false; ui.push(Route.Eq) }
                    Act(Icons.Default.Bedtime, tr("Sleep", "স্লিপ"), c.sleepState.value != SLEEP_NONE) { sleepDlg = true }
                }
            }

            @Composable
            fun SeekBlock() {
                val dur by vm.duration.collectAsState()
                var dragging by remember { mutableStateOf(false) }
                var dragVal by remember { mutableStateOf(0f) }
                val pos by produceState(0L, n.mediaId) { while (true) { value = vm.position(); delay(250) } }
                val total = max(1f, dur.toFloat())
                Column {
                    Slider(
                        value = (if (dragging) dragVal else pos.toFloat()).coerceIn(0f, total),
                        onValueChange = { dragging = true; dragVal = it },
                        onValueChangeFinished = { vm.seek(dragVal.toLong()); dragging = false },
                        valueRange = 0f..total,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(0.25f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fmt(if (dragging) dragVal.toLong() else pos), Modifier.width(56.dp), fontSize = 12.sp, color = Color.White.copy(0.75f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { tick(); vm.seekBy(-10_000L) }) {
                                Icon(Icons.Default.Replay10, tr("Back 10 seconds", "১০ সেকেন্ড পেছনে"), Modifier.size(30.dp))
                            }
                            Spacer(Modifier.width(28.dp))
                            IconButton(onClick = { tick(); vm.seekBy(10_000L) }) {
                                Icon(Icons.Default.Forward10, tr("Forward 10 seconds", "১০ সেকেন্ড সামনে"), Modifier.size(30.dp))
                            }
                        }
                        Text(fmt(dur), Modifier.width(56.dp), fontSize = 12.sp, textAlign = TextAlign.End, color = Color.White.copy(0.75f))
                    }
                }
            }

            if (land) {
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                    ArtBlock(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp, top = 8.dp, bottom = 8.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        TopBar(); InfoBlock(); SeekBlock(); Controls(); ActionRow()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                    TopBar()
                    ArtBlock(Modifier.weight(1f).fillMaxWidth())
                    InfoBlock(); SeekBlock(); Controls(); ActionRow()
                }
            }
        }
    }
    if (sleepDlg) SleepDialog { sleepDlg = false }
    if (speedDlg) SpeedDialog { speedDlg = false }
}

@Composable
private fun ArtArea(n: NowPlaying, playing: Boolean, style: String, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight) * 0.94f
        when (style) {
            "vinyl" -> VinylArt(n, playing, Modifier.size(side))
            "square" -> NowArt(n, Modifier.size(side).shadow(18.dp, RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
            "minimal" -> NowArt(n, Modifier.size(side * 0.55f).shadow(10.dp, RoundedCornerShape(24.dp)), RoundedCornerShape(24.dp))
            "blur" -> NowArt(n, Modifier.size(side * 0.88f).shadow(20.dp, RoundedCornerShape(28.dp)), RoundedCornerShape(28.dp))
            else -> NowArt(n, Modifier.size(side).shadow(18.dp, RoundedCornerShape(28.dp)), RoundedCornerShape(28.dp))
        }
    }
}

@Composable
fun VinylArt(n: NowPlaying, playing: Boolean, modifier: Modifier) {
    val rot = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) while (true) { rot.animateTo(rot.value + 360f, tween(9000, easing = LinearEasing)) }
    }
    Box(modifier.graphicsLayer { rotationZ = rot.value }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF111111)))
        NowArt(n, Modifier.fillMaxSize(0.62f), CircleShape)
        Canvas(Modifier.fillMaxSize()) {
            for (k in 1..5) drawCircle(Color.White.copy(0.07f), radius = size.minDimension / 2 * (0.68f + k * 0.06f), style = Stroke(1.5f))
            drawCircle(Color(0xFF111111), radius = size.minDimension * 0.035f)
        }
    }
}

// ================================================================= visualizer

@Composable
fun AudioVisualizer(type: String, playing: Boolean, color: Color, modifier: Modifier) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Box(modifier, Alignment.Center) {
            TextButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(tr("Enable visualizer (needs audio permission)", "ভিজ্যুয়ালাইজার চালু করুন (অডিও পারমিশন লাগবে)"))
            }
        }
        return
    }
    var levels by remember { mutableStateOf(FloatArray(32)) }
    var wave by remember { mutableStateOf(FloatArray(128)) }
    val sid = PlaybackService.instance?.player?.audioSessionId ?: 0

    DisposableEffect(sid, playing) {
        var viz: Visualizer? = null
        if (playing && sid != 0 && sid != C.AUDIO_SESSION_ID_UNSET) {
            try {
                val v = Visualizer(sid)
                v.setEnabled(false)
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024))
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform == null) return
                        val step = max(1, waveform.size / 128)
                        wave = FloatArray(128) { i -> (((waveform[min(i * step, waveform.size - 1)].toInt() and 0xFF) - 128) / 128f) }
                    }

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null) return
                        val nb = fft.size / 2
                        val bins = nb - 1
                        val out = FloatArray(32)
                        for (b in 0 until 32) {
                            val from = 1 + (bins * (b / 32f).pow(2f)).toInt()
                            val to = max(from + 1, 1 + (bins * ((b + 1) / 32f).pow(2f)).toInt())
                            var m = 0f
                            for (k in from until min(to, nb)) {
                                m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
                            }
                            out[b] = min(1f, (m / 128f).pow(0.6f))
                        }
                        val prev = levels
                        levels = FloatArray(32) { max(out[it], prev[it] * 0.85f) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                v.setEnabled(true)
                viz = v
            } catch (_: Throwable) {
            }
        }
        onDispose { try { viz?.setEnabled(false); viz?.release() } catch (_: Throwable) {} }
    }
    LaunchedEffect(playing) { if (!playing) { levels = FloatArray(32); wave = FloatArray(128) } }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (type) {
            "wave" -> {
                val p = Path()
                wave.forEachIndexed { i, v ->
                    val x = i / (wave.size - 1f) * w
                    val y = h / 2 + v * h / 2
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            "circular" -> {
                val r = min(w, h) * 0.28f
                val ctr = Offset(w / 2, h / 2)
                val cnt = 64
                for (i in 0 until cnt) {
                    val lv = levels[(if (i < 32) i else 63 - i)]
                    val a = i * (2 * Math.PI / cnt)
                    val r2 = r + lv * min(w, h) * 0.22f + 2f
                    drawLine(color, Offset(ctr.x + (r * cos(a)).toFloat(), ctr.y + (r * sin(a)).toFloat()),
                        Offset(ctr.x + (r2 * cos(a)).toFloat(), ctr.y + (r2 * sin(a)).toFloat()), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            else -> {
                val bw = w / levels.size
                levels.forEachIndexed { i, lv ->
                    val bh = max(3.dp.toPx(), lv * h)
                    drawRoundRect(color.copy(alpha = 0.55f + 0.45f * lv), Offset(i * bw + bw * 0.15f, h - bh), androidx.compose.ui.geometry.Size(bw * 0.7f, bh), CornerRadius(bw * 0.3f))
                }
            }
        }
    }
}

// ================================================================= lyrics

@Composable
fun LyricsPanel(song: Song, modifier: Modifier) {
    val c = LocalContainer.current
    val vm = LocalVm.current
    val ui = LocalUi.current
    val s = LocalSettings.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val db by c.lyricsAll.collectAsState()
    val text by produceState<String?>(null, song.id, db[song.id]) { value = Lyrics.load(c, song) }
    var fetching by remember { mutableStateOf(false) }
    val lines = remember(text) { text?.let { Lyrics.parseLrc(it) } }
    val pos by produceState(0L, lines) { while (true) { value = vm.position(); delay(150) } }
    val size = s.lyricSize
    val accent = LocalAccent.current
    val notFound = tr("No lyrics found online", "অনলাইনে লিরিক্স পাওয়া যায়নি")
    val noNet = tr("Searching…", "খোঁজা হচ্ছে…")

    fun fetch() {
        fetching = true; actions.toast(noNet)
        scope.launch {
            val r = Lyrics.fetchOnline(c, song)
            if (r != null) c.saveLyrics(song.id, r) else actions.toast(notFound)
            fetching = false
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { c.set(K.LYRIC_SIZE, (size - 2).coerceAtLeast(12)) }) { Text("A−", fontWeight = FontWeight.Bold) }
            IconButton(onClick = { c.set(K.LYRIC_SIZE, (size + 2).coerceAtMost(40)) }) { Text("A+", fontWeight = FontWeight.Bold) }
            IconButton(onClick = { fetch() }, enabled = !fetching) { Icon(Icons.Default.CloudDownload, null) }
            IconButton(onClick = {
                actions.openDocument(arrayOf("*/*")) { uri ->
                    val t = try { actions.readText(uri) } catch (_: Throwable) { "" }
                    if (t.isNotBlank()) c.saveLyrics(song.id, t)
                }
            }) { Icon(Icons.Default.FileUpload, null) }
            IconButton(onClick = {
                val t = text ?: ""
                if (t.isNotBlank()) actions.createDocument("${song.title}.lrc") { it.write(t.toByteArray()) }
            }) { Icon(Icons.Default.FileDownload, null) }
            IconButton(onClick = { ui.playerOpen = false; ui.push(Route.LyricsEdit(song.id)) }) { Icon(Icons.Default.Edit, null) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val t = text
            when {
                t == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(tr("No lyrics for this song", "এই গানের লিরিক্স নেই"), color = Color.White.copy(0.8f))
                    Spacer(Modifier.height(10.dp))
                    GlassButton(tr("Search online (LRCLIB)", "অনলাইনে খুঁজুন (LRCLIB)"), Icons.Default.CloudDownload) { fetch() }
                    Spacer(Modifier.height(8.dp))
                    GlassButton(tr("Add lyrics", "লিরিক্স যোগ করুন"), Icons.Default.Edit) { ui.playerOpen = false; ui.push(Route.LyricsEdit(song.id)) }
                }
                lines != null -> {
                    val cur = lines.indexOfLast { it.timeMs <= pos }
                    val state = rememberLazyListState()
                    LaunchedEffect(cur) { if (cur >= 0) state.animateScrollToItem(max(0, cur - 2)) }
                    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 60.dp), modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { i, l ->
                            Text(
                                l.text.ifBlank { "♪" }, fontSize = if (i == cur) (size + 4).sp else size.sp,
                                fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == cur) accent else Color.White.copy(0.6f), textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable { vm.seek(l.timeMs) }.padding(vertical = 8.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
                else -> Text(t, fontSize = size.sp, lineHeight = (size * 1.5f).sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp))
            }
        }
    }
}

// ================================================================= dialogs

@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val sl by c.sleepState.collectAsState()
    var custom by remember { mutableStateOf(s.sleepLast.toString()) }
    fun start(m: Int) { PlaybackService.instance?.startSleep(m); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Sleep timer", "স্লিপ টাইমার")) },
        text = {
            Column {
                if (sl > 0) Text(tr("Remaining: ", "বাকি: ") + fmt(sl), color = LocalAccent.current, modifier = Modifier.padding(bottom = 8.dp))
                listOf(15, 30, 45, 60).forEach { m ->
                    Text("$m " + tr("minutes", "মিনিট"), Modifier.fillMaxWidth().clickable { start(m) }.padding(vertical = 10.dp))
                }
                Text(tr("End of current song", "বর্তমান গান শেষ হলে"), Modifier.fillMaxWidth().clickable {
                    PlaybackService.instance?.startSleepEndOfSong(); onDismiss()
                }.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it.filter { ch -> ch.isDigit() }.take(4) }, singleLine = true,
                        label = { Text(tr("Custom (min)", "কাস্টম (মিনিট)")) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { custom.toIntOrNull()?.takeIf { it > 0 }?.let { start(it) } }) { Text(tr("Start", "শুরু")) }
                }
            }
        },
        confirmButton = {
            if (sl != SLEEP_NONE) TextButton(onClick = { PlaybackService.instance?.cancelSleep(); onDismiss() }) { Text(tr("Cancel timer", "টাইমার বাতিল")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } }
    )
}

@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalSettings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Speed & pitch", "গতি ও পিচ")) },
        text = {
            Column {
                Text(tr("Speed", "গতি") + ": %.2fx".format(s.speed))
                Slider(s.speed, { c.set(K.SPEED, (it * 20).toInt() / 20f) }, valueRange = 0.5f..2f)
                Text(tr("Pitch", "পিচ") + ": %.2fx".format(s.pitch))
                Slider(s.pitch, { c.set(K.PITCH, (it * 20).toInt() / 20f) }, valueRange = 0.5f..2f)
            }
        },
        confirmButton = { TextButton(onClick = { c.set(K.SPEED, 1f); c.set(K.PITCH, 1f) }) { Text(tr("Reset", "রিসেট")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } }
    )
}

// ================================================================= song menu

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LocalAccent.current)
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}

@Composable
fun SongMenu(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val vm = LocalVm.current
    val ui = LocalUi.current
    val actions = LocalActions.current
    var confirmDelete by remember { mutableStateOf(false) }
    val deleted = tr("Deleted", "মুছে ফেলা হয়েছে")
    val thumbDone = tr("Thumbnail updated", "থাম্বনেইল আপডেট হয়েছে")
    fun go(r: Route) { onDismiss(); ui.playerOpen = false; ui.push(r) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("Delete this song?", "গানটি মুছবেন?")) },
            text = { Text(song.title + "\n" + song.path, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    actions.delete(song) { ok -> if (ok) { actions.toast(deleted); c.rescan() } }
                }) { Text(tr("Delete", "মুছুন")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MenuRow(Icons.Default.PlaylistPlay, tr("Play next", "পরেরটি হিসেবে চালান")) { vm.playNext(song); onDismiss() }
                MenuRow(Icons.Default.QueueMusic, tr("Add to queue", "কিউতে যোগ করুন")) { vm.addToQueue(song); onDismiss() }
                MenuRow(Icons.Default.PlaylistAdd, tr("Add to playlist", "প্লেলিস্টে যোগ করুন")) { onDismiss(); ui.playlistPick = song }
                MenuRow(Icons.Default.Edit, tr("Edit info (tags)", "তথ্য এডিট (ট্যাগ)")) { go(Route.TagEdit(song.id)) }
                MenuRow(Icons.Default.Image, tr("Edit thumbnail", "থাম্বনেইল এডিট")) {
                    onDismiss()
                    actions.pickImage { uri -> actions.saveImage(uri, "art_${song.id}")?.let { c.saveArt(song.id, it); actions.toast(thumbDone) } }
                }
                MenuRow(Icons.Default.Subject, tr("Edit lyrics", "লিরিক্স এডিট")) { go(Route.LyricsEdit(song.id)) }
                MenuRow(Icons.Default.Share, tr("Share", "শেয়ার")) { onDismiss(); actions.share(song) }
                MenuRow(Icons.Default.Notifications, tr("Set as ringtone", "রিংটোন সেট করুন")) { onDismiss(); actions.setRingtone(song) }
                MenuRow(Icons.Default.ContentCut, tr("Trim / ringtone cutter", "কাটুন / রিংটোন কাটার")) { go(Route.Trim(song.id)) }
                MenuRow(Icons.Default.Info, tr("Song details", "গানের বিবরণ")) { go(Route.Details(song.id)) }
                MenuRow(Icons.Default.Delete, tr("Delete", "মুছুন")) { confirmDelete = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } }
    )
}
