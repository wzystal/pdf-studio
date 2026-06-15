package com.pdfstudio.feature.reader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfannot.model.PdfAnnotation
import com.pdfstudio.feature.editor.EditorMode
import com.pdfstudio.feature.reader.databinding.ItemPdfPageBinding

class PdfPageAdapter(
    private val pageWidth: Int,
    private val bitmapProvider: (Int) -> Bitmap?,
    private val onPageVisible: (Int) -> Unit,
    private val onSelectionFinished: (Int, android.graphics.RectF, AnnotationType) -> Unit,
    private val onInkFinished: (Int, List<List<Pair<Float, Float>>>) -> Unit,
    private val onTapForNote: (Int, Float, Float) -> Unit,
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    companion object {
        private const val PAYLOAD_EDIT_MODE = "edit_mode"
        private const val PAYLOAD_ANNOTATIONS = "annotations"
        private const val PAYLOAD_BITMAP = "bitmap"
        private const val PAYLOAD_ZOOM = "zoom"
    }

    private var pageCount = 0
    private var pageHeights = intArrayOf()
    private var annotationsByPage = mapOf<Int, List<PdfAnnotation>>()
    var editMode: EditorMode = EditorMode.READ
    var currentPage: Int = 0
    var zoomScale: Float = 1f
    /** 捏合手势过程中临时缩放页高，松手后恢复为 1 */
    var zoomHeightScale: Float = 1f
    private var editableRange: IntRange = IntRange.EMPTY
    private var attachedRecyclerView: RecyclerView? = null

    fun setEditableRange(first: Int, last: Int) {
        if (editMode == EditorMode.READ) return
        if (first < 0 || last < first) return
        val newRange = first..last
        if (newRange == editableRange) return
        val pagesToRefresh = (editableRange + newRange).toSet()
        editableRange = newRange
        pagesToRefresh.forEach { page ->
            if (page in 0 until itemCount) {
                applyToBoundHolder(page) { it.bindEditMode(page) }
            }
        }
    }

    fun submitPageCount(count: Int, forceRefresh: Boolean = false): Boolean {
        val changed = pageCount != count
        pageCount = count
        return changed || forceRefresh
    }

    fun setPageHeights(heights: List<Int>) {
        pageHeights = heights.toIntArray()
    }

    fun notifyZoomLayoutChanged(resetPan: Boolean = false) {
        if (itemCount <= 0) return
        for (page in 0 until itemCount) {
            applyToBoundHolder(page) { it.bindZoom(page, resetPan) }
        }
    }

    /** 捏合缩放时，以焦点为中心调整可见页的平移偏移 */
    fun applyPinchFocalPoint(
        recyclerView: RecyclerView,
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
    ) {
        if (scaleFactor == 1f) return
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0) return
        val rvLoc = IntArray(2)
        recyclerView.getLocationInWindow(rvLoc)
        val focusWinX = rvLoc[0] + focusX
        val focusWinY = rvLoc[1] + focusY
        var applied = false
        for (page in first..last) {
            val holder = recyclerView.findViewHolderForAdapterPosition(page) as? PageViewHolder ?: continue
            if (holder.scaleAroundFocalPoint(focusWinX, focusWinY, scaleFactor)) {
                applied = true
            }
        }
        if (!applied) {
            val center = (first + last) / 2
            (recyclerView.findViewHolderForAdapterPosition(center) as? PageViewHolder)
                ?.scaleAroundCenter(scaleFactor)
        }
    }

    fun notifyZoomChanged() {
        notifyZoomLayoutChanged()
        if (itemCount <= 0) return
        for (page in 0 until itemCount) {
            if (bitmapProvider(page) != null) {
                applyToBoundHolder(page) { it.bindBitmap(page) }
            }
        }
    }

    fun refreshEditMode() {
        if (itemCount <= 0) return
        for (page in 0 until itemCount) {
            applyToBoundHolder(page) { it.bindEditMode(page) }
        }
    }

    /** 将缓存 bitmap 直接刷到已绑定的 ViewHolder（不走 notifyItemChanged）。 */
    fun applyBitmapToPage(recyclerView: RecyclerView, pageIndex: Int) {
        if (pageIndex !in 0 until itemCount) return
        if (bitmapProvider(pageIndex) == null) return
        runOnRecyclerReady(recyclerView) {
            (recyclerView.findViewHolderForAdapterPosition(pageIndex) as? PageViewHolder)?.bindBitmap(pageIndex)
        }
    }

    fun applyBitmapToPages(recyclerView: RecyclerView, pages: IntRange) {
        for (page in pages) {
            applyBitmapToPage(recyclerView, page)
        }
    }

    private fun runOnRecyclerReady(recyclerView: RecyclerView, block: () -> Unit) {
        if (recyclerView.isComputingLayout) {
            recyclerView.post { runOnRecyclerReady(recyclerView, block) }
        } else {
            block()
        }
    }

    private fun applyToBoundHolder(pageIndex: Int, block: (PageViewHolder) -> Unit) {
        val rv = attachedRecyclerView ?: return
        runOnRecyclerReady(rv) {
            (rv.findViewHolderForAdapterPosition(pageIndex) as? PageViewHolder)?.let(block)
        }
    }

    private fun heightFor(pageIndex: Int): Int {
        if (pageIndex in pageHeights.indices) return pageHeights[pageIndex]
        return (pageWidth * 1.414f).toInt().coerceAtLeast(1)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
    }

    fun updateAnnotations(annotations: List<PdfAnnotation>): Set<Int> {
        val grouped = annotations.groupBy { it.pageIndex }
        val previous = annotationsByPage
        if (grouped == previous) return emptySet()
        val changed = mutableSetOf<Int>()
        val allPages = grouped.keys + previous.keys
        for (page in allPages) {
            if (grouped[page] != previous[page]) changed.add(page)
        }
        annotationsByPage = grouped
        return changed
    }

    fun notifyAnnotationsChanged(pages: Set<Int>) {
        pages.forEach { page ->
            if (page in 0 until itemCount) {
                applyToBoundHolder(page) { it.bindAnnotations(page) }
            }
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (payloads.contains(PAYLOAD_EDIT_MODE)) holder.bindEditMode(position)
        if (payloads.contains(PAYLOAD_ANNOTATIONS)) holder.bindAnnotations(position)
        if (payloads.contains(PAYLOAD_BITMAP)) holder.bindBitmap(position)
        if (payloads.contains(PAYLOAD_ZOOM)) {
            holder.bindZoom(position)
            holder.bindBitmap(position)
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
        holder.itemView.post {
            if (holder.bindingAdapterPosition == position) onPageVisible(position)
        }
    }

    inner class PageViewHolder(
        private val binding: ItemPdfPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.zoomContainer.onRequestParentDisallowIntercept = { disallow ->
                var parent = binding.root.parent
                while (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(disallow)
                    if (parent is RecyclerView) break
                    parent = parent.parent
                }
            }
        }

        fun bind(pageIndex: Int) {
            binding.overlay.resetTransientState()
            bindZoom(pageIndex, resetPan = true)
            bindBitmap(pageIndex)
            bindAnnotations(pageIndex)
            bindEditMode(pageIndex)
            binding.overlay.onSelectionFinished = { rect, type ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSelectionFinished(pos, rect, type)
            }
            binding.overlay.onInkFinished = { strokes ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onInkFinished(pos, strokes)
            }
            binding.overlay.onTapForNote = { x, y ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onTapForNote(pos, x, y)
            }
        }

        fun scaleAroundFocalPoint(focusWinX: Float, focusWinY: Float, scaleFactor: Float): Boolean {
            val container = binding.zoomContainer
            val containerLoc = IntArray(2)
            container.getLocationInWindow(containerLoc)
            val localX = focusWinX - containerLoc[0]
            val localY = focusWinY - containerLoc[1]
            if (localX < 0 || localY < 0 || localX > container.width || localY > container.height) {
                return false
            }
            container.scaleAroundPoint(localX, localY, scaleFactor)
            return true
        }

        fun scaleAroundCenter(scaleFactor: Float) {
            val container = binding.zoomContainer
            container.scaleAroundPoint(container.width / 2f, container.height / 2f, scaleFactor)
        }

        fun bindZoom(pageIndex: Int, resetPan: Boolean = false) {
            val height = (heightFor(pageIndex) * zoomHeightScale).toInt().coerceAtLeast(1)
            val contentWidth = (pageWidth * zoomScale).toInt().coerceAtLeast(1)
            binding.zoomContainer.setContentSize(contentWidth, height)
            if (resetPan) {
                binding.zoomContainer.resetPan()
            }
        }

        fun bindBitmap(pageIndex: Int) {
            val bitmap = bitmapProvider(pageIndex)
            if (bitmap != null && !bitmap.isRecycled) {
                binding.ivPage.setImageBitmap(bitmap)
                binding.ivPage.requestLayout()
                binding.zoomContainer.requestLayout()
            }
        }

        fun bindAnnotations(pageIndex: Int) {
            binding.overlay.annotations = annotationsByPage[pageIndex] ?: emptyList()
        }

        fun bindEditMode(pageIndex: Int) {
            val editable = editMode != EditorMode.READ && pageIndex in editableRange
            binding.overlay.editMode = if (editable) editMode else EditorMode.READ
            binding.overlay.isClickable = editable
            binding.overlay.isFocusable = editable
            binding.zoomContainer.panEnabled = !editable
        }
    }
}
