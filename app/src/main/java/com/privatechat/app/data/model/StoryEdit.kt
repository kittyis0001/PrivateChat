package com.privatechat.app.data.model

/**
 * Editor overlay data for a VIDEO story — text, stickers, freehand
 * drawing, and the chosen filter/adjustments, stored alongside the
 * story so the viewer can render the same overlay on top of playback.
 * Image stories never need this: filter/adjust/draw/text/stickers are
 * all flattened into the uploaded image itself at share time (same
 * split as the reference's storyEditorExportImage vs
 * storyEditorExportEditData).
 */
data class StoryEdit(
    val filter: String = "none",
    val brightness: Int = 100,
    val contrast: Int = 100,
    val blur: Int = 0,
    val texts: List<StoryTextOverlay> = emptyList(),
    val stickers: List<StoryStickerOverlay> = emptyList(),
    val drawStrokes: List<StoryDrawStroke> = emptyList()
) {
    constructor() : this("none", 100, 100, 0, emptyList(), emptyList(), emptyList())

    fun isEmpty(): Boolean =
        filter == "none" && brightness == 100 && contrast == 100 && blur == 0 &&
            texts.isEmpty() && stickers.isEmpty() && drawStrokes.isEmpty()
}

data class StoryTextOverlay(
    val text: String = "",
    val xPct: Float = 50f,
    val yPct: Float = 50f,
    val font: String = "sans-serif",
    val size: Float = 24f,
    val color: String = "#ffffff",
    val bg: String = "transparent",
    val anim: String = "none"
) {
    constructor() : this("", 50f, 50f, "sans-serif", 24f, "#ffffff", "transparent", "none")
}

data class StoryStickerOverlay(
    val emoji: String = "",
    val xPct: Float = 50f,
    val yPct: Float = 50f,
    val size: Float = 48f
) {
    constructor() : this("", 50f, 50f, 48f)
}

data class StoryDrawStroke(
    val color: String = "#ff3b30",
    val size: Float = 4f,
    val points: List<StoryPoint> = emptyList()
) {
    constructor() : this("#ff3b30", 4f, emptyList())
}

data class StoryPoint(
    val xPct: Float = 0f,
    val yPct: Float = 0f
) {
    constructor() : this(0f, 0f)
}
