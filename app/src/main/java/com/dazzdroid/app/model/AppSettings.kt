package com.dazzdroid.app.model

import android.content.Context

class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("dazz_settings", Context.MODE_PRIVATE)

    var saveRaw: Boolean
        get() = p.getBoolean("raw", false)
        set(v) = p.edit().putBoolean("raw", v).apply()
    var saveOriginal: Boolean
        get() = p.getBoolean("orig", false)
        set(v) = p.edit().putBoolean("orig", v).apply()
    var photoMaxEdge: Int
        get() = p.getInt("maxEdge", 4096)
        set(v) = p.edit().putInt("maxEdge", v).apply()
    var videoHd: Boolean
        get() = p.getBoolean("video1080", true)
        set(v) = p.edit().putBoolean("video1080", v).apply()
    var mirrorFront: Boolean
        get() = p.getBoolean("mirror", true)
        set(v) = p.edit().putBoolean("mirror", v).apply()
    var shutterSound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()
    var showBorders: Boolean
        get() = p.getBoolean("borders", true)
        set(v) = p.edit().putBoolean("borders", v).apply()
    var showLevel: Boolean
        get() = p.getBoolean("level", false)
        set(v) = p.edit().putBoolean("level", v).apply()
    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.edit().putBoolean("grid", v).apply()
    var stamp: StampStyle
        get() = StampStyle.parse(p.getString("stamp", null)) ?: StampStyle.OFF
        set(v) = p.edit().putString("stamp", v.name).apply()
    /** Custom stamp date in epoch millis, or -1 to use the capture time. */
    var stampDate: Long
        get() = p.getLong("stampDate", -1L)
        set(v) = p.edit().putLong("stampDate", v).apply()
    var lastCamera: String
        get() = p.getString("camera", "fxnr") ?: "fxnr"
        set(v) = p.edit().putString("camera", v).apply()
}
