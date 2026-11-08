package com.yourname.musicplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
import com.yourname.musicplayer.AppContainer
import com.yourname.musicplayer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- locals & routes

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("container") }
val LocalSettings = staticCompositionLocalOf { AppSettings.DEFAULT }
val LocalVm = staticCompositionLocalOf<AppViewModel> { error("vm") }
val LocalUi = staticCompositionLocalOf<UiState> { error("ui") }
val LocalActions = staticCompositionLocalOf<com.yourname.musicplayer.Actions> { error("actions") }
val LocalBn = staticCompositionLocalOf { false }

@Composable
fun tr(en: String, bn: String): String = if (LocalBn.current) bn else en

sealed interface Route {
    data class Root(val tab: Int) : Route
    data class Folder(val path: String) : Route
    data class Album(val id: Long, val name: String) : Route
    data class Artist(val name: String) : Route
    data class Genre(val name: String) : Route
    data class Playlist(val id: Long) : Route
    data class Details(val id: Long) : Route
    data class TagEdit(val id: Long) : Route
    data class Trim(val id: Long) : Route
    data class LyricsEdit(val id: Long) : Route
    object Queue : Route
    object Eq : Route
    object Themes : Route
    object FolderFilter : Route
}

class UiState {
    var rootTab by mutableStateOf(0)
    var libTab by mutableStateOf(0)
    val stack = mutableStateListOf<Route>()
    var playerOpen by mutableStateOf(false)
    var menuSong by mutableStateOf<Song?>(null)
    var playlistPick by mutableStateOf<Song?>(null)

    fun push(r: Route) { stack.add(r) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun goRoot(tab: Int) { stack.clear(); rootTab = tab }
}

fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun rememberTick(): () -> Unit {
    val hf = LocalHapticFeedback.current
    val on = LocalSettings.current.haptic
    return { if (on) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

// ---------------------------------------------------------------- glass

fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), strength: Float = 1f, elevation: Dp = 6.dp): Modifier = composed {
    val dark = LocalIsDark.current
    val a = (if (dark) 0.12f else 0.55f) * strength
    this
        .then(
            if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(0.25f), spotColor = Color.Black.copy(0.25f))
            else Modifier
        )
        .clip(shape)
        .background(Brush.linearGradient(listOf(Color.White.copy(a + 0.08f), Color.White.copy(a * 0.6f))))
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(if (dark) 0.45f else 0.9f), Color.White.copy(0.08f))),
            shape
        )
}

@Composable
fun GlassButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.glass(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- artwork

@Composable
fun SongArt(song: Song?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val c = LocalContainer.current
    val ver by c.artVersion.collectAsState()
    val bmp by produceState<Bitmap?>(null, song?.id, ver) {
        value = if (song == null) null else withContext(Dispatchers.IO) { ArtLoader.load(c, song) }
    }
    val b = bmp
    Box(modifier.clip(shape)) {
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DefaultArt(song?.title ?: "x")
    }
}

@Composable
fun DefaultArt(seed: String) {
    val (a, b) = remember(seed) { ArtLoader.gradientFor(seed) }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(a), Color(b)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(0.85f), modifier = Modifier.fillMaxSize(0.5f))
    }
}

suspend fun paletteColors(c: AppContainer, song: Song?): List<Color>? = withContext(Dispatchers.IO) {
    try {
        val bmp = song?.let { ArtLoader.load(c, it) } ?: return@withContext null
        val p = Palette.from(bmp).generate()
        val d = p.getDominantColor(0xFF3A2A8F.toInt())
        val v = p.getVibrantColor(d)
        val m = p.getDarkMutedColor(d)
        listOf(Color(d), Color(v), Color(m))
    } catch (_: Throwable) { null }
}

// ---------------------------------------------------------------- play/pause morph + heart

@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (playing) 1f else 0f, spring(dampingRatio = 0.65f, stiffness = 350f), label = "pp")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun pt(ax: Float, ay: Float, bx: Float, by: Float) = Offset(w * lerp(ax, bx, p), h * lerp(ay, by, p))
        val left = Path().apply {
            val a = pt(.28f, .18f, .22f, .18f); val b = pt(.5f, .31f, .42f, .18f)
            val c = pt(.5f, .69f, .42f, .82f); val d = pt(.28f, .82f, .22f, .82f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        val right = Path().apply {
            val a = pt(.5f, .31f, .58f, .18f); val b = pt(.8f, .5f, .78f, .18f)
            val c = pt(.8f, .5f, .78f, .82f); val d = pt(.5f, .69f, .58f, .82f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(left, tint); drawPath(right, tint)
    }
}

@Composable
fun HeartButton(fav: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (fav) 1f else 0.9f, spring(dampingRatio = 0.35f, stiffness = 400f), label = "hs")
    val burst = remember { Animatable(1f) }
    val accent = LocalAccent.current
    LaunchedEffect(fav) { if (fav) { burst.snapTo(0f); burst.animateTo(1f, tween(550)) } }
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (burst.value < 1f) {
                val r = size.minDimension * (0.25f + 0.45f * burst.value)
                for (i in 0 until 8) {
                    val ang = i * (Math.PI * 2 / 8)
                    drawCircle(Color(0xFFFF4D6D).copy(alpha = 1f - burst.value), 3.dp.toPx(),
                        Offset(center.x + (r * cos(ang)).toFloat(), center.y + (r * sin(ang)).toFloat()))
                }
            }
        }
        Icon(
            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
            tint = if (fav) Color(0xFFFF4D6D) else LocalText.current,
            modifier = Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        )
    }
}

// ---------------------------------------------------------------- rows

@Composable
fun SongRow(song: Song, current: Boolean, index: Int, trailing: String? = null, onClick: () -> Unit, onMore: () -> Unit) {
    val animateIn = index in 0..9
    val alpha = remember { Animatable(if (animateIn) 0f else 1f) }
    val off = remember { Animatable(if (animateIn) 30f else 0f) }
    if (animateIn) {
        LaunchedEffect(Unit) {
            delay(index * 25L)
            launch { alpha.animateTo(1f, tween(300)) }
            off.animateTo(0f, spring(dampingRatio = 0.75f))
        }
    }
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value; translationY = off.value }
            .clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArt(song, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) accent else LocalText.current,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
            Text("${song.artist} • ${fmt(song.duration)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = LocalText.current.copy(0.7f))
        }
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = accent, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, null) }
    }
}

@Composable
fun SongGridItem(song: Song, current: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.padding(6.dp).clickable(onClick = onClick)) {
        Box {
            SongArt(song, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(18.dp))
            IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.MoreVert, null, tint = Color.White)
            }
        }
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            color = if (current) LocalAccent.current else LocalText.current, modifier = Modifier.padding(top = 4.dp))
        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
    }
}

data class Group(val key: String, val title: String, val subtitle: String, val songs: List<Song>) {
    /** Most recent "date added" among the songs - used for "newest / oldest" ordering. */
    val date: Long = songs.maxOfOrNull { it.dateAdded } ?: 0L
}

val SongSortOptions = listOf(
    SortOrder.NAME_ASC, SortOrder.NAME_DESC, SortOrder.DATE_NEW, SortOrder.DATE_OLD,
    SortOrder.DURATION, SortOrder.ARTIST, SortOrder.ALBUM
)
val GroupSortOptions = listOf(
    SortOrder.NAME_ASC, SortOrder.NAME_DESC, SortOrder.DATE_NEW, SortOrder.DATE_OLD, SortOrder.COUNT
)

private fun SortOrder.groupComparator(): Comparator<Group> = when (this) {
    SortOrder.NAME_DESC -> Comparator<Group> { a, b -> b.title.compareTo(a.title, true) }
    SortOrder.DATE_NEW -> Comparator<Group> { a, b -> b.date.compareTo(a.date) }
    SortOrder.DATE_OLD -> Comparator<Group> { a, b -> a.date.compareTo(b.date) }
    SortOrder.COUNT -> Comparator<Group> { a, b -> b.songs.size.compareTo(a.songs.size) }
    else -> Comparator<Group> { a, b -> a.title.compareTo(b.title, true) }
}

@Composable
fun GroupList(groups: List<Group>, listKey: String, icon: ImageVector?, onOpen: (Group) -> Unit) {
    val st = LocalSettings.current
    val grid = st.gridOf(listKey)
    val order = st.sortOf(listKey)
    if (groups.isEmpty()) { EmptyState(tr("Nothing here yet", "এখানে কিছু নেই")); return }
    val sorted = remember(groups, order) { groups.sortedWith(order.groupComparator()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size}", color = LocalText.current.copy(0.7f))
            Spacer(Modifier.weight(1f))
            SortMenu(listKey, GroupSortOptions)
            ViewToggle(listKey)
        }
        if (grid) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp)
            ) {
                items(sorted.size, key = { sorted[it].key }) { i ->
                    val g = sorted[i]
                    Column(Modifier.padding(6.dp).clickable { onOpen(g) }) {
                        SongArt(g.songs.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(18.dp))
                        Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                    }
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(sorted.size, key = { sorted[it].key }) { i ->
                    val g = sorted[i]
                    Row(Modifier.fillMaxWidth().clickable { onOpen(g) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Box(Modifier.size(52.dp).glass(RoundedCornerShape(14.dp), 1f, 0.dp), contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = LocalAccent.current)
                            }
                        } else SongArt(g.songs.firstOrNull(), Modifier.size(52.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = LocalText.current.copy(0.5f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- headers / menus

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun sortLabel(o: SortOrder): String = when (o) {
    SortOrder.NAME_ASC -> tr("Name (A–Z)", "নাম (A–Z)")
    SortOrder.NAME_DESC -> tr("Name (Z–A)", "নাম (Z–A)")
    SortOrder.DATE_NEW -> tr("Newest first", "নতুন আগে")
    SortOrder.DATE_OLD -> tr("Oldest first", "পুরনো আগে")
    SortOrder.DURATION -> tr("Duration", "সময়কাল")
    SortOrder.ARTIST -> tr("Artist", "আর্টিস্ট")
    SortOrder.ALBUM -> tr("Album", "অ্যালবাম")
    SortOrder.COUNT -> tr("Most songs first", "বেশি গান আগে")
}

@Composable
fun SortMenu(listKey: String, options: List<SortOrder> = SongSortOptions) {
    val c = LocalContainer.current
    val cur = LocalSettings.current.sortOf(listKey)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Sort, null) }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(sortLabel(o)) },
                    leadingIcon = { if (o == cur) Icon(Icons.Default.Check, null) },
                    onClick = { c.set(K.sort(listKey), o.key); open = false }
                )
            }
        }
    }
}

@Composable
fun ViewToggle(listKey: String) {
    val c = LocalContainer.current
    val grid = LocalSettings.current.gridOf(listKey)
    IconButton(onClick = { c.set(K.grid(listKey), !grid) }) {
        Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, null)
    }
}

@Composable
fun EmptyState(text: String, icon: ImageVector = Icons.Default.LibraryMusic) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(120.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = LocalAccent.current)
        }
        Spacer(Modifier.height(16.dp))
        Text(text, color = LocalText.current.copy(0.8f))
    }
}

// ---------------------------------------------------------------- fast scroller

@Composable
fun FastScroller(letters: List<Char>, modifier: Modifier = Modifier, onSelect: (Char) -> Unit) {
    var h by remember { mutableStateOf(0) }
    fun pick(y: Float) {
        if (letters.isEmpty() || h <= 0) return
        val i = ((y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
        onSelect(letters[i])
    }
    Column(
        modifier.width(22.dp).fillMaxHeight().padding(vertical = 8.dp)
            .onSizeChanged { h = it.height }
            .pointerInput(letters) { detectTapGestures { pick(it.y) } }
            .pointerInput(letters) { detectDragGestures { ch, _ -> ch.consume(); pick(ch.position.y) } },
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { Text(it.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current) }
    }
}

fun Song.letter(): Char {
    val ch = title.firstOrNull()?.uppercaseChar() ?: '#'
    return if (ch in 'A'..'Z') ch else '#'
}

// ---------------------------------------------------------------- drag reorder

class DragState {
    var idx by mutableStateOf(-1)
    var off by mutableStateOf(0f)
}

fun Modifier.dragReorder(i: Int, n: Int, rowPx: Float, st: DragState, onMove: (Int, Int) -> Unit): Modifier =
    this.zIndex(if (st.idx == i) 1f else 0f)
        .graphicsLayer { translationY = if (st.idx == i) st.off else 0f }
        .pointerInput(i, n) {
            detectDragGesturesAfterLongPress(
                onDragStart = { st.idx = i; st.off = 0f },
                onDrag = { ch, d -> ch.consume(); st.off += d.y },
                onDragEnd = {
                    val to = (i + (st.off / rowPx).roundToInt()).coerceIn(0, n - 1)
                    st.idx = -1; st.off = 0f
                    if (to != i) onMove(i, to)
                },
                onDragCancel = { st.idx = -1; st.off = 0f }
            )
        }
