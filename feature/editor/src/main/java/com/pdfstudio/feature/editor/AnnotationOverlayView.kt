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

    var editMode: EditorMode = EditorMode.READ
        set(value) {
            field = value
            isEnabled = value != EditorMode.READ
        }

    var annotations: List<PdfAnnotation> = emptyList()
        set(value) {
            val prevInkCount = field.count { it.type == AnnotationType.INK }
            val newInkCount = value.count { it.type == AnnotationType.INK }
            val prevStampCount = field.count { it.type == AnnotationType.STAMP }
            val newStampCount = value.count { it.type == AnnotationType.STAMP }
            val prevSize = field.size
            field = value
            if (newInkCount >= prevInkCount) {
                inkStrokes.clear()
            }
            if (newStampCount >= prevStampCount || value.size >= prevSize) {
                pendingSelection = null
            }
            invalidate()
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
        invalidate()
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
        val path = Path()
        path.moveTo(stroke[0].first, stroke[0].second)
        stroke.drop(1).forEach { (x, y) -> path.lineTo(x, y) }
        canvas.drawPath(path, inkPaint)
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
        beginDrawingLayer()
        isSelecting = true
        selectionStartX = x
        selectionStartY = y
        selectionEndX = x
        selectionEndY = y
    }

    private fun beginInkStroke(x: Float, y: Float) {
        beginDrawingLayer()
        currentStroke.clear()
        currentStroke.add(x to y)
    }

    private fun beginDrawingLayer() {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun endDrawingLayer() {
        setLayerType(LAYER_TYPE_NONE, null)
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
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawingGesture) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        if (dy > dx * 1.2f) {
                            return false
                        }
                        isDrawingGesture = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        when (editMode) {
                            EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> beginSelection(downX, downY)
                            EditorMode.INK -> beginInkStroke(downX, downY)
                            else -> return false
                        }
                    } else {
                        return true
                    }
                }
                when (editMode) {
                    EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> {
                        if (isSelecting) {
                            selectionEndX = event.x
                            selectionEndY = event.y
                        }
                    }
                    EditorMode.INK -> {
                        if (currentStroke.isNotEmpty()) {
                            currentStroke.add(event.x to event.y)
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                endDrawingLayer()
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isSelecting = false
                    isDrawingGesture = false
                    currentStroke.clear()
                    invalidate()
                    return true
                }
                if (!isDrawingGesture) {
                    when (editMode) {
                        EditorMode.HIGHLIGHT, EditorMode.UNDERLINE -> beginSelection(downX, downY)
                        EditorMode.INK -> beginInkStroke(downX, downY)
                        else -> return false
                    }
                    isDrawingGesture = true
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
                        invalidate()
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
                        invalidate()
                    }
                    else -> {}
                }
                isDrawingGesture = false
            }
        }
        invalidate()
        return true
    }
}
