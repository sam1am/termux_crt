package com.termux.crt

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.edit

/**
 * A user-selectable font.
 *
 * If [assetName] is set, the typeface is loaded from `assets/`. Otherwise
 * [systemFamily] + [systemStyle] are passed to `Typeface.create(...)` —
 * lets us expose Android's built-in monospace variants without bundling
 * extra TTFs.
 */
enum class CrtFont(
    val displayName: String,
    val assetName: String? = null,
    val systemFamily: String? = null,
    val systemStyle: Int = Typeface.NORMAL,
) {
    SYSTEM("System Monospace"),
    SYSTEM_MONO_BOLD("System Mono Bold", systemFamily = "monospace", systemStyle = Typeface.BOLD),
    SYSTEM_SERIF_MONO("System Serif Mono", systemFamily = "serif-monospace"),
    VT323("VT323 (Retro CRT)", assetName = "fonts/VT323-Regular.ttf"),
    PRESS_START("Press Start 2P (8-bit)", assetName = "fonts/PressStart2P-Regular.ttf"),
    SOURCE_CODE_PRO("Source Code Pro", assetName = "fonts/SourceCodePro-Regular.ttf"),
    FIRA_CODE("Fira Code", assetName = "fonts/FiraCode-Regular.ttf"),
    JETBRAINS_MONO("JetBrains Mono", assetName = "fonts/JetBrainsMono-Regular.ttf"),
    IBM_PLEX_MONO("IBM Plex Mono", assetName = "fonts/IBMPlexMono-Regular.ttf");

    companion object {
        fun fromKey(key: String?): CrtFont = entries.find { it.name == key } ?: SYSTEM
    }
}

/**
 * Immutable snapshot of every user-tunable CRT shader effect.
 *
 * Effect set follows cool-retro-term's vocabulary: each effect has an enable
 * flag and a 0..1 intensity. The renderer pushes the same shape to GL
 * uniforms named `u<Effect>On` / `u<Effect>Strength`.
 *
 * Colors are stored as packed ARGB ints (the alpha byte is ignored at the
 * GL layer — we always pass opaque RGB). Both color overrides are gated by
 * an enable flag so the user can keep their picked color around without
 * applying it.
 */
data class CrtSettings(
    val crtEnabled: Boolean,
    val fontSizeSp: Int,
    val font: CrtFont,
    val bgColorOverride: Boolean,
    val bgColor: Int,
    val textColorOverride: Boolean,
    val textColor: Int,
    val textColorMix: Float,

    val bloom: Effect,
    val burnin: Effect,
    val staticNoise: Effect,
    val jitter: Effect,
    val glowLine: Effect,
    val curvature: Effect,
    val ambient: Effect,
    val flicker: Effect,
    val hsync: Effect,
    val rgbShift: Effect,
) {
    data class Effect(val enabled: Boolean, val strength: Float)

    companion object {
        const val PREFS = "crt_settings"

        // Key helpers — keeps the SharedPreferences keyspace consistent with the
        // GL uniform names so it's obvious which pref drives which effect.
        private fun onKey(effect: String) = "fx_${effect}_on"
        private fun strKey(effect: String) = "fx_${effect}_str"

        const val KEY_CRT_ENABLED = "crt_enabled"
        const val KEY_FONT_SIZE_SP = "font_size_sp"
        const val KEY_FONT = "font"
        const val KEY_BG_COLOR_ON = "bg_color_on"
        const val KEY_BG_COLOR = "bg_color"
        const val KEY_TEXT_COLOR_ON = "text_color_on"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_TEXT_COLOR_MIX = "text_color_mix"

        val DEFAULT = CrtSettings(
            crtEnabled = false,
            fontSizeSp = 14,
            font = CrtFont.SYSTEM,
            bgColorOverride = false,
            bgColor = Color.BLACK,
            textColorOverride = false,
            textColor = Color.WHITE,
            textColorMix = 1.0f,
            bloom       = Effect(true,  0.5f),
            burnin      = Effect(true,  0.5f),
            staticNoise = Effect(false, 0.2f),
            jitter      = Effect(false, 0.3f),
            glowLine    = Effect(false, 0.5f),
            curvature   = Effect(true,  0.4f),
            ambient     = Effect(true,  0.3f),
            flicker     = Effect(true,  0.3f),
            hsync       = Effect(false, 0.3f),
            rgbShift    = Effect(true,  0.3f),
        )

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): CrtSettings {
            val p = prefs(context)
            fun fx(key: String, def: Effect) = Effect(
                p.getBoolean(onKey(key), def.enabled),
                p.getFloat(strKey(key), def.strength),
            )
            return CrtSettings(
                crtEnabled = p.getBoolean(KEY_CRT_ENABLED, DEFAULT.crtEnabled),
                fontSizeSp = p.getInt(KEY_FONT_SIZE_SP, DEFAULT.fontSizeSp),
                font = CrtFont.fromKey(p.getString(KEY_FONT, DEFAULT.font.name)),
                bgColorOverride = p.getBoolean(KEY_BG_COLOR_ON, DEFAULT.bgColorOverride),
                bgColor = p.getInt(KEY_BG_COLOR, DEFAULT.bgColor),
                textColorOverride = p.getBoolean(KEY_TEXT_COLOR_ON, DEFAULT.textColorOverride),
                textColor = p.getInt(KEY_TEXT_COLOR, DEFAULT.textColor),
                textColorMix = p.getFloat(KEY_TEXT_COLOR_MIX, DEFAULT.textColorMix),
                bloom       = fx("bloom",       DEFAULT.bloom),
                burnin      = fx("burnin",      DEFAULT.burnin),
                staticNoise = fx("static",      DEFAULT.staticNoise),
                jitter      = fx("jitter",      DEFAULT.jitter),
                glowLine    = fx("glowline",    DEFAULT.glowLine),
                curvature   = fx("curvature",   DEFAULT.curvature),
                ambient     = fx("ambient",     DEFAULT.ambient),
                flicker     = fx("flicker",     DEFAULT.flicker),
                hsync       = fx("hsync",       DEFAULT.hsync),
                rgbShift    = fx("rgbshift",    DEFAULT.rgbShift),
            )
        }

        fun saveEffect(context: Context, key: String, effect: Effect) {
            prefs(context).edit {
                putBoolean(onKey(key), effect.enabled)
                putFloat(strKey(key), effect.strength)
            }
        }

        /**
         * Write the whole [CrtSettings] snapshot to SharedPreferences in one
         * atomic edit. Used when applying a saved profile so that partial
         * writes can't leave the UI in a mixed state.
         */
        fun saveAll(context: Context, s: CrtSettings) {
            prefs(context).edit {
                putBoolean(KEY_CRT_ENABLED, s.crtEnabled)
                putInt(KEY_FONT_SIZE_SP, s.fontSizeSp)
                putString(KEY_FONT, s.font.name)
                putBoolean(KEY_BG_COLOR_ON, s.bgColorOverride)
                putInt(KEY_BG_COLOR, s.bgColor)
                putBoolean(KEY_TEXT_COLOR_ON, s.textColorOverride)
                putInt(KEY_TEXT_COLOR, s.textColor)
                putFloat(KEY_TEXT_COLOR_MIX, s.textColorMix)
                fun writeFx(key: String, e: Effect) {
                    putBoolean(onKey(key), e.enabled)
                    putFloat(strKey(key), e.strength)
                }
                writeFx("bloom",     s.bloom)
                writeFx("burnin",    s.burnin)
                writeFx("static",    s.staticNoise)
                writeFx("jitter",    s.jitter)
                writeFx("glowline",  s.glowLine)
                writeFx("curvature", s.curvature)
                writeFx("ambient",   s.ambient)
                writeFx("flicker",   s.flicker)
                writeFx("hsync",     s.hsync)
                writeFx("rgbshift",  s.rgbShift)
            }
        }

        fun reset(context: Context) {
            prefs(context).edit { clear() }
        }

        // Public so SettingsActivity can use the same canonical keys.
        fun onPrefKey(effect: String) = onKey(effect)
        fun strPrefKey(effect: String) = strKey(effect)
    }
}
