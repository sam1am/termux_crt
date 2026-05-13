package com.termux.crt

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Identifier matched to a TTF asset in `assets/fonts/`, or "system" for Android's monospace. */
enum class CrtFont(val displayName: String, val assetName: String?) {
    SYSTEM("System Monospace", null),
    VT323("VT323 (Retro CRT)", "fonts/VT323-Regular.ttf"),
    PRESS_START("Press Start 2P (8-bit)", "fonts/PressStart2P-Regular.ttf"),
    SOURCE_CODE_PRO("Source Code Pro", "fonts/SourceCodePro-Regular.ttf");

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
 */
data class CrtSettings(
    val crtEnabled: Boolean,
    val fontSizeSp: Int,
    val font: CrtFont,
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

        val DEFAULT = CrtSettings(
            crtEnabled = false,
            fontSizeSp = 14,
            font = CrtFont.SYSTEM,
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

        fun reset(context: Context) {
            prefs(context).edit { clear() }
        }

        // Public so SettingsActivity can use the same canonical keys.
        fun onPrefKey(effect: String) = onKey(effect)
        fun strPrefKey(effect: String) = strKey(effect)
    }
}
