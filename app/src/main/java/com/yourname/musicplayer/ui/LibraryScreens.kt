package com.yourname.musicplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yourname.musicplayer.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yourname.musicplayer.youtube.YouTube
import com.yourname.musicplayer.youtube.YtResult

// ---------------------------------------------------------------- generic song list

@Composable
fun SongListScreen(
    title: String,
    songs: List<Song>,
    listKey: String,
    onBack: (() -> Unit)? = null,
    sortable: Boolean = true,
    trailing: ((Song) -> String?)? = null,
    headerless: Boolean = false,
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    val s = LocalSettings.current
    val vm = LocalVm.current
    val ui = LocalUi.current
    val order = s.sortOf(listKey)
    val grid = s.gridOf(listKey)
    val sorted = remember(songs, order, sortable) { if (sortable) songs.sortedBy(order) else songs }
    val now by vm.now.collectAsState()
    val tick = rememberTick()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        if (!headerless) {
            ScreenHeader(title, onBack) {
                extraActions()
                if (sortable) SortMenu(listKey)
                ViewToggle(listKey)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlassButton(tr("Play all", "সব চালান"), Icons.Default.PlayArrow) { tick(); vm.playSongs(sorted, 0) }
            GlassButton(tr("Shuffle", "শাফল"), Icons.Default.Shuffle) { tick(); vm.shuffleAll(sorted) }
            Spacer(Modifier.weight(1f))
            if (headerless) {
                extraActions()
                if (sortable) SortMenu(listKey)
                ViewToggle(listKey)
            }
            Text("${sorted.size}", color = LocalText.current.copy(0.7f))
        }
        if (sorted.isEmpty()) {
            EmptyState(tr("No songs here", "কোনো গান নেই"))
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            if (!grid) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp, end = 18.dp)) {
                    itemsIndexed(sorted, key = { _, it -> it.id }) { i, song ->
                        SongRow(
                            song, now?.song?.id == song.id, i, trailing?.invoke(song),
                            onClick = { tick(); vm.playSongs(sorted, i) }, onMore = { ui.menuSong = song }
                        )
                    }
                }
                if (sortable && order == SortOrder.NAME_ASC && sorted.size > 40) {
                    val index = remember(sorted) {
                        val m = LinkedHashMap<Char, Int>()
                        sorted.forEachIndexed { i, sg -> m.putIfAbsent(sg.letter(), i) }
                        m
                    }
                    val letters = remember(index) { index.keys.toList() }
                    FastScroller(letters, Modifier.align(Alignment.CenterEnd)) { ch ->
                        index[ch]?.let { scope.launch { listState.scrollToItem(it) } }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp)) {
                    items(sorted.size, key = { sorted[it].id }) { i ->
                        val song = sorted[i]
                        SongGridItem(song, now?.song?.id == song.id, { tick(); vm.playSongs(sorted, i) }, { ui.menuSong = song })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- library host

private val libTabs = listOf(
    "Songs" to "গান", "Folders" to "ফোল্ডার", "Albums" to "অ্যালবাম", "Artists" to "আর্টিস্ট", "Genres" to "জনরা",
    "Playlists" to "প্লেলিস্ট", "Favorites" to "প্রিয়", "Recently Played" to "সম্প্রতি বাজানো", "My Top Tracks" to "সবচেয়ে বেশি শোনা"
)

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val rot = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) { rot.animateTo(rot.value + 360f, tween(900, easing = LinearEasing)) }
        } else {
            rot.snapTo(0f)
        }
    }
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Refresh, null, Modifier.graphicsLayer { rotationZ = rot.value })
    }
}

@Composable
private fun LibTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val txt = LocalText.current
    val bg by animateColorAsState(if (selected) accent.copy(0.22f) else Color.Transparent, tween(220), label = "tabbg")
    val fg by animateColorAsState(if (selected) accent else txt.copy(0.75f), tween(220), label = "tabfg")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(bg)
            .border(1.dp, if (selected) accent.copy(0.6f) else txt.copy(0.18f), shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun LibraryScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val songs by c.songs.collectAsState()
    val scanning by c.scanning.collectAsState()
    val reloading by c.reloading.collectAsState()
    val bn = LocalBn.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = ui.libTab.coerceIn(0, libTabs.size - 1)) { libTabs.size }
    val tabState = rememberLazyListState()

    // pager -> shared state (only once a swipe has settled, so taps/animations do not fight each other)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { ui.libTab = it } }
    // shared state -> pager (e.g. launcher shortcuts "Recent" / "Favorites")
    LaunchedEffect(ui.libTab) {
        if (pager.currentPage != ui.libTab && !pager.isScrollInProgress) pager.scrollToPage(ui.libTab.coerceIn(0, libTabs.size - 1))
    }
    // keep the selected tab chip visible
    LaunchedEffect(pager.currentPage) { tabState.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }

    // 0 = idle, 1 = scanning, 2 = just finished
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(reloading) {
        if (reloading) phase = 1
        else if (phase == 1) { phase = 2; delay(1700); phase = 0 }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("My Music", "আমার গান")) {
            RefreshButton(reloading) { if (!reloading) c.rescan(true) }
        }
        AnimatedVisibility(
            visible = phase != 0,
            enter = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                Row(
                    Modifier.glass(RoundedCornerShape(50), 1f, 0.dp).padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (phase == 1) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Scanning…", "স্ক্যান হচ্ছে…"), fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = LocalAccent.current)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("${songs.size} songs found", "${songs.size}টি গান পাওয়া গেছে"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        LazyRow(
            state = tabState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(libTabs) { i, t ->
                LibTab(if (bn) t.second else t.first, pager.currentPage == i) {
                    scope.launch { pager.animateScrollToPage(i) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
            LibraryPage(page, scanning)
        }
    }
}

@Composable
private fun LibraryPage(page: Int, scanning: Boolean) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val songs by c.songs.collectAsState()
    val favs by c.favorites.collectAsState()
    val stats by c.stats.collectAsState()
    val songsWord = tr("songs", "টি গান")

    Box(Modifier.fillMaxSize()) {
        if (songs.isEmpty() && !scanning && page != 5) {
            EmptyState(tr("No music found on this device", "ডিভাইসে কোনো গান পাওয়া যায়নি"))
        } else when (page) {
            0 -> SongListScreen(tr("Songs", "গান"), songs, "songs", headerless = true)
            1 -> {
                val groups = remember(songs, songsWord) {
                    songs.groupBy { it.folder }.map { (k, v) ->
                        Group(k, k.substringAfterLast('/'), "${v.size} $songsWord • $k", v)
                    }
                }
                GroupList(groups, "folders", Icons.Default.Folder) { ui.push(Route.Folder(it.key)) }
            }
            2 -> {
                val groups = remember(songs) {
                    songs.groupBy { it.albumId }.map { (k, v) -> Group(k.toString(), v.first().album, v.first().artist + " • ${v.size}", v) }
                }
                GroupList(groups, "albums", null) { g -> ui.push(Route.Album(g.key.toLong(), g.title)) }
            }
            3 -> {
                val groups = remember(songs, songsWord) {
                    songs.groupBy { it.artist }.map { (k, v) -> Group(k, k, "${v.size} $songsWord", v) }
                }
                GroupList(groups, "artists", Icons.Default.Person) { ui.push(Route.Artist(it.key)) }
            }
            4 -> {
                val groups = remember(songs, songsWord) {
                    songs.groupBy { it.genre }.map { (k, v) -> Group(k, k, "${v.size} $songsWord", v) }
                }
                GroupList(groups, "genres", Icons.Default.Category) { ui.push(Route.Genre(it.key)) }
            }
            5 -> PlaylistsScreen()
            6 -> {
                val map = remember(songs) { songs.associateBy { it.id } }
                val list = remember(songs, favs) { favs.mapNotNull { map[it] } }
                SongListScreen(tr("Favorites", "প্রিয়"), list, "favorites", headerless = true)
            }
            7 -> {
                val list = remember(songs, stats) {
                    val map = songs.associateBy { it.id }
                    stats.values.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(100).mapNotNull { map[it.songId] }
                }
                SongListScreen(tr("Recently Played", "সম্প্রতি বাজানো"), list, "recent", headerless = true, sortable = false,
                    extraActions = { TextButton(onClick = { c.clearRecent() }) { Text(tr("Clear", "মুছুন")) } })
            }
            else -> {
                val list = remember(songs, stats) {
                    val map = songs.associateBy { it.id }
                    stats.values.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(100).mapNotNull { map[it.songId] }
                }
                SongListScreen(tr("My Top Tracks", "সবচেয়ে বেশি শোনা"), list, "top", headerless = true, sortable = false,
                    trailing = { sg -> stats[sg.id]?.let { "▶ ${it.playCount}" } })
            }
        }
    }
}

// ---------------------------------------------------------------- detail screens

@Composable
fun FolderScreen(path: String) {
    val songs by LocalContainer.current.songs.collectAsState()
    val ui = LocalUi.current
    val list = remember(songs, path) { songs.filter { it.folder == path } }
    SongListScreen(path.substringAfterLast('/'), list, "folder", onBack = { ui.back() })
}

@Composable
fun AlbumScreen(id: Long, name: String) {
    val songs by LocalContainer.current.songs.collectAsState()
    val ui = LocalUi.current
    SongListScreen(name, remember(songs, id) { songs.filter { it.albumId == id } }, "album", onBack = { ui.back() })
}

@Composable
fun ArtistScreen(name: String) {
    val songs by LocalContainer.current.songs.collectAsState()
    val ui = LocalUi.current
    SongListScreen(name, remember(songs, name) { songs.filter { it.artist == name } }, "artist", onBack = { ui.back() })
}

@Composable
fun GenreScreen(name: String) {
    val songs by LocalContainer.current.songs.collectAsState()
    val ui = LocalUi.current
    SongListScreen(name, remember(songs, name) { songs.filter { it.genre == name } }, "genre", onBack = { ui.back() })
}

// ---------------------------------------------------------------- playlists

@Composable
fun PlaylistCover(pl: PlaylistEntity, songs: List<Song>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    if (pl.coverPath != null) {
        val bmp by produceState<android.graphics.Bitmap?>(null, pl.coverPath) {
            value = withContext(Dispatchers.IO) { ArtLoader.decodeFile(pl.coverPath, 300) }
        }
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, modifier.clip(shape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            return
        }
    }
    val four = songs.take(4)
    if (four.size < 4) { SongArt(four.firstOrNull(), modifier, shape); return }
    Column(modifier.clip(shape)) {
        Row(Modifier.weight(1f)) { SongArt(four[0], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)); SongArt(four[1], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) }
        Row(Modifier.weight(1f)) { SongArt(four[2], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)); SongArt(four[3], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) }
    }
}

@Composable
fun PlaylistsScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val pls by c.playlists.collectAsState()
    val ps by c.playlistSongs.collectAsState()
    val songs by c.songs.collectAsState()
    val actions = LocalActions.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val importDone = tr("Playlist imported", "প্লেলিস্ট ইমপোর্ট হয়েছে")
    val map = remember(songs) { songs.associateBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(tr("New playlist", "নতুন প্লেলিস্ট"), Icons.Default.Add) { name = ""; creating = true }
            GlassButton(tr("Import M3U", "M3U ইমপোর্ট"), Icons.Default.FileDownload) {
                actions.openDocument(arrayOf("*/*")) { uri ->
                    val text = try { actions.readText(uri) } catch (_: Throwable) { "" }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    val byPath = songs.associateBy { it.path }
                    val byName = songs.groupBy { it.path.substringAfterLast('/') }
                    val ids = lines.mapNotNull { l -> byPath[l]?.id ?: byName[l.substringAfterLast('/').substringAfterLast('\\')]?.firstOrNull()?.id }
                    val pname = (actions.displayName(uri) ?: "Imported").substringBeforeLast('.')
                    c.createPlaylist(pname) { id -> c.addToPlaylist(id, ids); actions.toast(importDone) }
                }
            }
        }
        if (pls.isEmpty()) EmptyState(tr("No playlists yet", "কোনো প্লেলিস্ট নেই"), Icons.Default.PlaylistPlay)
        else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(pls, key = { _, p -> p.id }) { _, p ->
                val plSongs = remember(ps, map, p.id) { ps.filter { it.playlistId == p.id }.sortedBy { it.position }.mapNotNull { map[it.songId] } }
                Row(Modifier.fillMaxWidth().clickable { ui.push(Route.Playlist(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(p, plSongs, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${plSongs.size} " + tr("songs", "টি গান"), fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = LocalText.current.copy(0.5f))
                }
            }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(tr("New playlist", "নতুন প্লেলিস্ট")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name", "নাম")) }) },
            confirmButton = { TextButton(onClick = { c.createPlaylist(name); creating = false }) { Text(tr("Create", "তৈরি করুন")) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("Cancel", "বাতিল")) } }
        )
    }
}

@Composable
fun PlaylistDetailScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val vm = LocalVm.current
    val actions = LocalActions.current
    val pls by c.playlists.collectAsState()
    val ps by c.playlistSongs.collectAsState()
    val all by c.songs.collectAsState()
    val now by vm.now.collectAsState()
    val pl = pls.firstOrNull { it.id == id }
    if (pl == null) { LaunchedEffect(Unit) { ui.back() }; return }
    val map = remember(all) { all.associateBy { it.id } }
    val list = remember(ps, map, id) { ps.filter { it.playlistId == id }.sortedBy { it.position }.mapNotNull { map[it.songId] } }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(pl.name) }
    val drag = remember { DragState() }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    val coverPicked = tr("Cover updated", "কভার পরিবর্তন হয়েছে")

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(pl.name, { ui.back() }) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Rename", "নাম বদলান")) }, onClick = { menu = false; newName = pl.name; rename = true })
                    DropdownMenuItem(text = { Text(tr("Choose cover image", "কভার ছবি")) }, onClick = {
                        menu = false
                        actions.pickImage { uri -> actions.saveImage(uri, "pl_$id")?.let { c.setPlaylistCover(id, it); actions.toast(coverPicked) } }
                    })
                    DropdownMenuItem(text = { Text(tr("Auto collage cover", "অটো কোলাজ কভার")) }, onClick = { menu = false; c.setPlaylistCover(id, null) })
                    DropdownMenuItem(text = { Text(tr("Export M3U", "M3U এক্সপোর্ট")) }, onClick = {
                        menu = false
                        actions.createDocument("${pl.name}.m3u") { out -> out.write(("#EXTM3U\n" + list.joinToString("\n") { it.path }).toByteArray()) }
                    })
                    DropdownMenuItem(text = { Text(tr("Delete playlist", "প্লেলিস্ট মুছুন")) }, onClick = { menu = false; delete = true })
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaylistCover(pl, list, Modifier.size(56.dp))
            GlassButton(tr("Play", "চালান"), Icons.Default.PlayArrow, Modifier.align(Alignment.CenterVertically)) { vm.playSongs(list, 0) }
            GlassButton(tr("Shuffle", "শাফল"), Icons.Default.Shuffle, Modifier.align(Alignment.CenterVertically)) { vm.shuffleAll(list) }
        }
        Text(tr("Long-press a row and drag to reorder", "সাজাতে সারি চেপে ধরে টানুন"), fontSize = 11.sp,
            color = LocalText.current.copy(0.6f), modifier = Modifier.padding(horizontal = 16.dp))
        if (list.isEmpty()) EmptyState(tr("Playlist is empty", "প্লেলিস্ট খালি"))
        else LazyColumn {
            itemsIndexed(list, key = { _, s -> s.id }) { i, song ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .dragReorder(i, list.size, rowPx, drag) { from, to ->
                            val m = list.toMutableList(); val it = m.removeAt(from); m.add(to, it)
                            c.reorderPlaylist(id, m.map { s -> s.id })
                        }
                        .clickable { vm.playSongs(list, i) }.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongArt(song, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (now?.song?.id == song.id) LocalAccent.current else LocalText.current)
                        Text(song.artist, maxLines = 1, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                    }
                    IconButton(onClick = { c.removeFromPlaylist(id, song.id) }) { Icon(Icons.Default.RemoveCircleOutline, null) }
                    Icon(Icons.Default.DragHandle, null, Modifier.padding(end = 12.dp), tint = LocalText.current.copy(0.5f))
                }
            }
        }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text(tr("Rename", "নাম বদলান")) },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { c.renamePlaylist(id, newName); rename = false }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(tr("Cancel", "বাতিল")) } })
    if (delete) AlertDialog(
        onDismissRequest = { delete = false }, title = { Text(tr("Delete playlist?", "প্লেলিস্ট মুছবেন?")) },
        confirmButton = { TextButton(onClick = { delete = false; c.deletePlaylist(id); ui.back() }) { Text(tr("Delete", "মুছুন")) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(tr("Cancel", "বাতিল")) } })
    if (adding) PickSongsDialog(all, { adding = false }) { ids -> c.addToPlaylist(id, ids); adding = false }
}

@Composable
fun PickSongsDialog(all: List<Song>, onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    var q by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    val shown = remember(all, q) { if (q.isBlank()) all.take(300) else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(300) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add songs", "গান যোগ করুন")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(shown, key = { _, s -> s.id }) { _, s ->
                        Row(Modifier.fillMaxWidth().clickable { if (s.id in picked) picked.remove(s.id) else picked.add(s.id) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(s.id in picked, { if (it) picked.add(s.id) else picked.remove(s.id) })
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked.toList()) }) { Text(tr("Add", "যোগ") + " (${picked.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } }
    )
}

@Composable
fun AddToPlaylistDialog(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val pls by c.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add to playlist", "প্লেলিস্টে যোগ করুন")) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    itemsIndexed(pls, key = { _, p -> p.id }) { _, p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { c.addToPlaylist(p.id, listOf(song.id)); onDismiss() }.padding(12.dp))
                    }
                }
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(tr("New playlist name", "নতুন প্লেলিস্টের নাম")) })
            }
        },
        confirmButton = {
            TextButton(onClick = { if (newName.isNotBlank()) c.createPlaylist(newName) { id -> c.addToPlaylist(id, listOf(song.id)) }; onDismiss() }) {
                Text(tr("Create & add", "তৈরি করে যোগ"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } }
    )
}

// ---------------------------------------------------------------- queue

@Composable
fun QueueScreen() {
    val vm = LocalVm.current
    val ui = LocalUi.current
    val q by vm.queue.collectAsState()
    val idx by vm.queueIndex.collectAsState()
    val drag = remember { DragState() }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Queue", "কিউ") + " (${q.size})", { ui.back() })
        if (q.isEmpty()) { EmptyState(tr("Queue is empty", "কিউ খালি"), Icons.Default.QueueMusic); return@Column }
        Text(tr("Long-press a row and drag to reorder", "সাজাতে সারি চেপে ধরে টানুন"), fontSize = 11.sp,
            color = LocalText.current.copy(0.6f), modifier = Modifier.padding(horizontal = 16.dp))
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 2).coerceAtLeast(0))
        LazyColumn(state = state) {
            itemsIndexed(q) { i, e ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .dragReorder(i, q.size, rowPx, drag) { from, to -> vm.moveQueue(from, to) }
                        .clickable { vm.skipTo(i) }.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongArt(e.song, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (i == idx) LocalAccent.current else LocalText.current,
                            fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal)
                        Text(e.artist, maxLines = 1, fontSize = 12.sp, color = LocalText.current.copy(0.7f))
                    }
                    IconButton(onClick = { vm.removeQueue(i) }) { Icon(Icons.Default.Close, null) }
                    Icon(Icons.Default.DragHandle, null, Modifier.padding(end = 12.dp), tint = LocalText.current.copy(0.5f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val vm = LocalVm.current
    val ui = LocalUi.current
    val songs by c.songs.collectAsState()
    val lyrics by c.lyricsAll.collectAsState()
    var q by remember { mutableStateOf("") }
    val now by vm.now.collectAsState()

    val res = remember(q, songs, lyrics) {
        if (q.isBlank()) null else {
            val t = q.trim()
            val sg = songs.filter { it.title.contains(t, true) || it.artist.contains(t, true) || it.album.contains(t, true) }.take(100)
            val artists = songs.map { it.artist }.distinct().filter { it.contains(t, true) }.take(20)
            val albums = songs.groupBy { it.albumId }.values.map { it.first() }.filter { it.album.contains(t, true) }.take(20)
            val folders = songs.map { it.folder }.distinct().filter { it.substringAfterLast('/').contains(t, true) }.take(20)
            val lyr = songs.filter { (lyrics[it.id] ?: "").contains(t, true) }.take(30)
            listOf(sg, lyr) to Triple(artists, albums, folders)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Search", "খুঁজুন"))
        OutlinedTextField(
            q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, null) } },
            placeholder = { Text(tr("Songs, artists, albums, folders, lyrics", "গান, আর্টিস্ট, অ্যালবাম, ফোল্ডার, লিরিক্স")) },
            shape = RoundedCornerShape(50)
        )
        if (res == null) { EmptyState(tr("Type to search", "খুঁজতে লিখুন"), Icons.Default.Search); return@Column }
        val (lists, groups) = res
        val (sg, lyr) = lists
        val (artists, albums, folders) = groups
        val hSongs = tr("Songs", "গান")
        val hArtists = tr("Artists", "আর্টিস্ট")
        val hAlbums = tr("Albums", "অ্যালবাম")
        val hFolders = tr("Folders", "ফোল্ডার")
        val hLyrics = tr("In lyrics", "লিরিক্সে পাওয়া গেছে")
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            fun header(t: String) = item { Text(t, Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold, color = LocalAccent.current) }
            if (sg.isNotEmpty()) {
                header(hSongs)
                itemsIndexed(sg, key = { _, s -> "s${s.id}" }) { i, s ->
                    SongRow(s, now?.song?.id == s.id, 0, onClick = { vm.playSongs(sg, i) }, onMore = { ui.menuSong = s })
                }
            }
            if (artists.isNotEmpty()) {
                header(hArtists)
                itemsIndexed(artists, key = { _, a -> "a$a" }) { _, a ->
                    Text(a, Modifier.fillMaxWidth().clickable { ui.push(Route.Artist(a)) }.padding(16.dp, 10.dp))
                }
            }
            if (albums.isNotEmpty()) {
                header(hAlbums)
                itemsIndexed(albums, key = { _, a -> "al${a.albumId}" }) { _, a ->
                    Text(a.album, Modifier.fillMaxWidth().clickable { ui.push(Route.Album(a.albumId, a.album)) }.padding(16.dp, 10.dp))
                }
            }
            if (folders.isNotEmpty()) {
                header(hFolders)
                itemsIndexed(folders, key = { _, f -> "f$f" }) { _, f ->
                    Text(f, Modifier.fillMaxWidth().clickable { ui.push(Route.Folder(f)) }.padding(16.dp, 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (lyr.isNotEmpty()) {
                header(hLyrics)
                itemsIndexed(lyr, key = { _, s -> "l${s.id}" }) { i, s ->
                    SongRow(s, now?.song?.id == s.id, 0, onClick = { vm.playSongs(lyr, i) }, onMore = { ui.menuSong = s })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- YouTube

@Composable
fun OnlineScreen() {
    val c = LocalContainer.current
    val vm = LocalVm.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YtResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val msg by vm.message.collectAsState()
    fun go() {
        if (q.isBlank()) return
        loading = true; error = null
        scope.launch {
            try { results = YouTube.search(c, q.trim()) } catch (e: Throwable) { error = e.message ?: "Search failed"; results = emptyList() }
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Online (YouTube)", "অনলাইন (YouTube)"))
        OutlinedTextField(
            q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Default.Check, null) } },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            placeholder = { Text(tr("Search songs on YouTube", "YouTube-এ গান খুঁজুন")) }, shape = RoundedCornerShape(50)
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        (error ?: msg)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (results.isEmpty() && !loading) { EmptyState(tr("Search to stream music online", "অনলাইনে গান শুনতে খুঁজুন"), Icons.Default.Public); return@Column }
        LazyColumn {
            itemsIndexed(results, key = { _, r -> r.url }) { _, r ->
                Row(Modifier.fillMaxWidth().clickable { vm.playOnline(r) }.padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(r.thumb, null, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${r.uploader} • ${fmt(r.durationSec * 1000)}", fontSize = 12.sp, color = LocalText.current.copy(0.7f), maxLines = 1)
                    }
                    IconButton(onClick = { vm.playOnline(r, true) }) { Icon(Icons.Default.PlaylistAdd, null) }
                }
            }
        }
    }
}
