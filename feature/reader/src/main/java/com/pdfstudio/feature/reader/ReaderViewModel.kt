package com.pdfstudio.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfstudio.core.common.result.AppResult
import com.pdfstudio.core.pdfannot.AnnotationManager
import com.pdfstudio.core.pdfannot.PdfAnnotationExporter
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfannot.model.PdfAnnotation
import com.pdfstudio.core.pdfengine.PageOperationService
import com.pdfstudio.core.pdfengine.PdfEngine
import com.pdfstudio.core.pdfengine.PdfRepository
import com.pdfstudio.core.pdfengine.PdfTextService
import com.pdfstudio.core.pdfengine.model.PdfBookmark
import com.pdfstudio.core.pdfengine.model.PdfDocumentHandle
import com.pdfstudio.core.pdfengine.model.PdfSearchResult
import com.pdfstudio.core.pdfrender.RenderEngine
import com.pdfstudio.core.storage.RecentFileRepository
import com.pdfstudio.feature.editor.EditorMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class ReaderUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val documentUri: String? = null,
    val displayName: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val editMode: EditorMode = EditorMode.READ,
    val needsPassword: Boolean = false,
    val bookmarks: List<PdfBookmark> = emptyList(),
    val searchResults: List<PdfSearchResult> = emptyList(),
    val message: String? = null,
    val pageHeights: List<Int> = emptyList(),
    val zoomScale: Float = 1f,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    application: Application,
    private val pdfRepository: PdfRepository,
    private val pdfEngine: PdfEngine,
    private val renderEngine: RenderEngine,
    private val annotationManager: AnnotationManager,
    private val annotationExporter: PdfAnnotationExporter,
    private val recentFileRepository: RecentFileRepository,
    private val pdfTextService: PdfTextService,
    private val pageOperationService: PageOperationService,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pendingUri: Uri? = null
    private var baseScreenWidth: Int = 1080
    private var targetWidth: Int = 1080
    private var pageHeightsResolved = BooleanArray(0)
    private var pageHeightsJob: Job? = null

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 0.25f
        private const val LARGE_DOC_PAGE_THRESHOLD = 80
    }

    private fun defaultPageHeight(): Int =
        (baseScreenWidth * 1.414f).toInt().coerceAtLeast(1)

    val renderState = renderEngine.renderState

    val annotations: StateFlow<List<PdfAnnotation>> = _uiState
        .flatMapLatest { state ->
            val uri = state.documentUri
            if (uri == null) flowOf(emptyList())
            else annotationManager.observeAnnotations(uri)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun open(uri: Uri, password: String? = null) {
        pendingUri = uri
        stopRenderingAndCloseDocument()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, needsPassword = false)
            when (val result = pdfRepository.open(uri, password)) {
                is AppResult.Success -> onDocumentOpened(result.data)
                is AppResult.Error -> {
                    if (pdfRepository.requiresPassword(result)) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            needsPassword = true,
                            error = result.message,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }

    private suspend fun onDocumentOpened(handle: PdfDocumentHandle) {
        recentFileRepository.recordOpen(handle.uri, handle.displayName, handle.pageCount)
        pageHeightsResolved = BooleanArray(handle.pageCount)
        val heights: List<Int> = if (handle.pageCount <= LARGE_DOC_PAGE_THRESHOLD) {
            pageHeightsResolved.fill(true)
            (0 until handle.pageCount).map { pageIndex ->
                pdfEngine.getPageDisplayHeight(handle, pageIndex, targetWidth)
            }
        } else {
            MutableList(handle.pageCount) { defaultPageHeight() }.also { list ->
                if (handle.pageCount > 0) {
                    list[0] = pdfEngine.getPageDisplayHeight(handle, 0, targetWidth)
                    pageHeightsResolved[0] = true
                }
            }
        }
        val bookmarks = pdfEngine.getBookmarks(handle)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            documentUri = handle.uri.toString(),
            displayName = handle.displayName,
            pageCount = handle.pageCount,
            currentPage = 0,
            bookmarks = bookmarks,
            pageHeights = heights,
            zoomScale = 1f,
        )
        applyLowMemoryIfNeeded(handle.pageCount)
        renderPage(0)
        if (handle.pageCount > LARGE_DOC_PAGE_THRESHOLD) {
            ensurePageHeightsInternal(handle, 0, minOf(3, handle.pageCount - 1))
        }
    }

    fun ensurePageHeights(from: Int, to: Int) {
        pageHeightsJob?.cancel()
        pageHeightsJob = viewModelScope.launch(Dispatchers.IO) {
            val handle = pdfRepository.getCurrentDocument() ?: return@launch
            if (handle.isClosed) return@launch
            ensurePageHeightsInternal(handle, from, to)
        }
    }

    private suspend fun ensurePageHeightsInternal(handle: PdfDocumentHandle, from: Int, to: Int) {
        if (handle.isClosed || handle.pageCount <= 0) return
        val heights = _uiState.value.pageHeights.toMutableList()
        if (heights.size != handle.pageCount) return
        var changed = false
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(handle.pageCount - 1)
        for (i in start..end) {
            if (i < pageHeightsResolved.size && pageHeightsResolved[i]) continue
            heights[i] = pdfEngine.getPageDisplayHeight(handle, i, targetWidth)
            if (i < pageHeightsResolved.size) pageHeightsResolved[i] = true
            changed = true
        }
        if (changed) {
            _uiState.value = _uiState.value.copy(pageHeights = heights)
        }
    }

    fun getCachedBitmap(pageIndex: Int): Bitmap? {
        pdfRepository.getCurrentDocument() ?: return null
        return renderEngine.getCached(pageIndex, targetWidth)
    }

    private fun applyLowMemoryIfNeeded(pageCount: Int) {
        val activityManager = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val lowRam = activityManager.isLowRamDevice || pageCount > LARGE_DOC_PAGE_THRESHOLD
        renderEngine.setLowMemoryMode(lowRam)
    }

    fun submitPassword(password: String) {
        pendingUri?.let { open(it, password) }
    }

    fun setTargetWidth(width: Int) {
        baseScreenWidth = width.coerceAtLeast(320)
        targetWidth = baseScreenWidth
    }

    fun getPageContentWidth(): Int = targetWidth

    fun setZoomScale(scale: Float) {
        val clamped = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(clamped - _uiState.value.zoomScale) < 0.01f) return

        val handle = pdfRepository.getCurrentDocument()
        val oldZoom = _uiState.value.zoomScale
        val newRenderWidth = (baseScreenWidth * clamped).toInt().coerceIn(320, 4096)
        val widthBucketChanged = abs(newRenderWidth - targetWidth) > 32
        targetWidth = newRenderWidth

        if (handle != null) {
            val prevHeights = _uiState.value.pageHeights
            val heights = if (prevHeights.size == handle.pageCount && oldZoom > 0f) {
                prevHeights.map { (it * clamped / oldZoom).toInt().coerceAtLeast(1) }.toMutableList()
            } else {
                MutableList(handle.pageCount) { defaultPageHeight() }
            }
            val current = _uiState.value.currentPage
            if (widthBucketChanged && current in 0 until handle.pageCount) {
                heights[current] = pdfEngine.getPageDisplayHeight(handle, current, newRenderWidth)
                if (current < pageHeightsResolved.size) {
                    pageHeightsResolved[current] = true
                }
            }
            _uiState.value = _uiState.value.copy(
                zoomScale = clamped,
                pageHeights = heights,
            )
        } else {
            _uiState.value = _uiState.value.copy(zoomScale = clamped)
        }

        if (widthBucketChanged) {
            renderEngine.cancelAllRenders()
        }
    }

    fun zoomIn() = setZoomScale(_uiState.value.zoomScale + ZOOM_STEP)

    fun zoomOut() = setZoomScale(_uiState.value.zoomScale - ZOOM_STEP)

    fun resetZoom() = setZoomScale(1f)

    fun setCurrentPage(pageIndex: Int) {
        if (_uiState.value.currentPage != pageIndex) {
            _uiState.value = _uiState.value.copy(currentPage = pageIndex)
        }
    }

    fun requestRender(pageIndex: Int) {
        val handle = pdfRepository.getCurrentDocument() ?: return
        if (pageIndex !in 0 until handle.pageCount) return
        val preload = handle.pageCount <= LARGE_DOC_PAGE_THRESHOLD
        renderEngine.renderPage(handle, pageIndex, targetWidth, preloadNeighbors = preload)
    }

    fun preloadPage(pageIndex: Int) {
        val handle = pdfRepository.getCurrentDocument() ?: return
        if (pageIndex !in 0 until handle.pageCount) return
        if (handle.pageCount > LARGE_DOC_PAGE_THRESHOLD) return
        renderEngine.preloadPage(handle, pageIndex, targetWidth)
    }

    fun renderPage(pageIndex: Int) {
        setCurrentPage(pageIndex)
        requestRender(pageIndex)
    }

    fun getPageBitmap(pageIndex: Int): android.graphics.Bitmap? {
        val handle = pdfRepository.getCurrentDocument() ?: return null
        return renderEngine.renderPageSync(handle, pageIndex, targetWidth)
    }

    fun toggleEditMode() {
        val current = _uiState.value.editMode
        val next = if (current == EditorMode.READ) EditorMode.HIGHLIGHT else EditorMode.READ
        _uiState.value = _uiState.value.copy(editMode = next)
    }

    fun setEditMode(mode: EditorMode) {
        _uiState.value = _uiState.value.copy(editMode = mode)
    }

    fun addHighlight(pageIndex: Int, rect: RectF, type: AnnotationType) {
        val uri = _uiState.value.documentUri ?: return
        viewModelScope.launch {
            annotationManager.addHighlight(
                documentUri = uri,
                pageIndex = pageIndex,
                rect = rect,
                color = when (type) {
                    AnnotationType.UNDERLINE -> Color.argb(220, 255, 152, 0)
                    else -> Color.argb(100, 255, 235, 59)
                },
                type = type,
            )
        }
    }

    fun addInk(pageIndex: Int, strokes: List<List<Pair<Float, Float>>>) {
        val uri = _uiState.value.documentUri ?: return
        viewModelScope.launch {
            annotationManager.addInk(uri, pageIndex, strokes, Color.RED)
        }
    }

    fun addNote(pageIndex: Int, rect: RectF, text: String) {
        val uri = _uiState.value.documentUri ?: return
        viewModelScope.launch {
            annotationManager.addFreeText(uri, pageIndex, rect, text, Color.BLACK)
        }
    }

    fun addSignature(base64: String) {
        val uri = _uiState.value.documentUri ?: return
        val page = _uiState.value.currentPage
        val rect = RectF(0.15f, 0.72f, 0.85f, 0.92f)
        viewModelScope.launch {
            annotationManager.addStamp(uri, page, rect, base64)
            _uiState.value = _uiState.value.copy(
                message = getApplication<Application>().getString(R.string.signature_added, page + 1),
            )
        }
    }

    fun undo() {
        viewModelScope.launch {
            annotationManager.undo()
        }
    }

    fun search(query: String) {
        val uri = _uiState.value.documentUri ?: return
        val pageCount = _uiState.value.pageCount
        viewModelScope.launch {
            val results = pdfTextService.searchText(Uri.parse(uri), query, pageCount)
            _uiState.value = _uiState.value.copy(searchResults = results)
        }
    }

    fun saveAs(outputUri: Uri) {
        val sourceUri = _uiState.value.documentUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = annotationExporter.exportWithAnnotations(
                Uri.parse(sourceUri),
                outputUri,
                sourceUri,
            )) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = getApplication<Application>().getString(R.string.save_success),
                    )
                }
                is AppResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun rotateCurrentPage(degrees: Int) {
        val uri = _uiState.value.documentUri ?: return
        val page = _uiState.value.currentPage
        viewModelScope.launch {
            when (val result = pageOperationService.rotatePage(Uri.parse(uri), page, degrees)) {
                is AppResult.Success -> {
                    reopenAfterMutation(Uri.parse(uri))
                }
                is AppResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun deleteCurrentPage() {
        val uri = _uiState.value.documentUri ?: return
        val page = _uiState.value.currentPage
        viewModelScope.launch {
            when (val result = pageOperationService.deletePage(Uri.parse(uri), page)) {
                is AppResult.Success -> reopenAfterMutation(Uri.parse(uri))
                is AppResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun mergePdfs(sources: List<Uri>, output: Uri) {
        viewModelScope.launch {
            when (val result = pageOperationService.merge(sources, output)) {
                is AppResult.Success -> open(output)
                is AppResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun splitPdf(ranges: List<IntRange>, outputs: List<Uri>) {
        val uri = _uiState.value.documentUri ?: return
        viewModelScope.launch {
            when (val result = pageOperationService.split(Uri.parse(uri), ranges, outputs)) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        message = getApplication<Application>().getString(
                            R.string.split_success,
                            result.data.size,
                        ),
                    )
                }
                is AppResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    private suspend fun reopenAfterMutation(uri: Uri) {
        renderEngine.evictCache()
        open(uri)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /** Activity 退出时提前停止渲染并关闭文档，避免 pdfium 在后台继续访问已关闭句柄 */
    fun releaseReader() {
        stopRenderingAndCloseDocument()
    }

    private fun stopRenderingAndCloseDocument() {
        pageHeightsJob?.cancel()
        pageHeightsJob = null
        renderEngine.cancelAllRenders()
        pdfEngine.awaitIdle()
        renderEngine.evictCache()
        pdfRepository.close()
    }

    override fun onCleared() {
        releaseReader()
        super.onCleared()
    }
}
