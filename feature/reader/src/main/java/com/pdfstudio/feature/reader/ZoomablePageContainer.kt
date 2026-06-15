package com.pdfstudio.feature.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 包裹单页 PDF 内容：放大后内容宽于视口时支持单指横向平移。
 */
class ZoomablePageContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var contentWidthPx = 0
    private var contentHeightPx = 0
    private var translateX = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    var onRequestParentDisallowIntercept: ((Boolean) -> Unit)? = null

    /** 编辑模式下禁用横向平移，避免与框选/手绘手势冲突 */
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

    fun resetPan() {
        translateX = 0f
        isPanning = false
        applyTranslation()
    }

    private fun getPageContent(): View? = if (childCount > 0) getChildAt(0) else null

    private fun viewportWidth(): Int = width.coerceAtLeast(1)

    private fun canPanHorizontally(): Boolean = contentWidthPx > viewportWidth() + touchSlop

    private fun clampTranslation() {
        if (!canPanHorizontally()) {
            translateX = 0f
            return
        }
        val minTx = (viewportWidth() - contentWidthPx).toFloat()
        translateX = translateX.coerceIn(minTx, 0f)
    }

    private fun applyTranslation() {
        getPageContent()?.translationX = translateX
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
        if (!panEnabled || !canPanHorizontally()) return false
        if (ev.pointerCount > 1) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = ev.x
                lastPanY = ev.y
                isPanning = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - lastPanX)
                val dy = abs(ev.y - lastPanY)
                if (!isPanning && dx > touchSlop && dx > dy * 1.2f) {
                    isPanning = true
                    onRequestParentDisallowIntercept?.invoke(true)
                }
                if (isPanning) {
                    translateX += ev.x - lastPanX
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
        if (!isPanning || !canPanHorizontally()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translateX += event.x - lastPanX
                lastPanX = event.x
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
