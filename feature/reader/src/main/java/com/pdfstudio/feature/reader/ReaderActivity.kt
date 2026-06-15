package com.pdfstudio.feature.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pdfstudio.core.pdfannot.CoordinateMapper
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfrender.RenderState
import com.pdfstudio.feature.editor.EditorMode
import com.pdfstudio.feature.editor.SignaturePadActivity
import com.pdfstudio.feature.pageops.PageOpsDialogFragment
import com.pdfstudio.feature.reader.databinding.ActivityReaderBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ReaderActivity : AppCompatActivity(), PageOpsDialogFragment.Callback {

    private lateinit var binding: ActivityReaderBinding
    private val viewModel: ReaderViewModel by viewModels()
    private lateinit var pageAdapter: PdfPageAdapter

    private var pendingMergeSources: List<Uri> = emptyList()
    private var pendingSplitRange: IntRange? = null
    private var isUserScrolling = false
    private var isPinchZooming = false
    private var isHorizontalPagePanning = false
    private var panTargetContainer: ZoomablePageContainer? = null
    private var panDownX = 0f
    private var panDownY = 0f
    private var panNeedsDown = false
    private val panTouchSlop by lazy {
        android.view.ViewConfiguration.get(this).scaledTouchSlop
    }
    private var pinchStartZoom = 1f
    private var pinchCumulativeScale = 1f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastPageHeights: List<Int> = emptyList()
    private var lastDocumentUri: String? = null

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        uri?.let { viewModel.saveAs(it) }
    }

    private val mergePickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingMergeSources = uris
            mergeOutputLauncher.launch("merged_${System.currentTimeMillis()}.pdf")
        }
    }

    private val mergeOutputLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        uri?.let { viewModel.mergePdfs(pendingMergeSources, it) }
    }

    private val splitOutputLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val range = pendingSplitRange
        if (uri != null && range != null) {
            viewModel.splitPdf(listOf(range), listOf(uri))
            pendingSplitRange = null
        }
    }

    private val signatureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val base64 = result.data?.getStringExtra(SignaturePadActivity.EXTRA_SIGNATURE_BASE64)
            base64?.let {
                viewModel.addSignature(it)
                viewModel.setEditMode(EditorMode.HIGHLIGHT)
                pageAdapter.editMode = EditorMode.HIGHLIGHT
                binding.editorToolbar.setSelectedMode(EditorMode.HIGHLIGHT)
                pageAdapter.refreshEditMode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
            ?: run { finish(); return }

        setupRecycler()
        observeState()
        observeRender()
        setupToolbar()

        viewModel.setTargetWidth(resources.displayMetrics.widthPixels)
        viewModel.open(uri)
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.releaseReader()
        }
        super.onDestroy()
    }

    private fun runOnRecyclerIdle(block: () -> Unit) {
        binding.recyclerPages.post {
            val rv = binding.recyclerPages
            if (!rv.isComputingLayout && rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                block()
            } else {
                runOnRecyclerIdle(block)
            }
        }
    }

    private fun applyVisibleBitmapsFromCache() {
        val pageCount = viewModel.uiState.value.pageCount
        if (pageCount <= 0) return
        val lm = binding.recyclerPages.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val indices = if (first >= 0 && last >= first) {
            first..last
        } else {
            0 until minOf(3, pageCount)
        }
        pageAdapter.applyBitmapToPages(binding.recyclerPages, indices)
    }

    private fun applyRenderState(state: RenderState) {
        when (state) {
            is RenderState.Success -> {
                pageAdapter.applyBitmapToPage(binding.recyclerPages, state.pageIndex)
            }
            is RenderState.Error -> {
                Toast.makeText(
                    this,
                    getString(R.string.page_render_failed, state.pageIndex + 1, state.message),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            else -> Unit
        }
    }

    private fun onPageVisible(page: Int) {
        viewModel.ensurePageHeights(page, page + 2)
        if (viewModel.getCachedBitmap(page) == null) {
            viewModel.requestRender(page)
        }
    }

    private fun updateVisiblePageIndicator() {
        val rv = binding.recyclerPages
        if (rv.isComputingLayout) {
            rv.post { updateVisiblePageIndicator() }
            return
        }
        val lm = binding.recyclerPages.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0) return
        val current = if (last > first) (first + last) / 2 else first
        pageAdapter.currentPage = current
        viewModel.setCurrentPage(current)
        updatePageIndicator(current)
        if (!isUserScrolling) {
            viewModel.ensurePageHeights(first, last + 2)
        }
    }

    private fun renderFocalAndPreload() {
        val lm = binding.recyclerPages.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0) {
            binding.recyclerPages.doOnNextLayout {
                viewModel.requestRender(0)
                pageAdapter.applyBitmapToPage(binding.recyclerPages, 0)
            }
            return
        }
        val current = if (last > first) (first + last) / 2 else first
        pageAdapter.currentPage = current
        viewModel.setCurrentPage(current)
        updatePageIndicator(current)
        val inEditMode = viewModel.uiState.value.editMode != EditorMode.READ
        if (inEditMode) {
            pageAdapter.setEditableRange(first, last)
        }
        viewModel.ensurePageHeights(first, last + 2)
        if (!inEditMode || viewModel.getCachedBitmap(current) == null) {
            viewModel.requestRender(current)
        }
        if (!inEditMode) {
            for (i in first..last.coerceAtLeast(first)) {
                if (i != current) {
                    viewModel.preloadPage(i)
                }
                pageAdapter.applyBitmapToPage(binding.recyclerPages, i)
            }
        }
    }

    private fun setupRecycler() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchStartZoom = viewModel.uiState.value.zoomScale
                pinchCumulativeScale = 1f
                isPinchZooming = true
                binding.recyclerPages.stopScroll()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                pinchCumulativeScale *= factor
                val preview = (pinchStartZoom * pinchCumulativeScale)
                    .coerceIn(ReaderViewModel.MIN_ZOOM, ReaderViewModel.MAX_ZOOM)
                pageAdapter.zoomScale = preview
                pageAdapter.zoomHeightScale = preview / pinchStartZoom
                pageAdapter.applyPinchFocalPoint(binding.recyclerPages, detector.focusX, detector.focusY, factor)
                pageAdapter.notifyZoomLayoutChanged(resetPan = false)
                compensateVerticalFocalScroll(detector.focusY, factor)
                updatePageIndicator(pageAdapter.currentPage, preview)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinchZooming = false
                pageAdapter.zoomHeightScale = 1f
                val finalScale = (pinchStartZoom * pinchCumulativeScale)
                    .coerceIn(ReaderViewModel.MIN_ZOOM, ReaderViewModel.MAX_ZOOM)
                applyZoom(finalScale)
            }
        })

        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val viewportWidth = resources.displayMetrics.widthPixels - horizontalPadding

        pageAdapter = PdfPageAdapter(
            pageWidth = viewportWidth,
            bitmapProvider = { page -> viewModel.getCachedBitmap(page) },
            onPageVisible = { page -> onPageVisible(page) },
            onSelectionFinished = { page, rect, type ->
                viewModel.addHighlight(page, rect, type)
            },
            onInkFinished = { page, strokes -> viewModel.addInk(page, strokes) },
            onTapForNote = { page, x, y ->
                showNoteDialog(page, x, y)
            },
        )
        binding.recyclerPages.layoutManager = LinearLayoutManager(this)
        binding.recyclerPages.adapter = pageAdapter
        binding.recyclerPages.setItemViewCacheSize(4)
        binding.recyclerPages.itemAnimator = null
        binding.recyclerPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateVisiblePageIndicator()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        isUserScrolling = false
                        renderFocalAndPreload()
                    }
                    RecyclerView.SCROLL_STATE_DRAGGING,
                    RecyclerView.SCROLL_STATE_SETTLING,
                    -> isUserScrolling = true
                }
            }
        })
        binding.recyclerPages.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.pointerCount >= 2 || isPinchZooming) {
                    rv.stopScroll()
                    scaleDetector.onTouchEvent(e)
                    return true
                }
                if (pageAdapter.zoomScale > 1.01f) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            panDownX = e.x
                            panDownY = e.y
                            isHorizontalPagePanning = false
                            panNeedsDown = false
                            panTargetContainer = findZoomContainerUnder(rv, e.x, e.y)
                                ?.takeIf { it.isHorizontallyPannable() }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (panTargetContainer != null && !isHorizontalPagePanning) {
                                val dx = e.x - panDownX
                                val dy = e.y - panDownY
                                if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > panTouchSlop) {
                                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                        isHorizontalPagePanning = true
                                        panNeedsDown = true
                                    } else {
                                        panTargetContainer = null
                                    }
                                }
                            }
                            if (isHorizontalPagePanning) return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isHorizontalPagePanning) {
                                dispatchPanToContainer(rv, e)
                                isHorizontalPagePanning = false
                                panTargetContainer = null
                                panNeedsDown = false
                                return true
                            }
                            panTargetContainer = null
                        }
                    }
                }
                return isHorizontalPagePanning
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.pointerCount >= 2 || isPinchZooming) {
                    scaleDetector.onTouchEvent(e)
                    return
                }
                if (isHorizontalPagePanning) {
                    dispatchPanToContainer(rv, e)
                    if (e.actionMasked == MotionEvent.ACTION_UP ||
                        e.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        isHorizontalPagePanning = false
                        panTargetContainer = null
                        panNeedsDown = false
                    }
                }
            }
        })
    }

    private fun findZoomContainerUnder(rv: RecyclerView, x: Float, y: Float): ZoomablePageContainer? {
        val child = rv.findChildViewUnder(x, y) ?: return null
        return child.findViewById(R.id.zoomContainer)
    }

    private fun dispatchPanToContainer(rv: RecyclerView, event: MotionEvent) {
        val container = panTargetContainer ?: return
        val local = MotionEvent.obtain(event)
        val rvLoc = IntArray(2)
        rv.getLocationOnScreen(rvLoc)
        val containerLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)
        local.offsetLocation(
            (rvLoc[0] - containerLoc[0]).toFloat(),
            (rvLoc[1] - containerLoc[1]).toFloat(),
        )
        if (panNeedsDown) {
            val down = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                MotionEvent.ACTION_DOWN,
                panDownX + rvLoc[0] - containerLoc[0],
                panDownY + rvLoc[1] - containerLoc[1],
                event.metaState,
            )
            container.handlePanMotionEvent(down)
            down.recycle()
            panNeedsDown = false
        }
        container.handlePanMotionEvent(local)
        local.recycle()
    }

    private fun applyZoom(scale: Float) {
        viewModel.setZoomScale(scale)
        pageAdapter.zoomScale = scale
        pageAdapter.setPageHeights(viewModel.uiState.value.pageHeights)
        runOnRecyclerIdle {
            pageAdapter.notifyZoomLayoutChanged(resetPan = false)
            renderFocalAndPreload()
        }
        updatePageIndicator(pageAdapter.currentPage, scale)
    }

    /** 捏合放大时补偿纵向滚动，使焦点附近内容尽量保持在双指中心 */
    private fun compensateVerticalFocalScroll(focusY: Float, scaleFactor: Float) {
        if (scaleFactor == 1f) return
        val delta = (focusY * (scaleFactor - 1f)).toInt()
        if (delta != 0) {
            binding.recyclerPages.scrollBy(0, delta)
        }
    }

    private fun setupToolbar() {
        binding.editorToolbar.onModeSelected = { mode ->
            viewModel.setEditMode(mode)
            pageAdapter.editMode = mode
            binding.editorToolbar.setSelectedMode(mode)
            runOnRecyclerIdle { pageAdapter.refreshEditMode() }
            if (mode == EditorMode.STAMP) {
                signatureLauncher.launch(SignaturePadActivity.createIntent(this))
            }
        }
        binding.editorToolbar.onUndo = { viewModel.undo() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.toolbar.title = state.displayName
                    pageAdapter.setPageHeights(state.pageHeights)
                    pageAdapter.zoomScale = state.zoomScale
                    if (state.pageHeights != lastPageHeights && state.pageHeights.isNotEmpty()) {
                        lastPageHeights = state.pageHeights
                        if (pageAdapter.itemCount > 0) {
                            binding.recyclerPages.post {
                                pageAdapter.notifyZoomLayoutChanged()
                            }
                        }
                    }
                    val newDocument = state.documentUri != null && state.documentUri != lastDocumentUri
                    if (newDocument) lastDocumentUri = state.documentUri
                    if (pageAdapter.submitPageCount(state.pageCount, forceRefresh = newDocument)) {
                        lastPageHeights = state.pageHeights
                        binding.recyclerPages.post {
                            pageAdapter.notifyDataSetChanged()
                            binding.recyclerPages.doOnNextLayout {
                                viewModel.requestRender(0)
                                val end = minOf(3, state.pageCount)
                                if (end > 0) {
                                    pageAdapter.applyBitmapToPages(binding.recyclerPages, 0 until end)
                                }
                                renderFocalAndPreload()
                            }
                        }
                    }
                    val editModeChanged = pageAdapter.editMode != state.editMode
                    pageAdapter.editMode = state.editMode
                    binding.recyclerPages.isNestedScrollingEnabled = true
                    binding.editorToolbar.setSelectedMode(
                        if (state.editMode == EditorMode.READ) EditorMode.HIGHLIGHT else state.editMode,
                    )
                    if (editModeChanged) {
                        val lm = binding.recyclerPages.layoutManager as? LinearLayoutManager
                        if (lm != null && state.editMode != EditorMode.READ) {
                            val first = lm.findFirstVisibleItemPosition()
                            val last = lm.findLastVisibleItemPosition()
                            if (first >= 0) {
                                runOnRecyclerIdle { pageAdapter.setEditableRange(first, last) }
                            }
                        }
                        runOnRecyclerIdle { pageAdapter.refreshEditMode() }
                    }
                    binding.editorToolbar.visibility =
                        if (state.editMode != EditorMode.READ) View.VISIBLE else View.GONE
                    invalidateOptionsMenu()
                    updatePageIndicator(state.currentPage, state.zoomScale)
                    state.error?.let {
                        Toast.makeText(this@ReaderActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.clearMessage()
                    }
                    state.message?.let {
                        Toast.makeText(this@ReaderActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.clearMessage()
                    }
                    if (state.needsPassword) showPasswordDialog()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.annotations.collect { list ->
                    val changedPages = pageAdapter.updateAnnotations(list)
                    if (changedPages.isNotEmpty()) {
                        pageAdapter.notifyAnnotationsChanged(changedPages)
                    }
                }
            }
        }
    }

    private fun observeRender() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applyVisibleBitmapsFromCache()
                val current = viewModel.renderState.value
                if (current is RenderState.Success) {
                    applyRenderState(current)
                }
                viewModel.renderState.collect { applyRenderState(it) }
            }
        }
    }

    private fun updatePageIndicator(page: Int, zoomScale: Float = viewModel.uiState.value.zoomScale) {
        val total = viewModel.uiState.value.pageCount
        if (total > 0) {
            val percent = (zoomScale * 100).toInt()
            binding.tvPageIndicator.text = getString(
                R.string.page_indicator_with_zoom,
                page + 1,
                total,
                percent,
            )
        }
    }

    private fun showPasswordDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.password_required)
            .setMessage(R.string.enter_password)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.submitPassword(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun showNoteDialog(pageIndex: Int, x: Float, y: Float) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(com.pdfstudio.feature.editor.R.string.enter_note)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    val w = viewModel.getPageContentWidth()
                    val h = viewModel.uiState.value.pageHeights.getOrElse(pageIndex) { (w * 1.4f).toInt() }
                    val (nx, ny) = CoordinateMapper.deviceToNormalized(x, y, w, h)
                    val rect = RectF(nx, ny, (nx + 0.3f).coerceAtMost(1f), (ny + 0.08f).coerceAtMost(1f))
                    viewModel.addNote(pageIndex, rect, text)
                }
            }
            .show()
    }

    private fun showSearchDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.search)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.search(input.text.toString())
                showSearchResults()
            }
            .show()
    }

    private fun showSearchResults() {
        val results = viewModel.uiState.value.searchResults
        if (results.isEmpty()) {
            Toast.makeText(this, R.string.no_search_results, Toast.LENGTH_SHORT).show()
            return
        }
        val items = results.map { "第${it.pageIndex + 1}页：${it.snippet}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.search)
            .setItems(items) { _, which ->
                val page = results[which].pageIndex
                binding.recyclerPages.scrollToPosition(page)
                viewModel.renderPage(page)
            }
            .show()
    }

    private fun showBookmarks() {
        val bookmarks = viewModel.uiState.value.bookmarks
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            return
        }
        val items = bookmarks.map { "${it.title}（第${it.pageIndex + 1}页）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks)
            .setItems(items) { _, which ->
                val page = bookmarks[which].pageIndex
                binding.recyclerPages.scrollToPosition(page)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_edit)?.title = if (viewModel.uiState.value.editMode == EditorMode.READ) {
            getString(R.string.edit_mode)
        } else {
            getString(R.string.read_mode)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_edit -> {
                viewModel.toggleEditMode()
                true
            }
            R.id.action_save -> {
                saveLauncher.launch("annotated_${System.currentTimeMillis()}.pdf")
                true
            }
            R.id.action_search -> {
                showSearchDialog()
                true
            }
            R.id.action_bookmarks -> {
                showBookmarks()
                true
            }
            R.id.action_page_ops -> {
                PageOpsDialogFragment.newInstance().show(supportFragmentManager, "page_ops")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRotatePage(degrees: Int) {
        viewModel.rotateCurrentPage(degrees)
    }

    override fun onDeletePage() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_page_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteCurrentPage() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onMergePdfs() {
        mergePickLauncher.launch(arrayOf("application/pdf"))
    }

    override fun onSplitPdf() {
        showSplitDialog()
    }

    private fun showSplitDialog() {
        val total = viewModel.uiState.value.pageCount
        if (total <= 0) return
        val current = viewModel.uiState.value.currentPage + 1
        val fromInput = EditText(this).apply {
            hint = getString(R.string.split_from_page)
            setText(current.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val toInput = EditText(this).apply {
            hint = getString(R.string.split_to_page)
            setText(current.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(fromInput)
            addView(toInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.split_range_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val from = fromInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                val to = toInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (from < 1 || to < from || to > total) {
                    Toast.makeText(this, R.string.invalid_page_range, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingSplitRange = (from - 1)..(to - 1)
                splitOutputLauncher.launch("split_p${from}-${to}_${System.currentTimeMillis()}.pdf")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"

        fun createIntent(context: Context, uri: Uri): Intent {
            return Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URI, uri.toString())
        }
    }
}
