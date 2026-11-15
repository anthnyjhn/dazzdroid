package com.dazzdroid.app.model

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

data class Variant(val name: String, val spec: FilterSpec)

data class CameraModel(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val color: Int,
    val aspect: Aspect,
    val frame: FrameStyle,
    val special: Special,
    val isVideoCamera: Boolean,
    val defaultStamp: StampStyle?,
    val variants: List<Variant>,
) {
    val supportsVideo: Boolean get() = special == Special.NONE
}

object CameraCatalog {
    @Volatile private var cached: List<CameraModel>? = null

    fun load(context: Context): List<CameraModel> {
        cached?.let { return it }
        val text = context.assets.open("cameras.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cameras")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val vs = o.getJSONArray("variants")
            CameraModel(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category"),
                description = o.optString("desc"),
                color = runCatching { Color.parseColor(o.optString("color", "#444444")) }.getOrDefault(Color.DKGRAY),
                aspect = Aspect.parse(o.optString("aspect")),
                frame = FrameStyle.parse(o.optString("frame")),
                special = Special.parse(o.optString("special")),
                isVideoCamera = o.optString("category") == "Video",
                defaultStamp = StampStyle.parse(o.optString("stamp").uppercase()),
                variants = (0 until vs.length()).map { j ->
                    val v = vs.getJSONObject(j)
                    Variant(v.getString("name"), FilterSpec.fromJson(v.optJSONObject("spec")))
                },
            )
        }
        cached = list
        return list
    }
}
