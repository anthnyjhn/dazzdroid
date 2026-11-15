package com.dazzdroid.app.model

import org.json.JSONObject

/**
 * Parameters for the film engine shader. Every key maps to a shader uniform named
 * "u" + Key (e.g. "grain" -> uGrain). Values of size 1 are floats, 3 are vec3 and 9 a
 * row-major 3x3 color mixing matrix.
 */
class FilterSpec(val values: Map<String, FloatArray>) {

    operator fun get(key: String): FloatArray = values[key] ?: DEFAULTS.getValue(key)
    fun f(key: String): Float = get(key)[0]

    fun edit(block: MutableMap<String, FloatArray>.() -> Unit): FilterSpec {
        val m = LinkedHashMap<String, FloatArray>()
        values.forEach { (k, v) -> m[k] = v.copyOf() }
        m.block()
        return FilterSpec(m)
    }

    companion object {
        val DEFAULTS: Map<String, FloatArray> = linkedMapOf(
            "exposure" to f(0f), "contrast" to f(1f), "saturation" to f(1f),
            "temp" to f(0f), "tint" to f(0f), "scurve" to f(0f),
            "gamma" to f(1f, 1f, 1f), "black" to f(0f, 0f, 0f), "white" to f(1f, 1f, 1f),
            "mix" to f(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            "shadowTint" to f(0f, 0f, 0f), "highTint" to f(0f, 0f, 0f),
            "mono" to f(0f), "monoMix" to f(0.299f, 0.587f, 0.114f), "monoTone" to f(1f, 1f, 1f),
            "grain" to f(0f), "grainSize" to f(1.5f), "vignette" to f(0f), "vignetteSoft" to f(0.5f),
            "halation" to f(0f), "bloom" to f(0f), "soft" to f(0f), "sharpen" to f(0f),
            "ca" to f(0f), "noise" to f(0f), "leak" to f(0f), "leakColor" to f(1f, 0.45f, 0.15f),
            "leakSeed" to f(3f), "vhs" to f(0f), "lowRes" to f(320f), "scan" to f(0f),
            "jitter" to f(0f), "dust" to f(0f), "flicker" to f(0f), "gate" to f(0f), "flash" to f(0f),
        )

        val NEUTRAL = FilterSpec(DEFAULTS)

        private fun f(vararg v: Float) = v

        fun fromJson(o: JSONObject?): FilterSpec {
            val m = LinkedHashMap<String, FloatArray>()
            DEFAULTS.forEach { (k, v) -> m[k] = v.copyOf() }
            if (o != null) {
                for (key in o.keys()) {
                    val def = DEFAULTS[key] ?: continue
                    val arr = o.optJSONArray(key)
                    m[key] = if (arr != null) {
                        FloatArray(def.size) { i -> arr.optDouble(i, def[i].toDouble()).toFloat() }
                    } else {
                        FloatArray(def.size) { o.getDouble(key).toFloat() }
                    }
                }
            }
            return FilterSpec(m)
        }
    }
}
