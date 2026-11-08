package com.yourname.musicplayer.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.yourname.musicplayer.data.AppSettings

val LocalIsDark = staticCompositionLocalOf { true }
val LocalAccent = staticCompositionLocalOf { Color(0xFF8E7CFF) }
val LocalText = staticCompositionLocalOf { Color.White }
val LocalThemeColors = staticCompositionLocalOf { listOf(Color(0xFF1B2A6B), Color(0xFF6A3FD1), Color(0xFF16A38B)) }

data class ThemeDef(val id: String, val name: String, val colors: List<Color>, val forceDark: Boolean?, val accent: Color)

val Themes = listOf(
    ThemeDef("aurora", "Aurora Glass", listOf(Color(0xFF1B2A6B), Color(0xFF6A3FD1), Color(0xFF16A38B)), null, Color(0xFF7CF5D4)),
    ThemeDef("sunset", "Sunset Gradient", listOf(Color(0xFFFF8A3D), Color(0xFFFF4F79), Color(0xFFB83280)), null, Color(0xFFFFD36E)),
    ThemeDef("neon", "Neon Night", listOf(Color(0xFF0B0B1A), Color(0xFF1A0B2E), Color(0xFF05243A)), true, Color(0xFF00F0FF)),
    ThemeDef("ocean", "Ocean Breeze", listOf(Color(0xFF0A4D8C), Color(0xFF1294C9), Color(0xFF2EC4B6)), null, Color(0xFFB8F2FF)),
    ThemeDef("midnight", "Midnight Purple", listOf(Color(0xFF150F3A), Color(0xFF3A1C71), Color(0xFF5B2A86)), null, Color(0xFFD0A2FF)),
    ThemeDef("cherry", "Cherry Blossom", listOf(Color(0xFFE56B9A), Color(0xFFF6A5C0), Color(0xFFB65A9E)), null, Color(0xFFFFE0EC)),
    ThemeDef("forest", "Emerald Forest", listOf(Color(0xFF0B3D2E), Color(0xFF1B7F5C), Color(0xFF6BBF59)), null, Color(0xFFB6F5C8)),
    ThemeDef("amoled", "Minimal Dark AMOLED", listOf(Color.Black, Color(0xFF050505), Color.Black), true, Color(0xFFFFFFFF)),
    ThemeDef("frost", "Light Frost", listOf(Color(0xFFE8F1FB), Color(0xFFD6E4F5), Color(0xFFEFE8FB)), false, Color(0xFF3D6FE0)),
    ThemeDef("dynamic", "Dynamic (from artwork)", listOf(Color(0xFF1B2A6B), Color(0xFF6A3FD1), Color(0xFF16A38B)), null, Color(0xFFFFFFFF))
)

val AccentChoices = listOf(
    0xFF8E7CFF, 0xFF00E5FF, 0xFFFF4081, 0xFFFFC107, 0xFF69F0AE, 0xFFFF7043, 0xFF40C4FF, 0xFFE040FB, 0xFFFFFFFF
).map { it.toInt() }

private fun readableOn(c: Color): Color = if (c.luminance() > 0.55f) Color(0xFF111122) else Color.White

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun AppTheme(s: AppSettings, dyn: List<Color>?, content: @Composable (List<Color>) -> Unit) {
    val sysDark = isSystemInDarkTheme()
    val def = Themes.firstOrNull { it.id == s.theme } ?: Themes[0]
    val dark = def.forceDark ?: when (s.darkMode) { "dark" -> true; "light" -> false; else -> sysDark }
    val base = if (def.id == "dynamic" && dyn != null) dyn else def.colors
    val colors = if (def.forceDark == null) base.map { if (dark) lerp(it, Color.Black, 0.45f) else lerp(it, Color.White, 0.55f) } else base
    val accent = when {
        s.accent != 0 -> Color(s.accent)
        def.id == "dynamic" && dyn != null -> lerp(dyn[0], Color.White, 0.5f)
        else -> if (dark) def.accent else lerp(def.accent, Color.Black, 0.35f)
    }
    val text = if (dark) Color.White else Color(0xFF1B1B2F)
    val bg = colors.first()
    val tint = if (dark) Color.White else Color.Black
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = readableOn(accent), secondary = accent, onSecondary = readableOn(accent),
        primaryContainer = lerp(bg, accent, 0.35f), onPrimaryContainer = text,
        background = bg, onBackground = text,
        surface = lerp(bg, tint, 0.08f), onSurface = text,
        surfaceVariant = lerp(bg, tint, 0.14f), onSurfaceVariant = text.copy(alpha = 0.75f),
        surfaceContainerHigh = lerp(bg, tint, 0.12f), surfaceContainer = lerp(bg, tint, 0.10f),
        outline = text.copy(alpha = 0.35f)
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalIsDark provides dark, LocalAccent provides accent, LocalText provides text,
            LocalThemeColors provides colors, LocalContentColor provides text
        ) { content(colors) }
    }
}

@Composable
fun GlassBackground(colors: List<Color>, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "blobs")
    val p1 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "p1")
    val p2 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Reverse), label = "p2")
    val dark = LocalIsDark.current
    val glow = if (dark) 0.35f else 0.55f
    val c1 = colors.getOrElse(1) { colors[0] }
    val c2 = colors.getOrElse(2) { colors[0] }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors))) {
        // Each blob is recorded once (draw block never reads animation state);
        // only the layer transform animates, which is very cheap compared to re-drawing every frame.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = (p1.value - 0.5f) * 0.6f * size.width
                    translationY = (p2.value - 0.5f) * 0.25f * size.height
                }
                .drawBehind {
                    val ctr = Offset(size.width * 0.45f, size.height * 0.225f)
                    val r = size.width * 0.75f
                    drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = glow), Color.Transparent), center = ctr, radius = r), radius = r, center = ctr)
                }
        )
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = -(p2.value - 0.5f) * 0.5f * size.width
                    translationY = (p1.value - 0.5f) * 0.2f * size.height
                }
                .drawBehind {
                    val ctr = Offset(size.width * 0.6f, size.height * 0.75f)
                    val r = size.width * 0.85f
                    drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = glow), Color.Transparent), center = ctr, radius = r), radius = r, center = ctr)
                }
        )
        content()
    }
}
