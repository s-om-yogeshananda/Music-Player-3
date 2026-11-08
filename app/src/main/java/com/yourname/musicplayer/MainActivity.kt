package com.yourname.musicplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.yourname.musicplayer.data.AppSettings
import com.yourname.musicplayer.data.K
import com.yourname.musicplayer.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var c: AppContainer
    private lateinit var actions: Actions
    private val ui = UiState()
    private var perm by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perm = c.hasAudioPermission()
        if (perm) c.rescan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = (application as MusicApplication).container
        WindowCompat.setDecorFitsSystemWindows(window, false)
        actions = Actions(this, c)
        perm = c.hasAudioPermission()
        if (perm) c.rescan()
        setContent { AppRoot() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestPerms() {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.READ_MEDIA_AUDIO); l.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permLauncher.launch(l.toTypedArray())
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            "com.yourname.musicplayer.SHUFFLE_ALL" -> lifecycleScope.launch {
                withTimeoutOrNull(8000) { c.songs.first { it.isNotEmpty() } }
                delay(400)
                vm.shuffleAll(c.songs.value)
            }
            "com.yourname.musicplayer.RECENT" -> { ui.stack.clear(); ui.rootTab = 0; ui.libTab = 7 }
            "com.yourname.musicplayer.FAVORITES" -> { ui.stack.clear(); ui.rootTab = 0; ui.libTab = 6 }
            Intent.ACTION_VIEW -> i.data?.let { uri -> lifecycleScope.launch { delay(700); vm.playUri(uri) } }
        }
    }

    @Composable
    private fun AppRoot() {
        var splashDone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(3_300)
            splashDone = true
        }
        val sState = produceState<AppSettings?>(null) { c.settings.uiFlow.collect { value = it } }
        val ready = sState.value != null && splashDone
        Crossfade(targetState = ready, animationSpec = tween(650), label = "splash") { isReady ->
            val s = sState.value
            if (!isReady || s == null) SplashScreen() else AppContent(s)
        }
    }

    @Composable
    private fun AppContent(s: AppSettings) {
        val now by vm.now.collectAsState()
        var dyn by remember { mutableStateOf<List<Color>?>(null) }
        LaunchedEffect(now?.mediaId, s.theme) {
            dyn = if (s.theme == "dynamic") (paletteColors(c, now?.song) ?: dyn) else null
        }
        val bn = s.language == "bn" || (s.language == "system" && Locale.getDefault().language == "bn")

        CompositionLocalProvider(
            LocalContainer provides c, LocalSettings provides s, LocalVm provides vm,
            LocalUi provides ui, LocalActions provides actions, LocalBn provides bn
        ) {
            AppTheme(s, dyn) { colors ->
                val dark = LocalIsDark.current
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                GlassBackground(colors) {
                    when {
                        !s.onboarded -> OnboardingScreen { c.set(K.ONBOARDED, true) }
                        !perm -> PermissionScreen { requestPerms() }
                        else -> MainScaffold()
                    }
                }
            }
        }
    }

    @Composable
    private fun MainScaffold() {
        val np by vm.now.collectAsState()
        BackHandler(enabled = ui.playerOpen || ui.stack.isNotEmpty() || ui.rootTab != 0) {
            when {
                ui.playerOpen -> ui.playerOpen = false
                ui.stack.isNotEmpty() -> ui.back()
                else -> ui.rootTab = 0
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ScreenHost() }
                if (np != null) MiniPlayer()
                GlassBottomNav()
            }
            AnimatedVisibility(
                visible = ui.playerOpen,
                enter = slideInVertically(tween(380)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(320)) { it } + fadeOut(tween(250))
            ) { FullPlayer() }
        }
        ui.menuSong?.let { SongMenu(it) { ui.menuSong = null } }
        ui.playlistPick?.let { AddToPlaylistDialog(it) { ui.playlistPick = null } }
    }

    @Composable
    private fun ScreenHost() {
        AnimatedContent(
            targetState = ui.stack.lastOrNull() ?: Route.Root(ui.rootTab),
            transitionSpec = {
                val fromRoot = initialState is Route.Root
                val toRoot = targetState is Route.Root
                if (fromRoot && toRoot) {
                    // bottom-tab switch: plain, cheap cross-fade
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                } else if (toRoot) {
                    // going back
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 12 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { it / 12 })
                } else {
                    // going deeper
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 12 })
                }
            },
            label = "nav"
        ) { r ->
            when (r) {
                is Route.Root -> when (r.tab) {
                    0 -> LibraryScreen()
                    1 -> SearchScreen()
                    2 -> SettingsScreen()
                    else -> DeveloperScreen()
                }
                is Route.Folder -> FolderScreen(r.path)
                is Route.Album -> AlbumScreen(r.id, r.name)
                is Route.Artist -> ArtistScreen(r.name)
                is Route.Genre -> GenreScreen(r.name)
                is Route.Playlist -> PlaylistDetailScreen(r.id)
                is Route.Details -> DetailsScreen(r.id)
                is Route.TagEdit -> TagEditScreen(r.id)
                is Route.Trim -> TrimScreen(r.id)
                is Route.LyricsEdit -> LyricsEditScreen(r.id)
                Route.Queue -> QueueScreen()
                Route.Eq -> EqScreen()
                Route.Themes -> ThemesScreen()
                Route.FolderFilter -> FolderFilterScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- splash

@Composable
private fun InfiniteTransition.bar(ms: Int): State<Float> =
    animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar"
    )

@Composable
private fun SplashScreen() {
    val title = "Music Player"
    val charAnims = remember { List(title.length) { Animatable(0f) } }
    val logoScale = remember { Animatable(0.55f) }
    val logoAlpha = remember { Animatable(0f) }
    val lineScale = remember { Animatable(0f) }
    val poweredAlpha = remember { Animatable(0f) }
    val nameAlpha = remember { Animatable(0f) }
    val nameShift = remember { Animatable(26f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(500)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 170f)) }
        delay(500)
        charAnims.forEachIndexed { i, a ->
            launch { delay(i * 45L); a.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        }
        delay(title.length * 45L + 200)
        launch { lineScale.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(300)
        launch { poweredAlpha.animateTo(1f, tween(550)) }
        delay(350)
        launch { nameAlpha.animateTo(1f, tween(750)) }
        launch { nameShift.animateTo(0f, tween(750, easing = FastOutSlowInEasing)) }
    }

    val inf = rememberInfiniteTransition(label = "splash")
    val glow = inf.animateFloat(
        initialValue = 0.88f, targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val b0 = inf.bar(520); val b1 = inf.bar(700); val b2 = inf.bar(610); val b3 = inf.bar(820); val b4 = inf.bar(560)
    val bars = listOf(b0 to 26, b1 to 44, b2 to 60, b3 to 38, b4 to 50)

    val violet = Color(0xFF9B7BFF)
    val teal = Color(0xFF36D9C5)

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0B1A), Color(0xFF241650), Color(0xFF0B0B1A)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // logo with pulsing glow
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(250.dp)
                        .graphicsLayer { scaleX = glow.value; scaleY = glow.value; alpha = logoAlpha.value }
                        .background(Brush.radialGradient(listOf(violet.copy(alpha = 0.42f), Color.Transparent)), CircleShape)
                )
                Box(
                    Modifier.size(120.dp)
                        .graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value }
                        .background(Brush.linearGradient(listOf(violet, teal)), RoundedCornerShape(36.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.height(66.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        bars.forEach { (st, base) ->
                            Box(
                                Modifier.width(8.dp).height(base.dp)
                                    .graphicsLayer { scaleY = st.value }
                                    .clip(RoundedCornerShape(8.dp)).background(Color.White)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // title, letter by letter
            Row {
                title.forEachIndexed { i, ch ->
                    val a = charAnims[i]
                    Text(
                        ch.toString(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 30f }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.width(150.dp).height(3.dp)
                    .graphicsLayer { scaleX = lineScale.value }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(violet, teal)))
            )
            Spacer(Modifier.height(30.dp))
            Text(
                "POWERED BY", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 5.sp,
                modifier = Modifier.graphicsLayer { alpha = poweredAlpha.value }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "S. Om. Yogeshananda",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Color(0xFFC9B8FF), teal, Color(0xFFC9B8FF))),
                    fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
                ),
                modifier = Modifier.graphicsLayer { alpha = nameAlpha.value; translationY = nameShift.value }
            )
        }
    }
}
