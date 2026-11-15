package com.dazzdroid.app.model

enum class Aspect(val label: String, val w: Int, val h: Int) {
    R1_1("1:1", 1, 1),
    R4_5("4:5", 4, 5),
    R3_4("3:4", 3, 4),
    R2_3("2:3", 2, 3),
    R9_16("9:16", 9, 16);

    /** Portrait width / height. */
    val ratio: Float get() = w.toFloat() / h

    companion object {
        fun parse(s: String?): Aspect = entries.firstOrNull { it.label == s } ?: R3_4
    }
}

enum class Lens(val label: String, val shaderId: Int) {
    NONE("None", 0), FISHEYE("Fisheye", 1), PRISM("Prism", 2), STAR("Star", 3), ND("ND", 0)
}

enum class FrameStyle {
    NONE, FILM35, FILM120, INSTAX_SQUARE, INSTAX_MINI, POLAROID;

    companion object {
        fun parse(s: String?) = when (s) {
            "film35" -> FILM35
            "film120" -> FILM120
            "instaxsq" -> INSTAX_SQUARE
            "instaxmini" -> INSTAX_MINI
            "polaroid" -> POLAROID
            else -> NONE
        }
    }
}

enum class Special { NONE, HALF, MULTI4, WIGGLE;
    companion object {
        fun parse(s: String?) = when (s) {
            "half" -> HALF
            "multi4" -> MULTI4
            "wiggle" -> WIGGLE
            else -> NONE
        }
    }
}

enum class StampStyle(val label: String) {
    OFF("Off"), LED("LED"), DIGITAL("Digital"), VCR("VCR"), DV("DV");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name == s }
    }
}

enum class CaptureMode { PHOTO, VIDEO }

/** What the shader should render apart from the camera spec. */
data class Look(
    val spec: FilterSpec,
    val intensity: Float = 1f,
    val lens: Lens = Lens.NONE,
    val lensAmount: Float = 0.6f,
)

/** User-facing manual adjustments. */
data class Adjustments(
    val ev: Float = 0f,          // stops
    val kelvin: Int = 5500,      // 5500 = neutral
    val tint: Float = 0f,        // -1..1, + = magenta
    val iso: Int? = null,        // null = auto
    val shutterNs: Long? = null, // null = auto
    val intensity: Float = 1f,
)
