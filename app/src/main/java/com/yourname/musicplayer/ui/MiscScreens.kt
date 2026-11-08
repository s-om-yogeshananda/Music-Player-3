package com.yourname.musicplayer.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.musicplayer.BuildConfig
import com.yourname.musicplayer.data.*
import com.yourname.musicplayer.player.EQ_PRESET_CURVES
import com.yourname.musicplayer.player.presetBands
import com.yourname.musicplayer.util.Trimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ================================================================= small building blocks

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glass().padding(16.dp), content = content)
}

@Composable
fun SectionTitle(t: String) {
    Text(t, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp), fontWeight = FontWeight.Bold, color = LocalAccent.current, fontSize = 13.sp)
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, fontSize = 12.sp, color = LocalText.current.copy(0.65f))
        }
        Switch(checked, onChange)
    }
}

@Composable
fun ClickRow(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LocalAccent.current)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, fontSize = 12.sp, color = LocalText.current.copy(0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = LocalText.current.copy(0.4f))
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                options.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(k); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(k == current, { onPick(k); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } }
    )
}

// ================================================================= bottom nav

@Composable
fun GlassBottomNav() {
    val ui = LocalUi.current
    val tick = rememberTick()
    val items = listOf(
        Triple(Icons.Default.LibraryMusic, "Library", "লাইব্রেরি"),
        Triple(Icons.Default.Search, "Search", "খুঁজুন"),
        Triple(Icons.Default.Settings, "Settings", "সেটিংস"),
        Triple(Icons.Default.Code, "Developer", "ডেভেলপার")
    )
    val bn = LocalBn.current
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .glass(RoundedCornerShape(26.dp), 1.2f).padding(5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEachIndexed { i, (icon, en, bnL) ->
            val sel = ui.rootTab == i && ui.stack.isEmpty()
            val bg by animateColorAsState(
                if (sel) LocalAccent.current.copy(0.25f) else Color.Transparent,
                tween(260), label = "nav"
            )
            val sc by animateFloatAsState(
                if (sel) 1.08f else 1f, spring(dampingRatio = 0.65f, stiffness = 420f),
                label = "nav-scale"
            )
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                    .background(bg).clickable { tick(); ui.goRoot(i) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, Modifier.graphicsLayer { scaleX = sc; scaleY = sc },
                    tint = if (sel) LocalAccent.current else LocalText.current.copy(0.78f))
                Text(if (bn) bnL else en, fontSize = 11.sp,
                    color = if (sel) LocalAccent.current else LocalText.current.copy(0.78f))
            }
        }
    }
}

// ================================================================= onboarding / permission

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        Triple(Icons.Default.LibraryMusic, tr("All your music", "আপনার সব গান"), tr("Songs, folders, albums, artists and playlists in one glass-smooth place.", "গান, ফোল্ডার, অ্যালবাম, আর্টিস্ট ও প্লেলিস্ট এক জায়গায়।")),
        Triple(Icons.Default.Equalizer, tr("Sound your way", "আপনার মতো সাউন্ড"), tr("Equalizer, bass boost, volume boost up to 200%, sleep timer and more.", "ইকুয়ালাইজার, বেস বুস্ট, ২০০% পর্যন্ত ভলিউম বুস্ট, স্লিপ টাইমার ও আরও অনেক কিছু।")),
        Triple(Icons.Default.Palette, tr("Make it yours", "নিজের মতো সাজান"), tr("10 gradient themes, player styles, synced lyrics and a home-screen widget.", "১০টি গ্রেডিয়েন্ট থিম, প্লেয়ার স্টাইল, সিঙ্কড লিরিক্স ও হোম স্ক্রিন উইজেট।"))
    )
    val state = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state, Modifier.weight(1f)) { p ->
            val (icon, t, d) = pages[p]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(160.dp).glass(CircleShape, 1.4f), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(80.dp), tint = LocalAccent.current)
                }
                Spacer(Modifier.height(28.dp))
                Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(d, textAlign = TextAlign.Center, color = LocalText.current.copy(0.8f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            repeat(pages.size) { i ->
                Box(Modifier.size(if (state.currentPage == i) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                    .background(if (state.currentPage == i) LocalAccent.current else LocalText.current.copy(0.3f)))
            }
        }
        GlassButton(if (state.currentPage == pages.size - 1) tr("Get started", "শুরু করুন") else tr("Next", "পরেরটি"), modifier = Modifier.padding(16.dp)) {
            if (state.currentPage == pages.size - 1) onDone() else scope.launch { state.animateScrollToPage(state.currentPage + 1) }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(140.dp).glass(CircleShape, 1.4f), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Lock, null, Modifier.size(64.dp), tint = LocalAccent.current)
        }
        Spacer(Modifier.height(24.dp))
        Text(tr("Allow music access", "মিউজিক অ্যাক্সেস দিন"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(tr("To show the songs on your device, the app needs permission to read audio files (and to show playback notifications).",
            "আপনার ডিভাইসের গান দেখানোর জন্য অডিও ফাইল পড়ার (এবং নোটিফিকেশন দেখানোর) অনুমতি দরকার।"), textAlign = TextAlign.Center, color = LocalText.current.copy(0.8f))
        Spacer(Modifier.height(24.dp))
        GlassButton(tr("Grant permission", "অনুমতি দিন"), Icons.Default.Check, onClick = onRequest)
    }
}

// ================================================================= equalizer

private fun parseCustom(s: String): Map<String, List<Int>> {
    val m = LinkedHashMap<String, List<Int>>()
    s.split(";").filter { it.contains("|") }.forEach {
        val (n, v) = it.split("|", limit = 2)
        m[n] = v.split(",").mapNotNull { x -> x.toIntOrNull() }
    }
    return m
}

@Composable
fun EqScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val s = LocalSettings.current
    val info by c.fxInfo.collectAsState()
    val custom = remember(s.customPresets) { parseCustom(s.customPresets) }
    var saveDlg by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val inf = info

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Equalizer & Boost", "ইকুয়ালাইজার ও বুস্ট"), { ui.back() }) {
            TextButton(onClick = {
                c.editPrefs { p ->
                    p[K.EQ_ON] = false; p[K.EQ_PRESET] = "Normal"; p[K.EQ_BANDS] = ""; p[K.BASS] = 0; p[K.VIRT] = 0
                    p[K.BOOST] = 100; p[K.NORMALIZE] = false
                }
            }) { Text(tr("Reset", "রিসেট")) }
        }
        GlassCard {
            SwitchRow(tr("Equalizer", "ইকুয়ালাইজার"), null, s.eqOn) { c.set(K.EQ_ON, it) }
            if (inf == null) {
                Text(tr("Start playing a song to load this device's equalizer bands.", "ডিভাইসের ব্যান্ড লোড করতে আগে একটি গান চালান।"),
                    fontSize = 12.sp, color = LocalText.current.copy(0.7f))
            } else {
                val names = EQ_PRESET_CURVES.keys.toList() + custom.keys.toList()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    itemsIndexed(names + "Custom") { _, name ->
                        FilterChip(selected = s.eqPreset == name, onClick = {
                            val bands = when {
                                name == "Custom" -> s.eqBandsList()
                                custom.containsKey(name) -> custom.getValue(name)
                                else -> presetBands(name, inf.bands, inf.min, inf.max)
                            }
                            c.editPrefs { p -> p[K.EQ_PRESET] = name; p[K.EQ_BANDS] = bands.joinToString(","); p[K.EQ_ON] = true }
                        }, label = { Text(name) })
                    }
                }
                val cur = s.eqBandsList().let { if (it.size == inf.bands) it else List(inf.bands) { 0 } }
                for (i in 0 until inf.bands) {
                    val f = inf.freqs[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (f >= 1000) "%.1fk".format(f / 1000f) else "$f", Modifier.width(48.dp), fontSize = 12.sp)
                        Slider(
                            cur[i].toFloat(), { v ->
                                val l = cur.toMutableList(); l[i] = v.toInt()
                                c.editPrefs { p -> p[K.EQ_BANDS] = l.joinToString(","); p[K.EQ_PRESET] = "Custom" }
                            },
                            valueRange = inf.min.toFloat()..inf.max.toFloat(), enabled = s.eqOn, modifier = Modifier.weight(1f)
                        )
                        Text("${cur[i] / 100}dB", Modifier.width(44.dp), fontSize = 11.sp, textAlign = TextAlign.End)
                    }
                }
                TextButton(onClick = { newName = ""; saveDlg = true }) { Text(tr("Save as custom preset", "কাস্টম প্রিসেট হিসেবে সেভ করুন")) }
            }
        }
        GlassCard {
            Text(tr("Volume boost", "ভলিউম বুস্ট") + ": ${s.boost}%", fontWeight = FontWeight.SemiBold)
            Slider(s.boost.toFloat(), { c.set(K.BOOST, it.toInt()) }, valueRange = 0f..200f)
            if (s.boost > 100) Text("⚠ " + tr("High boost may damage speakers or your ears", "বেশি বুস্টে স্পিকার/কানের ক্ষতি হতে পারে"),
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(tr("Bass boost", "বেস বুস্ট") + ": ${s.bass / 10}%")
            Slider(s.bass.toFloat(), { c.set(K.BASS, it.toInt()) }, valueRange = 0f..1000f)
            Text(tr("Virtualizer (3D / surround)", "ভার্চুয়ালাইজার (৩ডি / সারাউন্ড)") + ": ${s.virt / 10}%")
            Slider(s.virt.toFloat(), { c.set(K.VIRT, it.toInt()) }, valueRange = 0f..1000f)
            SwitchRow(tr("Loudness normalization (basic)", "লাউডনেস নরমালাইজেশন (সাধারণ)"), tr("Adds a small +3 dB lift", "সামান্য +৩ dB লিফট দেয়"), s.normalize) { c.set(K.NORMALIZE, it) }
        }
    }
    if (saveDlg) AlertDialog(
        onDismissRequest = { saveDlg = false }, title = { Text(tr("Preset name", "প্রিসেটের নাম")) },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                val nm = newName.replace(";", "").replace("|", "").trim()
                if (nm.isNotEmpty() && inf != null) {
                    val bands = s.eqBandsList().let { if (it.size == inf.bands) it else List(inf.bands) { 0 } }
                    val all = s.customPresets + (if (s.customPresets.isEmpty()) "" else ";") + nm + "|" + bands.joinToString(",")
                    c.editPrefs { p -> p[K.CUSTOM_PRESETS] = all; p[K.EQ_PRESET] = nm }
                }
                saveDlg = false
            }) { Text(tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = { saveDlg = false }) { Text(tr("Cancel", "বাতিল")) } })
}

// ================================================================= settings

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val s = LocalSettings.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    var dlg by remember { mutableStateOf("") }
    val okMsg = tr("Done", "সম্পন্ন")
    val failMsg = tr("Failed", "ব্যর্থ হয়েছে")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Settings", "সেটিংস"))
        SectionTitle(tr("Appearance", "চেহারা"))
        GlassCard {
            ClickRow(Icons.Default.Palette, tr("Theme & accent color", "থিম ও অ্যাকসেন্ট কালার"), Themes.firstOrNull { it.id == s.theme }?.name) { ui.push(Route.Themes) }
            ClickRow(Icons.Default.Album, tr("Full player style", "ফুল প্লেয়ার স্টাইল"), s.playerStyle.replaceFirstChar { it.uppercase() }) { dlg = "style" }
            ClickRow(Icons.Default.Brightness6, tr("Dark / Light / System", "ডার্ক / লাইট / সিস্টেম"), s.darkMode) { dlg = "dark" }
            ClickRow(Icons.Default.Language, tr("Language", "ভাষা"), when (s.language) { "en" -> "English"; "bn" -> "বাংলা"; else -> "System" }) { dlg = "lang" }
        }
        SectionTitle(tr("Library", "লাইব্রেরি"))
        GlassCard {
            SwitchRow(tr("Hide short audio", "ছোট অডিও লুকান"), tr("Hide clips shorter than ${s.minSec}s", "${s.minSec} সেকেন্ডের কম অডিও লুকানো থাকবে"), s.hideShort) { c.set(K.HIDE_SHORT, it) }
            if (s.hideShort) Slider(s.minSec.toFloat(), { c.set(K.MIN_SEC, it.toInt()) }, valueRange = 10f..120f)
            ClickRow(Icons.Default.FolderOpen, tr("Include / exclude folders", "ফোল্ডার ইনক্লুড / এক্সক্লুড"),
                "${s.included.size} " + tr("included", "ইনক্লুড") + ", ${s.excluded.size} " + tr("excluded", "এক্সক্লুড")) { ui.push(Route.FolderFilter) }
        }
        SectionTitle(tr("Playback & audio", "প্লেব্যাক ও অডিও"))
        GlassCard {
            ClickRow(Icons.Default.PlaylistPlay, tr("Play mode", "প্লে মোড"),
                if (s.playMode == "single") tr("Stop after each song", "একটা গান শেষে থামবে") else tr("Continuous", "একটার পর একটা")) { dlg = "mode" }
            SwitchRow(tr("Keep playing when app is swiped away", "অ্যাপ সোয়াইপ করলেও গান চলবে"), null, s.keepPlaying) { c.set(K.KEEP_PLAYING, it) }
            ClickRow(Icons.Default.Equalizer, tr("Equalizer & Boost", "ইকুয়ালাইজার ও বুস্ট")) { ui.push(Route.Eq) }
            SwitchRow(tr("Audio visualizer", "অডিও ভিজ্যুয়ালাইজার"), tr("Uses a little more battery", "একটু বেশি ব্যাটারি খরচ হয়"), s.visualizer) { c.set(K.VISUALIZER, it) }
            if (s.visualizer) ClickRow(Icons.Default.GraphicEq, tr("Visualizer type", "ভিজ্যুয়ালাইজার ধরন"), s.visType) { dlg = "vis" }
        }
        SectionTitle(tr("Backup", "ব্যাকআপ"))
        GlassCard {
            ClickRow(Icons.Default.FileUpload, tr("Backup (export JSON)", "ব্যাকআপ (JSON এক্সপোর্ট)"),
                tr("Playlists, favorites, lyrics, play counts, settings", "প্লেলিস্ট, ফেভারিট, লিরিক্স, প্লে কাউন্ট, সেটিংস")) {
                scope.launch {
                    val json = try { c.exportBackup() } catch (_: Throwable) { null }
                    if (json == null) actions.toast(failMsg)
                    else actions.createDocument("music_player_backup.json") { it.write(json.toByteArray()) }
                }
            }
            ClickRow(Icons.Default.FileDownload, tr("Restore (import JSON)", "রিস্টোর (JSON ইমপোর্ট)")) {
                actions.openDocument(arrayOf("application/json", "*/*")) { uri ->
                    scope.launch {
                        val text = try { withContext(Dispatchers.IO) { actions.readText(uri) } } catch (_: Throwable) { "" }
                        actions.toast(if (c.importBackup(text)) okMsg else failMsg)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    when (dlg) {
        "style" -> ChoiceDialog(tr("Full player style", "ফুল প্লেয়ার স্টাইল"),
            listOf("classic" to "Classic", "vinyl" to "Vinyl Disc", "square" to "Square Art", "blur" to "Blur Background", "minimal" to "Minimal"),
            s.playerStyle, { dlg = "" }) { c.set(K.PLAYER_STYLE, it) }
        "dark" -> ChoiceDialog(tr("Mode", "মোড"), listOf("system" to "System", "dark" to "Dark", "light" to "Light"), s.darkMode, { dlg = "" }) { c.set(K.DARK, it) }
        "lang" -> ChoiceDialog(tr("Language", "ভাষা"), listOf("system" to "System", "en" to "English", "bn" to "বাংলা"), s.language, { dlg = "" }) { c.set(K.LANG, it) }
        "mode" -> ChoiceDialog(tr("Play mode", "প্লে মোড"),
            listOf("continuous" to tr("Continuous (one after another)", "একটার পর একটা চলবে"), "single" to tr("Play single (stop after each song)", "একটা গান শেষ হলে থেমে যাবে")),
            s.playMode, { dlg = "" }) { c.set(K.PLAY_MODE, it) }
        "vis" -> ChoiceDialog(tr("Visualizer", "ভিজ্যুয়ালাইজার"), listOf("bars" to "Bars", "wave" to "Wave", "circular" to "Circular"), s.visType, { dlg = "" }) { c.set(K.VIS_TYPE, it) }
    }
}

// ================================================================= themes

@Composable
fun ThemesScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val s = LocalSettings.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Themes", "থিম"), { ui.back() })
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.weight(1f)) {
            items(Themes.size) { i ->
                val t = Themes[i]
                val sel = s.theme == t.id
                Column(Modifier.padding(6.dp).clickable { c.set(K.THEME, t.id) }) {
                    Box(
                        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(t.colors))
                            .border(if (sel) 3.dp else 1.dp, if (sel) LocalAccent.current else Color.White.copy(0.3f), RoundedCornerShape(20.dp))
                    ) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().height(34.dp).glass(RoundedCornerShape(14.dp)))
                        Box(Modifier.align(Alignment.TopStart).padding(10.dp).size(30.dp).clip(RoundedCornerShape(8.dp)).background(t.accent))
                        if (sel) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(8.dp), tint = Color.White)
                    }
                    Text(t.name, Modifier.padding(top = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Text(tr("Accent color", "অ্যাকসেন্ট কালার"), Modifier.padding(start = 20.dp), fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Box(Modifier.size(38.dp).clip(CircleShape).border(2.dp, if (s.accent == 0) LocalAccent.current else Color.White.copy(0.4f), CircleShape)
                    .clickable { c.set(K.ACCENT, 0) }, contentAlignment = Alignment.Center) { Text("A", fontWeight = FontWeight.Bold) }
            }
            items(AccentChoices.size) { i ->
                val col = AccentChoices[i]
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color(col))
                    .border(if (s.accent == col) 3.dp else 1.dp, if (s.accent == col) Color.White else Color.White.copy(0.4f), CircleShape)
                    .clickable { c.set(K.ACCENT, col) })
            }
        }
    }
}

// ================================================================= folder include/exclude

@Composable
fun FolderFilterScreen() {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val s = LocalSettings.current
    val raw by c.rawSongs.collectAsState()
    val folders = remember(raw) { raw.groupBy { it.folder }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Include / exclude folders", "ফোল্ডার ইনক্লুড / এক্সক্লুড"), { ui.back() })
        Text(tr("Tap a folder to cycle: Normal → Only this (include) → Hidden (exclude). If any folder is set to include, only included folders are shown.",
            "ফোল্ডারে ট্যাপ করুন: সাধারণ → শুধু এটি (ইনক্লুড) → লুকানো (এক্সক্লুড)।"), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp), color = LocalText.current.copy(0.7f))
        LazyColumn {
            itemsIndexed(folders, key = { _, f -> f.first }) { _, (path, count) ->
                val state = if (path in s.included) 1 else if (path in s.excluded) 2 else 0
                Row(Modifier.fillMaxWidth().clickable {
                    val next = (state + 1) % 3
                    c.editPrefs { p ->
                        p[K.INCLUDED] = (p[K.INCLUDED] ?: emptySet()) - path
                        p[K.EXCLUDED] = (p[K.EXCLUDED] ?: emptySet()) - path
                        if (next == 1) p[K.INCLUDED] = (p[K.INCLUDED] ?: emptySet()) + path
                        if (next == 2) p[K.EXCLUDED] = (p[K.EXCLUDED] ?: emptySet()) + path
                    }
                }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$count • $path", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalText.current.copy(0.6f))
                    }
                    Text(when (state) { 1 -> tr("Only", "শুধু"); 2 -> tr("Hidden", "লুকানো"); else -> tr("Normal", "সাধারণ") },
                        color = if (state == 0) LocalText.current.copy(0.6f) else LocalAccent.current, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ================================================================= developer

@Composable
fun DeveloperScreen() {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Developer", "ডেভেলপার"))
        GlassCard(Modifier.padding(top = 8.dp)) {
            Text(tr("DEVELOPER", "ডেভেলপার"), fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("S. Om. Yogeshananda", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, null, tint = accent); Spacer(Modifier.width(12.dp))
                Column { Text(tr("Study", "পড়াশোনা"), fontSize = 11.sp, color = LocalText.current.copy(0.6f)); Text("BSc in CSE") }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, null, tint = accent); Spacer(Modifier.width(12.dp))
                Column { Text(tr("Varsity", "বিশ্ববিদ্যালয়"), fontSize = 11.sp, color = LocalText.current.copy(0.6f)); Text("Teesta University Rangpur") }
            }
        }
        GlassCard {
            Text(tr("Built with", "যা দিয়ে তৈরি"), fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Kotlin" to "Language", "Jetpack Compose + Material 3" to "UI", "AndroidX Media3 (ExoPlayer, MediaSession)" to "Audio engine",
                "Room" to "Database", "DataStore" to "Settings", "Coil" to "Images", 
                "LRCLIB" to "Online lyrics", "GitHub Actions" to "APK build"
            ).forEach { (a, b) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•  $a", Modifier.weight(1f))
                    Text(b, fontSize = 12.sp, color = LocalText.current.copy(0.6f))
                }
            }
        }
        Text("Music Player v${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = LocalText.current.copy(0.6f))
    }
}

// ================================================================= details / tags / lyrics edit / trim

@Composable
fun DetailsScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val song = c.songMap[id]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Song details", "গানের বিবরণ"), { ui.back() })
        if (song == null) { Text(tr("Song not found", "গান পাওয়া যায়নি"), Modifier.padding(20.dp)); return@Column }
        GlassCard {
            listOf(
                tr("Title", "নাম") to song.title, tr("Artist", "আর্টিস্ট") to song.artist, tr("Album", "অ্যালবাম") to song.album,
                tr("Genre", "জনরা") to song.genre, tr("Year", "বছর") to (if (song.year > 0) song.year.toString() else "-"),
                tr("Format", "ফরম্যাট") to "${song.ext.uppercase()} (${song.mime})",
                tr("Bitrate (approx.)", "বিটরেট (আনুমানিক)") to "${song.bitrateKbps} kbps",
                tr("Duration", "সময়কাল") to fmt(song.duration),
                tr("Size", "সাইজ") to "%.2f MB".format(song.size / 1048576.0),
                tr("Path", "পাথ") to song.path,
                tr("Date added", "যোগের তারিখ") to DateFormat.getDateTimeInstance().format(Date(song.dateAdded * 1000))
            ).forEach { (k, v) ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text(k, fontSize = 11.sp, color = LocalAccent.current)
                    Text(v)
                }
            }
        }
    }
}

@Composable
fun TagEditScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val actions = LocalActions.current
    val song = c.songMap[id]
    if (song == null) { LaunchedEffect(Unit) { ui.back() }; return }
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }
    var year by remember { mutableStateOf(if (song.year > 0) song.year.toString() else "") }
    val saved = tr("Saved", "সেভ হয়েছে")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Edit info", "তথ্য এডিট"), { ui.back() })
        GlassCard {
            OutlinedTextField(title, { title = it }, label = { Text(tr("Title", "নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, label = { Text(tr("Artist", "আর্টিস্ট")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(album, { album = it }, label = { Text(tr("Album", "অ্যালবাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(genre, { genre = it }, label = { Text(tr("Genre", "জনরা")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(year, { year = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text(tr("Year", "বছর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("Changes are stored inside the app (the audio file itself is not modified).", "পরিবর্তন অ্যাপের ভেতরে সেভ হয় (মূল অডিও ফাইল বদলায় না)।"),
                fontSize = 11.sp, color = LocalText.current.copy(0.6f), modifier = Modifier.padding(vertical = 8.dp))
            GlassButton(tr("Save", "সেভ"), Icons.Default.Check) {
                c.saveTag(TagEntity(id, title.ifBlank { null }, artist.ifBlank { null }, album.ifBlank { null }, genre.ifBlank { null }, year.toIntOrNull()))
                actions.toast(saved); ui.back()
            }
        }
    }
}

@Composable
fun LyricsEditScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val song = c.songMap[id]
    if (song == null) { LaunchedEffect(Unit) { ui.back() }; return }
    val existing by produceState<String?>(null, id) { value = com.yourname.musicplayer.lyrics.Lyrics.load(c, song) }
    var text by remember(existing) { mutableStateOf(existing ?: "") }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(tr("Edit lyrics", "লিরিক্স এডিট"), { ui.back() }) {
            TextButton(onClick = { c.saveLyrics(id, text); ui.back() }) { Text(tr("Save", "সেভ")) }
            TextButton(onClick = { c.saveLyrics(id, ""); ui.back() }) { Text(tr("Delete", "মুছুন")) }
        }
        Text(song.title, Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = LocalText.current.copy(0.7f))
        Text(tr("Plain text, or synced format like [01:23.45] line", "সাধারণ টেক্সট, অথবা সিঙ্কড ফরম্যাট যেমন [01:23.45] লাইন"), Modifier.padding(horizontal = 20.dp), fontSize = 11.sp, color = LocalText.current.copy(0.6f))
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxSize().padding(16.dp))
    }
}

@Composable
fun TrimScreen(id: Long) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val song = c.songMap[id]
    if (song == null) { LaunchedEffect(Unit) { ui.back() }; return }
    val total = song.duration.toFloat().coerceAtLeast(1000f)
    var range by remember { mutableStateOf(0f..minOf(total, 30_000f)) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    val okMsg = tr("Saved to Ringtones folder", "Ringtones ফোল্ডারে সেভ হয়েছে")
    val failMsg = tr("Trim failed. This works best with M4A/AAC files.", "কাটা যায়নি। M4A/AAC ফাইলে সবচেয়ে ভালো কাজ করে।")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Trim / ringtone cutter", "কাটুন / রিংটোন কাটার"), { ui.back() })
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongArt(song, Modifier.size(56.dp)); Spacer(Modifier.width(12.dp))
                Column { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(fmt(song.duration), fontSize = 12.sp) }
            }
            Spacer(Modifier.height(16.dp))
            Text("${fmt(range.start.toLong())}  →  ${fmt(range.endInclusive.toLong())}   (${fmt((range.endInclusive - range.start).toLong())})")
            RangeSlider(range, { range = it }, valueRange = 0f..total)
            Text(tr("Cuts the compressed audio without re-encoding. Best with M4A/AAC. Saved to the Ringtones folder.",
                "রি-এনকোড ছাড়া অডিও কাটে। M4A/AAC-তে সবচেয়ে ভালো। Ringtones ফোল্ডারে সেভ হয়।"), fontSize = 11.sp, color = LocalText.current.copy(0.65f))
            Spacer(Modifier.height(10.dp))
            if (busy) CircularProgressIndicator()
            else GlassButton(tr("Cut & save", "কেটে সেভ করুন"), Icons.Default.ContentCut) {
                busy = true
                scope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) { Trimmer.trim(c.app, song, range.start.toLong(), range.endInclusive.toLong()) }
                        savedUri = uri; actions.toast(okMsg)
                    } catch (e: Throwable) { actions.toast(failMsg) }
                    busy = false
                }
            }
            savedUri?.let { u ->
                Spacer(Modifier.height(10.dp))
                GlassButton(tr("Set as ringtone", "রিংটোন সেট করুন"), Icons.Default.Notifications) { actions.setRingtoneUri(u) }
            }
        }
    }
}
