package com.pdfstudio.core.pdfrender

import android.graphics.Bitmap
import android.util.LruCache
import com.pdfstudio.core.common.dispatchers.DispatcherProvider
import com.pdfstudio.core.pdfengine.PdfEngine
import com.pdfstudio.core.pdfengine.PdfRenderException
import com.pdfstudio.core.pdfengine.model.PdfDocumentHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class PageRenderKey(
    val pageIndex: Int,
    val widthBucket: Int,
)

sealed class RenderState {
    data object Idle : RenderState()
    data class Loading(val pageIndex: Int) : RenderState()
    data class Success(val pageIndex: Int, val bitmap: Bitmap, val widthBucket: Int) : RenderState()
    data class Error(val pageIndex: Int, val message: String) : RenderState()
}

@Singleton
class RenderEngine @Inject constructor(
    private val pdfEngine: PdfEngine,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val renderMutex = Mutex()
    private var focalJob: Job? = null

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 8).toInt()
        .coerceAtLeast(4 * 1024 * 1024)

    private val bitmapCache = object : LruCache<PageRenderKey, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: PageRenderKey, value: Bitmap): Int = value.byteCount
    }

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    @Volatile
    private var useRgb565 = false

    fun setLowMemoryMode(enabled: Boolean) {
        useRgb565 = enabled
    }

    fun evictCache() {
        cancelAllRenders()
        bitmapCache.evictAll()
        _renderState.value = RenderState.Idle
    }

    fun previewWidth(targetWidth: Int): Int = max(targetWidth / 2, 360)

    fun getCached(pageIndex: Int, targetWidth: Int): Bitmap? {
        bitmapCache.get(PageRenderKey(pageIndex, targetWidth))?.let { return it }
        bitmapCache.get(PageRenderKey(pageIndex, previewWidth(targetWidth)))?.let { return it }
        // 缩放切换分辨率桶时，先用同页已有缓存顶住，避免黑屏
        return bitmapCache.snapshot()
            .filterKeys { it.pageIndex == pageIndex }
            .maxByOrNull { it.key.widthBucket }
            ?.value
    }

    /** 取消所有进行中的渲染（含 preload），关闭文档前必须调用 */
    fun cancelAllRenders() {
        scope.coroutineContext[Job]?.cancelChildren(CancellationException("Render cancelled"))
        focalJob = null
    }

    fun cancelPending() = cancelAllRenders()

    /** 优先渲染当前页：先出低清预览，再补高清 */
    fun renderPage(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        targetWidth: Int,
        preloadNeighbors: Boolean = true,
    ) {
        if (handle.isClosed || pageIndex < 0 || pageIndex >= handle.pageCount) return
        val fullKey = PageRenderKey(pageIndex, targetWidth)
        val previewKey = PageRenderKey(pageIndex, previewWidth(targetWidth))

        bitmapCache.get(fullKey)?.let {
            _renderState.value = RenderState.Success(pageIndex, it, targetWidth)
            if (preloadNeighbors) schedulePreload(handle, pageIndex, targetWidth)
            return
        }
        bitmapCache.get(previewKey)?.let {
            _renderState.value = RenderState.Success(pageIndex, it, previewWidth(targetWidth))
        }

        focalJob?.cancel()
        _renderState.value = RenderState.Loading(pageIndex)
        focalJob = scope.launch {
            try {
                if (handle.isClosed) return@launch
                if (bitmapCache.get(fullKey) == null && bitmapCache.get(previewKey) == null) {
                    renderAndCache(handle, pageIndex, previewWidth(targetWidth))?.let { bitmap ->
                        _renderState.value = RenderState.Success(pageIndex, bitmap, previewWidth(targetWidth))
                    }
                }
                if (handle.isClosed) return@launch
                if (bitmapCache.get(fullKey) == null) {
                    renderAndCache(handle, pageIndex, targetWidth)?.let { bitmap ->
                        _renderState.value = RenderState.Success(pageIndex, bitmap, targetWidth)
                    }
                }
                if (!handle.isClosed && preloadNeighbors) {
                    schedulePreload(handle, pageIndex, targetWidth)
                }
            } catch (_: CancellationException) {
            }
        }
    }

    fun preloadPage(handle: PdfDocumentHandle, pageIndex: Int, targetWidth: Int) {
        if (handle.isClosed || pageIndex < 0 || pageIndex >= handle.pageCount) return
        val fullKey = PageRenderKey(pageIndex, targetWidth)
        if (bitmapCache.get(fullKey) != null) return
        scope.launch {
            try {
                if (handle.isClosed) return@launch
                if (bitmapCache.get(fullKey) == null) {
                    val previewKey = PageRenderKey(pageIndex, previewWidth(targetWidth))
                    if (bitmapCache.get(previewKey) == null) {
                        renderAndCache(handle, pageIndex, previewWidth(targetWidth))?.let { bitmap ->
                            _renderState.value = RenderState.Success(pageIndex, bitmap, previewWidth(targetWidth))
                        }
                    }
                    if (handle.isClosed) return@launch
                    renderAndCache(handle, pageIndex, targetWidth)?.let { bitmap ->
                        _renderState.value = RenderState.Success(pageIndex, bitmap, targetWidth)
                    }
                }
            } catch (_: CancellationException) {
            }
        }
    }

    private fun schedulePreload(handle: PdfDocumentHandle, centerPage: Int, targetWidth: Int) {
        if (handle.isClosed || handle.pageCount > 80) return
        listOf(centerPage - 1, centerPage + 1).forEach { index ->
            preloadPage(handle, index, targetWidth)
        }
    }

    private suspend fun renderAndCache(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        renderWidth: Int,
    ): Bitmap? {
        if (handle.isClosed || pageIndex < 0 || pageIndex >= handle.pageCount) return null
        val key = PageRenderKey(pageIndex, renderWidth)
        bitmapCache.get(key)?.let { return it }
        return withContext(dispatchers.io) {
            renderMutex.withLock {
                try {
                    val config = if (useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    val bitmap = pdfEngine.renderPage(handle, pageIndex, max(renderWidth, 1), config)
                    bitmapCache.put(key, bitmap)
                    bitmap
                } catch (e: PdfRenderException) {
                    _renderState.value = RenderState.Error(pageIndex, e.message ?: "Render failed")
                    null
                } catch (e: OutOfMemoryError) {
                    _renderState.value = RenderState.Error(pageIndex, "内存不足，无法渲染该页")
                    null
                } catch (e: Exception) {
                    _renderState.value = RenderState.Error(pageIndex, e.message ?: "Render failed")
                    null
                }
            }
        }
    }

    fun renderPageSync(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        targetWidth: Int,
    ): Bitmap? {
        if (handle.isClosed || pageIndex < 0 || pageIndex >= handle.pageCount) return null
        return getCached(pageIndex, targetWidth) ?: run {
            try {
                val config = if (useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                val bitmap = pdfEngine.renderPage(handle, pageIndex, max(targetWidth, 1), config)
                bitmapCache.put(PageRenderKey(pageIndex, targetWidth), bitmap)
                bitmap
            } catch (_: Exception) {
                null
            }
        }
    }
}
