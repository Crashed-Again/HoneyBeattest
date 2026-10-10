package com.neonbear.honeybeat

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class BoolPref(private val p: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(p.getBoolean(key, default))
        private set

    fun set(v: Boolean) {
        value = v
        p.edit().putBoolean(key, v).apply()
    }
}

class IntPref(private val p: SharedPreferences, private val key: String, default: Int) {
    var value by mutableIntStateOf(p.getInt(key, default))
        private set

    fun set(v: Int) {
        value = v
        p.edit().putInt(key, v).apply()
    }
}

class StrPref(private val p: SharedPreferences, private val key: String, default: String) {
    var value by mutableStateOf(p.getString(key, default) ?: default)
        private set

    fun set(v: String) {
        value = v
        p.edit().putString(key, v).apply()
    }
}

/** Every setting, saved in SharedPreferences. The services read the same keys directly. */
class Settings(p: SharedPreferences) {
    val shuffle = BoolPref(p, "shuffle", false)
    val repeat = BoolPref(p, "repeat", false)
    val art = BoolPref(p, "art", true)
    val caveArt = BoolPref(p, "cave_art", true)
    val ask = BoolPref(p, "cave_ask", true)
    val autoYt = BoolPref(p, "cave_autoupdate", true)
    val watch = BoolPref(p, "watch_on", false)
    val notify = BoolPref(p, "notify", true)
    val noPrompt = BoolPref(p, "login_prompt_off", false)
    val par = IntPref(p, "cave_par", 4)
    val every = IntPref(p, "watch_every", 5)
    val quality = StrPref(p, "cave_quality", "mp3")
    val sort = StrPref(p, "lib_sort", "title")
    /** false = A to Z / oldest first, true = Z to A / newest first. */
    val sortDesc = BoolPref(p, "lib_sort_desc", false)
    val coverAccent = BoolPref(p, "accent_from_cover", true)
}
