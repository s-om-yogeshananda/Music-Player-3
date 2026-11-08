package com.yourname.musicplayer.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.yourname.musicplayer.AppContainer
import com.yourname.musicplayer.FxInfo
import com.yourname.musicplayer.data.AppSettings
import kotlin.math.max
import kotlin.math.min

/** Built-in EQ preset curves (dB at 5 reference points: bass .. treble). Mapped to any band count. */
val EQ_PRESET_CURVES: Map<String, List<Float>> = linkedMapOf(
    "Normal" to listOf(0f, 0f, 0f, 0f, 0f),
    "Pop" to listOf(-1.5f, 3f, 4f, 2f, -1f),
    "Rock" to listOf(5f, 3f, -2f, 3f, 5f),
    "Jazz" to listOf(4f, 2f, -2f, 2f, 4f),
    "Classical" to listOf(4f, 3f, -1f, 3f, 4f),
    "Hip-Hop" to listOf(5f, 4f, 1f, -1f, 2f),
    "Bass Heavy" to listOf(7f, 5f, 1f, 0f, 0f),
    "Treble Boost" to listOf(0f, 0f, 1f, 4f, 7f),
    "Vocal" to listOf(-2f, 0f, 4f, 3f, 1f),
    "Electronic" to listOf(5f, 3f, -1f, 2f, 5f)
)

/** Returns band levels in millibels for [bands] bands, clamped to [lo]..[hi]. */
fun presetBands(name: String, bands: Int, lo: Int, hi: Int): List<Int> {
    val curve = EQ_PRESET_CURVES[name] ?: EQ_PRESET_CURVES.getValue("Normal")
    return List(bands) { i ->
        val t = if (bands <= 1) 0f else i.toFloat() / (bands - 1) * (curve.size - 1)
        val a = t.toInt().coerceIn(0, curve.size - 1)
        val b = min(a + 1, curve.size - 1)
        val f = t - a
        val db = curve[a] * (1 - f) + curve[b] * f
        (db * 100).toInt().coerceIn(lo, hi)
    }
}

class AudioFx {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var minLevel = 0
    private var maxLevel = 0

    fun attach(session: Int, c: AppContainer, s: AppSettings) {
        release()
        if (session == 0) return
        try {
            val e = Equalizer(0, session)
            eq = e
            val range = e.bandLevelRange
            minLevel = range[0].toInt(); maxLevel = range[1].toInt()
            val n = e.numberOfBands.toInt()
            c.fxInfo.value = FxInfo(n, minLevel, maxLevel, List(n) { e.getCenterFreq(it.toShort()) / 1000 })
        } catch (t: Throwable) { Log.w("AudioFx", "EQ unavailable", t) }
        try { bass = BassBoost(0, session) } catch (t: Throwable) { Log.w("AudioFx", "Bass unavailable", t) }
        try { virt = Virtualizer(0, session) } catch (t: Throwable) { Log.w("AudioFx", "Virtualizer unavailable", t) }
        try { loud = LoudnessEnhancer(session) } catch (t: Throwable) { Log.w("AudioFx", "Loudness unavailable", t) }
        apply(s)
    }

    fun apply(s: AppSettings) {
        try {
            eq?.let { e ->
                e.setEnabled(s.eqOn)
                if (s.eqOn) {
                    val bands = s.eqBandsList()
                    for (i in 0 until e.numberOfBands.toInt()) {
                        val lv = (bands.getOrNull(i) ?: 0).coerceIn(minLevel, maxLevel)
                        e.setBandLevel(i.toShort(), lv.toShort())
                    }
                }
            }
        } catch (t: Throwable) { Log.w("AudioFx", "eq apply", t) }
        try {
            bass?.let { b ->
                if (b.strengthSupported) b.setStrength(s.bass.coerceIn(0, 1000).toShort())
                b.setEnabled(s.bass > 0)
            }
        } catch (t: Throwable) { Log.w("AudioFx", "bass apply", t) }
        try {
            virt?.let { v ->
                if (v.strengthSupported) v.setStrength(s.virt.coerceIn(0, 1000).toShort())
                v.setEnabled(s.virt > 0)
            }
        } catch (t: Throwable) { Log.w("AudioFx", "virt apply", t) }
        try {
            loud?.let { l ->
                val gain = max(0, s.boost - 100) * 15 + (if (s.normalize) 300 else 0)
                l.setTargetGain(gain)
                l.setEnabled(gain > 0)
            }
        } catch (t: Throwable) { Log.w("AudioFx", "loud apply", t) }
    }

    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        try { virt?.release() } catch (_: Throwable) {}
        try { loud?.release() } catch (_: Throwable) {}
        eq = null; bass = null; virt = null; loud = null
    }
}
