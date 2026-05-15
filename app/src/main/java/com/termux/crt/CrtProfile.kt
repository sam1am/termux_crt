package com.termux.crt

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named snapshot of CRT settings. Stored as JSON for both on-device
 * persistence ([CrtProfileStore]) and for export/import to a portable file.
 *
 * The JSON shape is stable: bumping [VERSION] lets future loaders branch on
 * older payloads if the schema ever changes.
 */
data class CrtProfile(
    val name: String,
    val settings: CrtSettings,
) {
    fun toJson(): JSONObject = profileToJson(this)

    companion object {
        const val VERSION = 1

        fun fromJson(obj: JSONObject): CrtProfile = profileFromJson(obj)

        /** Bundle one or more profiles into a single export document. */
        fun bundleToJson(profiles: List<CrtProfile>): JSONObject {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            return JSONObject().apply {
                put("version", VERSION)
                put("profiles", arr)
            }
        }

        /**
         * Parse an import document. Accepts either a single-profile object,
         * a bundle `{ "profiles": [...] }`, or a bare array of profiles.
         */
        fun bundleFromJson(obj: JSONObject): List<CrtProfile> {
            obj.optJSONArray("profiles")?.let { return arrayToList(it) }
            // Single profile (no wrapper).
            if (obj.has("name")) return listOf(fromJson(obj))
            throw IllegalArgumentException("Not a CRT profile document")
        }

        fun bundleFromJson(arr: JSONArray): List<CrtProfile> = arrayToList(arr)

        private fun arrayToList(arr: JSONArray): List<CrtProfile> =
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

private fun profileToJson(p: CrtProfile): JSONObject {
    val s = p.settings
    return JSONObject().apply {
        put("version", CrtProfile.VERSION)
        put("name", p.name)
        put("crtEnabled", s.crtEnabled)
        put("fontSizeSp", s.fontSizeSp)
        put("font", s.font.name)
        put("bgColorOverride", s.bgColorOverride)
        put("bgColor", s.bgColor)
        put("textColorOverride", s.textColorOverride)
        put("textColor", s.textColor)
        put("bloom", effectJson(s.bloom))
        put("burnin", effectJson(s.burnin))
        put("staticNoise", effectJson(s.staticNoise))
        put("jitter", effectJson(s.jitter))
        put("glowLine", effectJson(s.glowLine))
        put("curvature", effectJson(s.curvature))
        put("ambient", effectJson(s.ambient))
        put("flicker", effectJson(s.flicker))
        put("hsync", effectJson(s.hsync))
        put("rgbShift", effectJson(s.rgbShift))
    }
}

private fun profileFromJson(obj: JSONObject): CrtProfile {
    val d = CrtSettings.DEFAULT
    val name = obj.optString("name").ifBlank { "Imported Profile" }
    val settings = CrtSettings(
        crtEnabled = obj.optBoolean("crtEnabled", d.crtEnabled),
        fontSizeSp = obj.optInt("fontSizeSp", d.fontSizeSp),
        font = CrtFont.fromKey(obj.optString("font", d.font.name)),
        bgColorOverride = obj.optBoolean("bgColorOverride", d.bgColorOverride),
        bgColor = obj.optInt("bgColor", d.bgColor),
        textColorOverride = obj.optBoolean("textColorOverride", d.textColorOverride),
        textColor = obj.optInt("textColor", d.textColor),
        bloom = effectFromJson(obj.optJSONObject("bloom"), d.bloom),
        burnin = effectFromJson(obj.optJSONObject("burnin"), d.burnin),
        staticNoise = effectFromJson(obj.optJSONObject("staticNoise"), d.staticNoise),
        jitter = effectFromJson(obj.optJSONObject("jitter"), d.jitter),
        glowLine = effectFromJson(obj.optJSONObject("glowLine"), d.glowLine),
        curvature = effectFromJson(obj.optJSONObject("curvature"), d.curvature),
        ambient = effectFromJson(obj.optJSONObject("ambient"), d.ambient),
        flicker = effectFromJson(obj.optJSONObject("flicker"), d.flicker),
        hsync = effectFromJson(obj.optJSONObject("hsync"), d.hsync),
        rgbShift = effectFromJson(obj.optJSONObject("rgbShift"), d.rgbShift),
    )
    return CrtProfile(name, settings)
}

private fun effectJson(e: CrtSettings.Effect): JSONObject = JSONObject().apply {
    put("enabled", e.enabled)
    put("strength", e.strength.toDouble())
}

private fun effectFromJson(obj: JSONObject?, default: CrtSettings.Effect): CrtSettings.Effect {
    if (obj == null) return default
    return CrtSettings.Effect(
        enabled = obj.optBoolean("enabled", default.enabled),
        strength = obj.optDouble("strength", default.strength.toDouble()).toFloat(),
    )
}
