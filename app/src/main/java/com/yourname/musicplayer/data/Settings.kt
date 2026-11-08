package com.yourname.musicplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object K {
    val THEME = stringPreferencesKey("theme")
    val PLAYER_STYLE = stringPreferencesKey("player_style")
    val DARK = stringPreferencesKey("dark_mode")
    val ACCENT = intPreferencesKey("accent")
    val LANG = stringPreferencesKey("language")
    val HAPTIC = booleanPreferencesKey("haptic")
    val HIDE_SHORT = booleanPreferencesKey("hide_short")
    val MIN_SEC = intPreferencesKey("min_sec")
    val EXCLUDED = stringSetPreferencesKey("excluded")
    val INCLUDED = stringSetPreferencesKey("included")
    val PLAY_MODE = stringPreferencesKey("play_mode")
    val CROSSFADE = intPreferencesKey("crossfade")
    val SPEED = floatPreferencesKey("speed")
    val PITCH = floatPreferencesKey("pitch")
    val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
    val BOOST = intPreferencesKey("boost")
    val EQ_ON = booleanPreferencesKey("eq_on")
    val EQ_PRESET = stringPreferencesKey("eq_preset")
    val EQ_BANDS = stringPreferencesKey("eq_bands")
    val BASS = intPreferencesKey("bass")
    val VIRT = intPreferencesKey("virt")
    val NORMALIZE = booleanPreferencesKey("normalize")
    val KEEP_PLAYING = booleanPreferencesKey("keep_playing")
    val VISUALIZER = booleanPreferencesKey("visualizer")
    val VIS_TYPE = stringPreferencesKey("vis_type")
    val LYRIC_SIZE = intPreferencesKey("lyric_size")
    val SLEEP_LAST = intPreferencesKey("sleep_last")
    val SHUFFLE = booleanPreferencesKey("shuffle")
    val REPEAT = intPreferencesKey("repeat")
    val LAST_QUEUE = stringPreferencesKey("last_queue")
    val LAST_INDEX = intPreferencesKey("last_index")
    val LAST_POS = longPreferencesKey("last_pos")
    val CUSTOM_PRESETS = stringPreferencesKey("custom_presets")
    val ONBOARDED = booleanPreferencesKey("onboarded")

    fun sort(list: String) = stringPreferencesKey("sort_$list")
    fun grid(list: String) = booleanPreferencesKey("grid_$list")
}

data class AppSettings(
    val raw: Preferences,
    val theme: String, val playerStyle: String, val darkMode: String, val accent: Int,
    val language: String, val haptic: Boolean,
    val hideShort: Boolean, val minSec: Int, val excluded: Set<String>, val included: Set<String>,
    val playMode: String, val crossfade: Int, val speed: Float, val pitch: Float, val skipSilence: Boolean,
    val boost: Int, val eqOn: Boolean, val eqPreset: String, val eqBands: String,
    val bass: Int, val virt: Int, val normalize: Boolean, val keepPlaying: Boolean,
    val visualizer: Boolean, val visType: String, val lyricSize: Int, val sleepLast: Int,
    val shuffle: Boolean, val repeat: Int,
    val lastQueue: String, val lastIndex: Int, val lastPos: Long,
    val customPresets: String, val onboarded: Boolean
) {
    fun sortOf(list: String): SortOrder = SortOrder.from(raw[K.sort(list)])
    fun gridOf(list: String): Boolean = raw[K.grid(list)] ?: false
    fun eqBandsList(): List<Int> = eqBands.split(",").mapNotNull { it.trim().toIntOrNull() }

    companion object {
        val DEFAULT: AppSettings = emptyPreferences().toSettings()
    }
}

fun Preferences.toSettings(): AppSettings = AppSettings(
    raw = this,
    theme = this[K.THEME] ?: "aurora",
    playerStyle = this[K.PLAYER_STYLE] ?: "classic",
    darkMode = this[K.DARK] ?: "system",
    accent = this[K.ACCENT] ?: 0,
    language = this[K.LANG] ?: "system",
    haptic = this[K.HAPTIC] ?: true,
    hideShort = this[K.HIDE_SHORT] ?: false,
    minSec = this[K.MIN_SEC] ?: 30,
    excluded = this[K.EXCLUDED] ?: emptySet(),
    included = this[K.INCLUDED] ?: emptySet(),
    playMode = this[K.PLAY_MODE] ?: "continuous",
    crossfade = this[K.CROSSFADE] ?: 0,
    speed = this[K.SPEED] ?: 1f,
    pitch = this[K.PITCH] ?: 1f,
    skipSilence = this[K.SKIP_SILENCE] ?: false,
    boost = this[K.BOOST] ?: 100,
    eqOn = this[K.EQ_ON] ?: false,
    eqPreset = this[K.EQ_PRESET] ?: "Normal",
    eqBands = this[K.EQ_BANDS] ?: "",
    bass = this[K.BASS] ?: 0,
    virt = this[K.VIRT] ?: 0,
    normalize = this[K.NORMALIZE] ?: false,
    keepPlaying = this[K.KEEP_PLAYING] ?: true,
    visualizer = this[K.VISUALIZER] ?: false,
    visType = this[K.VIS_TYPE] ?: "bars",
    lyricSize = this[K.LYRIC_SIZE] ?: 18,
    sleepLast = this[K.SLEEP_LAST] ?: 30,
    shuffle = this[K.SHUFFLE] ?: false,
    repeat = this[K.REPEAT] ?: 0,
    lastQueue = this[K.LAST_QUEUE] ?: "",
    lastIndex = this[K.LAST_INDEX] ?: 0,
    lastPos = this[K.LAST_POS] ?: 0L,
    customPresets = this[K.CUSTOM_PRESETS] ?: "",
    onboarded = this[K.ONBOARDED] ?: false
)

class SettingsRepo(private val ctx: Context) {
    val flow: Flow<AppSettings> = ctx.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toSettings() }

    /**
     * Flow for the UI / player service. The saved-queue keys are rewritten every few seconds
     * and on every song change; leaving them out stops the whole UI from recomposing each time
     * (this was a major source of periodic stutter).
     */
    val uiFlow: Flow<AppSettings> = ctx.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            val m = p.toMutablePreferences()
            m.remove(K.LAST_QUEUE); m.remove(K.LAST_INDEX); m.remove(K.LAST_POS)
            m.toSettings()
        }
        .distinctUntilChanged()

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        ctx.dataStore.edit { block(it) }
    }

    suspend fun snapshot(): Preferences = ctx.dataStore.data.first()
}
