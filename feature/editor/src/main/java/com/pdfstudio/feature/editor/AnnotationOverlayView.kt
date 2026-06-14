package com.pdfstudio.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.pdfstudio.core.pdfannot.CoordinateMapper
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfannot.model.PdfAnnotation
import kotlin.math.abs

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 235, 59)
        style = Paint.Style.FILL
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 152, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 36f
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val stampBitmapCache = mutableMapOf<String, Bitmap>()
    private val strokePath = Path()

    var editMode: EditorMode = EditorMode.READ
        set(value) {
            field = value
            isEnabled = value != EditorMode.READ
        }

    var annotations: List<PdfAnnotation> = emptyList()
        set(value) {
            val prevInkCount = field.count { it.type == AnnotationType.INK }
            val newInkCount = value.count { it.type == AnnotationType.INK }
            field = value
            if (newInkCount > prevInkCount) {
                inkStrokes.clear()
            }
            postInvalidateOnAnimation()
        }

    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionEndX = 0f
    private var selectionEndY = 0f
    private var isSelecting = false
    private var isDrawingGesture = false
    private var downX = 0f
    private var downY = 0f
    private var pendingSelection: Pair<RectF, AnnotationType>? = null

    private val currentStroke = mutableListOf<Pair<Float, Float>>()
    private val inkStrokes = mutableListOf<List<Pair<Float, Float>>>()

    var onSelectionFinished: ((RectF, AnnotationType) -> Unit)? = null
    var onInkFinished: ((List<List<Pair<Float, Float>>>) -> Unit)? = null
    var onTapForNote: ((Float, Float) -> Unit)? = null

    fun clearInkDraft() {
        inkStrokes.clear()
        currentStroke.clear()
        pendingSelection = null
        postInvalidateOnAnimation()
    }

    fun resetTransientState() {
        isSelecting = false
        isDrawingGesture = false
        currentStroke.clear()
        inkStrokes.clear()
        pendingSelection = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        annotations.forEach { drawAnnotation(canvas, it) }
        pendingSelection?.let { (rect, type) -> drawSelectionPreview(canvas, rect, type) }
        if (isSelecting) {
            val rect = CoordinateMapper.rectFromPoints(
                selectionStartX, selectionStartY, selectionEndX, selectionEndY,
                width, height,
            )
            val type = if (editMode == EditorMode.UNDERLINE) {
                AnnotationType.UNDERLINE
            } else {
                AnnotationType.HIGHLIGHT
            }
            drawSelectionPreview(canvas, rect, type)
        }
        inkStrokes.forEach { stroke -> drawStroke(canvas, stroke) }
        if (currentStroke.isNotEmpty()) {
            drawStroke(canvas, currentStroke)
        }
    }

    private fun drawSelectionPreview(canvas: Canvas, rect: RectF, type: AnnotationType) {
        when (type) {
            AnnotationType.UNDERLINE -> {
                canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, underlinePaint)
            }
            else -> canvas.drawRect(rect, highlightPaint)
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: PdfAnnotation) {
        when (annotation.type) {
            AnnotationType.HIGHLIGHT -> {
                val rect = deviceRect(annotation)
                highlightPaint.color = annotation.color
                canvas.drawRect(rect, highlightPaint)
            }
            AnnotationType.UNDERLINE, AnnotationType.STRIKETHROUGH -> {
                val rect = deviceRect(annotation)
                inkPaint.color = annotation.color
                val y = if (annotation.type == AnnotationType.UNDERLINE) rect.bottom else rect.centerY()
                canvas.drawLine(rect.left, y, rect.right, y, inkPaint)
            }
            AnnotationType.INK -> {
                inkPaint.color = annotation.color
                annotation.inkPoints.forEach { drawNormalizedStroke(canvas, it) }
            }
            AnnotationType.FREE_TEXT -> {
                val rect = deviceRect(annotation)
                annotation.text?.let { canvas.drawText(it, rect.left, rect.bottom, textPaint) }
            }
            AnnotationType.STAMP -> drawStamp(canvas, annotation)
        }
    }

    private fun deviceRect(annotation: PdfAnnotation): RectF {
        return CoordinateMapper.normalizedRectToDevice(
            annotation.normalizedLeft,
            annotation.normalizedTop,
            annotation.normalizedRight,
            annotation.normalizedBottom,
            width, height,
        )
    }

    private fun drawStamp(canvas: Canvas, annotation: PdfAnnotation) {
        val rect = deviceRect(annotation)
        val base64 = annotation.imageBase64
        if (base64 != null) {
            decodeStampBitmap(base64)?.let { bitmap ->
                canvas.drawBitmap(bitmap, null, rect, null)
                return
            }
        }
        canvas.drawRect(rect, highlightPaint.apply { color = Color.argb(60, 0, 150, 136) })
        canvas.drawText("签名", rect.left, rect.centerY(), textPaint)
    }

    private fun decodeStampBitmap(base64: String): Bitmap? {
        stampBitmapCache[base64]?.let { cached ->
            if (!cached.isRecycled) return cached
            stampBitmapCache.remove(base64)
        }
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                stampBitmapCache[base64] = it
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: List<Pair<Float, Float>>) {
        if (stroke.size < 2) return
        strokePath.rewind()
        strokePath.moveTo(stroke[0].first, stroke[0].second)
        for (i in 1 until stroke.size) {
            strokePath.lineTo(stroke[i].first, stroke[i].second)
        }
        canvas.drawPath(strokePath, inkPaint)
    }

    private fun drawNormalizedStroke(canvas: Canvas, stroke: List<Pair<Float, Float>>) {
        val deviceStroke = stroke.map { (x, y) -> x * width to y * height }
        drawStroke(canvas, deviceStroke)
    }

    private fun selectionAnnotationType(): AnnotationType {
        return if (editMode == EditorMode.UNDERLINE) {
            AnnotationType.UNDERLINE
        } else {
            AnnotationType.HIGHLIGHT
        }
    }

    private fun beginSelection(x: Float, y: Float) {
        isSelecting = true
        selectionStartX = x
        selectionStartY = y
        selectionEndX = x
        selectionEndY = y
    }

    private fun beginInkStroke(x: Float, y: Float) {
        currentStroke.clear()
        currentStroke.add(x to y)
    }

    private fun isDrawingMode(): Boolean {
        return editMode == EditorMode.HIGHLIGHT ||
            editMode == EditorMode.UNDERLINE ||
            editMode == EditorMode.INK
    }

    private fun requestParentDisallowIntercept(disallow: Boolean) {
        var parent = parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode == EditorMode.READ) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDrawingGesture = false
                when (editMode) {
                    EditorMode.NOTE -> {
                        onTapForNote?.invoke(event.x, event.y)
                        return true
                    }
                    EditorMode.STAMP -> return false
                    EditorMode.INK -> {
                        requestParentDisallowIntercept(true)
                        beginInkStroke(event.x, event.y)
                        isDrawingGesture = true
                    }
                    EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> {
                        requestParentDisallowIntercept(true)
                        beginSelection(event.x, event.y)
                        isDrawingGesture = true
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (editMode) {
                    EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> {
                        if (isSelecting) {
                            selectionEndX = event.x
                            selectionEndY = event.y
                        }
                    }
                    EditorMode.INK -> {
                        if (isDrawingGesture) {
                            currentStroke.add(event.x to event.y)
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestParentDisallowIntercept(false)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isSelecting = false
                    isDrawingGesture = false
                    currentStroke.clear()
                    postInvalidateOnAnimation()
                    return true
                }
                when (editMode) {
                    EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> {
                        isSelecting = false
                        val type = selectionAnnotationType()
                        val norm = CoordinateMapper.normalizedRect(
                            selectionStartX, selectionStartY, selectionEndX, selectionEndY,
                            width, height,
                        )
                        val deviceRect = CoordinateMapper.rectFromPoints(
                            selectionStartX, selectionStartY, selectionEndX, selectionEndY,
                            width, height,
                        )
                        if (deviceRect.width() > touchSlop || deviceRect.height() > touchSlop) {
                            pendingSelection = deviceRect to type
                            onSelectionFinished?.invoke(norm, type)
                        }
                    }
                    EditorMode.INK -> {
                        if (currentStroke.size >= 2) {
                            val deviceStroke = currentStroke.toList()
                            inkStrokes.add(deviceStroke)
                            val normalized = deviceStroke.map { (x, y) ->
                                CoordinateMapper.deviceToNormalized(x, y, width, height)
                            }
                            onInkFinished?.invoke(listOf(normalized))
                        }
                        currentStroke.clear()
                    }
                    else -> {}
                }
                isDrawingGesture = false
            }
        }
        if (isDrawingMode()) {
            postInvalidateOnAnimation()
        }
        return true
    }
}
