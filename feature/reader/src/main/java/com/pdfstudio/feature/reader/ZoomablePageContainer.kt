package com.pdfstudio.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 包裹单页 PDF 内容：放大后内容超出视口时支持平移查看。
 */
class ZoomablePageContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var contentWidthPx = 0
    private var contentHeightPx = 0
    private var translateX = 0f
    private var translateY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    var onRequestParentDisallowIntercept: ((Boolean) -> Unit)? = null

    /** 编辑模式下禁用平移，避免与框选/手绘手势冲突 */
    var panEnabled: Boolean = true

    fun setContentSize(widthPx: Int, heightPx: Int) {
        contentWidthPx = widthPx.coerceAtLeast(1)
        contentHeightPx = heightPx.coerceAtLeast(1)
        val lp = layoutParams
        if (lp != null) {
            lp.height = contentHeightPx
            layoutParams = lp
        }
        getPageContent()?.let { child ->
            val childLp = child.layoutParams
            childLp.width = contentWidthPx
            childLp.height = contentHeightPx
            child.layoutParams = childLp
        }
        clampTranslation()
        applyTranslation()
        requestLayout()
    }

    /** 以捏合焦点为中心缩放时，按比例调整平移偏移 */
    fun scaleAroundPoint(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor == 1f) return
        translateX = focusX - (focusX - translateX) * scaleFactor
        translateY = focusY - (focusY - translateY) * scaleFactor
        clampTranslation()
        applyTranslation()
    }

    fun isHorizontallyPannable(): Boolean = canPanHorizontally()

    /** 由 RecyclerView 转发的手势（坐标已映射到本容器） */
    fun handlePanMotionEvent(event: MotionEvent): Boolean {
        if (!panEnabled || !canPanHorizontally()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
                isPanning = true
                onRequestParentDisallowIntercept?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPanning) {
                    lastPanX = event.x
                    lastPanY = event.y
                    isPanning = true
                    return true
                }
                val dx = event.x - lastPanX
                translateX += dx
                lastPanX = event.x
                lastPanY = event.y
                clampTranslation()
                applyTranslation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                onRequestParentDisallowIntercept?.invoke(false)
            }
        }
        return true
    }

    fun resetPan() {
        translateX = 0f
        translateY = 0f
        isPanning = false
        applyTranslation()
    }

    private fun getPageContent(): View? = if (childCount > 0) getChildAt(0) else null

    private fun viewportWidth(): Int = width.coerceAtLeast(1)

    private fun viewportHeight(): Int = height.coerceAtLeast(1)

    private fun canPanHorizontally(): Boolean = contentWidthPx > viewportWidth() + touchSlop

    private fun canPanVertically(): Boolean = contentHeightPx > viewportHeight() + touchSlop

    private fun canPan(): Boolean = canPanHorizontally() || canPanVertically()

    private fun clampTranslation() {
        val vw = viewportWidth()
        val vh = viewportHeight()
        if (!canPanHorizontally()) {
            translateX = 0f
        } else {
            val minTx = (vw - contentWidthPx).toFloat()
            translateX = translateX.coerceIn(minTx, 0f)
        }
        if (!canPanVertically()) {
            translateY = 0f
        } else {
            val minTy = (vh - contentHeightPx).toFloat()
            translateY = translateY.coerceIn(minTy, 0f)
        }
    }

    private fun applyTranslation() {
        getPageContent()?.apply {
            translationX = translateX
            translationY = translateY
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (childCount > 0) {
            val child = getChildAt(0)
            val cw = contentWidthPx.coerceAtLeast(right - left)
            val ch = contentHeightPx.coerceAtLeast(bottom - top)
            child.layout(0, 0, cw, ch)
        }
        clampTranslation()
        applyTranslation()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panEnabled || !canPan()) return false
        if (ev.pointerCount > 1) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = ev.x
                lastPanY = ev.y
                isPanning = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastPanX
                val dy = ev.y - lastPanY
                if (!isPanning) {
                    val horizontalIntent = abs(dx) > touchSlop && abs(dx) >= abs(dy)
                    val verticalIntent = abs(dy) > touchSlop && abs(dy) > abs(dx)
                    when {
                        horizontalIntent && canPanHorizontally() -> {
                            isPanning = true
                            onRequestParentDisallowIntercept?.invoke(true)
                        }
                        verticalIntent && canPanVertically() -> {
                            isPanning = true
                            onRequestParentDisallowIntercept?.invoke(true)
                        }
                    }
                }
                if (isPanning) {
                    if (canPanHorizontally()) translateX += dx
                    if (canPanVertically()) translateY += dy
                    lastPanX = ev.x
                    lastPanY = ev.y
                    clampTranslation()
                    applyTranslation()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isPanning) {
                    isPanning = false
                    onRequestParentDisallowIntercept?.invoke(false)
                    return true
                }
            }
        }
        return isPanning
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isPanning || !canPan()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastPanX
                val dy = event.y - lastPanY
                if (canPanHorizontally()) translateX += dx
                if (canPanVertically()) translateY += dy
                lastPanX = event.x
                lastPanY = event.y
                clampTranslation()
                applyTranslation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                onRequestParentDisallowIntercept?.invoke(false)
            }
        }
        return true
    }
}
