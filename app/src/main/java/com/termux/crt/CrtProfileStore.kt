package com.termux.crt

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Persists named [CrtProfile]s in a dedicated SharedPreferences file.
 *
 * Layout: one key per profile (`profile:<name>` → JSON). Listing is just a
 * `prefs.all` walk filtered to keys with the prefix, which keeps writes
 * atomic per-profile and saves us from juggling an index.
 */
object CrtProfileStore {
    private const val PREFS = "crt_profiles"
    private const val KEY_PREFIX = "profile:"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<CrtProfile> {
        val out = mutableListOf<CrtProfile>()
        for ((k, v) in prefs(ctx).all) {
            if (!k.startsWith(KEY_PREFIX) || v !is String) continue
            try {
                out += CrtProfile.fromJson(JSONObject(v))
            } catch (_: Throwable) {
                // Skip corrupt entries rather than crashing the settings screen.
            }
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    fun save(ctx: Context, profile: CrtProfile) {
        prefs(ctx).edit {
            putString(KEY_PREFIX + profile.name, profile.toJson().toString())
        }
    }

    fun delete(ctx: Context, name: String) {
        prefs(ctx).edit { remove(KEY_PREFIX + name) }
    }

    fun exists(ctx: Context, name: String): Boolean =
        prefs(ctx).contains(KEY_PREFIX + name)
}
