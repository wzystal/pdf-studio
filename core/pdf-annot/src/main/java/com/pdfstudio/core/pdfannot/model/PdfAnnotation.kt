package com.pdfstudio.core.pdfannot.model

import org.json.JSONArray
import org.json.JSONObject

enum class AnnotationType {
    HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH,
    INK,
    FREE_TEXT,
    STAMP,
}

data class PdfAnnotation(
    val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val type: AnnotationType,
    val color: Int,
    val normalizedLeft: Float,
    val normalizedTop: Float,
    val normalizedRight: Float,
    val normalizedBottom: Float,
    val text: String? = null,
    val inkPoints: List<List<Pair<Float, Float>>> = emptyList(),
    val imageBase64: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toPayload(): String {
        val json = JSONObject()
        json.put("left", normalizedLeft.toDouble())
        json.put("top", normalizedTop.toDouble())
        json.put("right", normalizedRight.toDouble())
        json.put("bottom", normalizedBottom.toDouble())
        text?.let { json.put("text", it) }
        imageBase64?.let { json.put("image", it) }
        if (inkPoints.isNotEmpty()) {
            val strokes = JSONArray()
            inkPoints.forEach { stroke ->
                val arr = JSONArray()
                stroke.forEach { (x, y) ->
                    arr.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
                }
                strokes.put(arr)
            }
            json.put("ink", strokes)
        }
        return json.toString()
    }

    companion object {
        private fun JSONObject.optNullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = optString(key)
            return value.takeIf { it.isNotEmpty() }
        }

        fun fromEntity(
            id: Long,
            documentUri: String,
            pageIndex: Int,
            type: String,
            color: Int,
            payload: String,
            createdAt: Long,
        ): PdfAnnotation {
            val json = JSONObject(payload)
            val inkPoints = mutableListOf<List<Pair<Float, Float>>>()
            if (json.has("ink")) {
                val strokes = json.getJSONArray("ink")
                for (i in 0 until strokes.length()) {
                    val strokeArr = strokes.getJSONArray(i)
                    val stroke = mutableListOf<Pair<Float, Float>>()
                    for (j in 0 until strokeArr.length()) {
                        val pt = strokeArr.getJSONArray(j)
                        stroke.add(pt.getDouble(0).toFloat() to pt.getDouble(1).toFloat())
                    }
                    inkPoints.add(stroke)
                }
            }
            return PdfAnnotation(
                id = id,
                documentUri = documentUri,
                pageIndex = pageIndex,
                type = AnnotationType.valueOf(type),
                color = color,
                normalizedLeft = json.getDouble("left").toFloat(),
                normalizedTop = json.getDouble("top").toFloat(),
                normalizedRight = json.getDouble("right").toFloat(),
                normalizedBottom = json.getDouble("bottom").toFloat(),
                text = json.optNullableString("text"),
                inkPoints = inkPoints,
                imageBase64 = json.optNullableString("image"),
                createdAt = createdAt,
            )
        }
    }
}
