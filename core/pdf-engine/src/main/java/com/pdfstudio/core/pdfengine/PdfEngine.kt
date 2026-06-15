package com.pdfstudio.core.pdfengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfstudio.core.common.io.UriDisplayNameResolver
import com.pdfstudio.core.common.result.AppResult
import com.pdfstudio.core.pdfengine.model.PdfBookmark
import com.pdfstudio.core.pdfengine.model.PdfDocumentHandle
import com.pdfstudio.core.pdfengine.model.PdfMeta
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var pdfiumCore: PdfiumCore? = null

    /** pdfium 全局非线程安全，所有 native 调用必须串行 */
    private val pdfiumLock = Any()

    @Volatile
    private var opsInFlight = 0

    fun awaitIdle(timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(pdfiumLock) {
            while (opsInFlight > 0 && System.currentTimeMillis() < deadline) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (pdfiumLock as Object).wait(20)
            }
        }
    }

    private inline fun <T> runPdfium(block: () -> T): T {
        synchronized(pdfiumLock) {
            opsInFlight++
            try {
                return block()
            } finally {
                opsInFlight--
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (pdfiumLock as Object).notifyAll()
            }
        }
    }

    private inline fun <T> runPdfiumFor(handle: PdfDocumentHandle, default: T, block: () -> T): T {
        synchronized(pdfiumLock) {
            if (handle.isClosed) return default
            opsInFlight++
            try {
                return block()
            } finally {
                opsInFlight--
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (pdfiumLock as Object).notifyAll()
            }
        }
    }

    private inline fun <T> runPdfiumForRequired(handle: PdfDocumentHandle, block: () -> T): T {
        synchronized(pdfiumLock) {
            if (handle.isClosed) throw PdfRenderException("Document already closed")
            opsInFlight++
            try {
                return block()
            } finally {
                opsInFlight--
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (pdfiumLock as Object).notifyAll()
            }
        }
    }

    fun ensureInitialized(): PdfiumCore {
        return pdfiumCore ?: synchronized(this) {
            pdfiumCore ?: PdfiumCore(context).also { pdfiumCore = it }
        }
    }

    fun openDocument(uri: Uri, password: String? = null): AppResult<PdfDocumentHandle> {
        return try {
            runPdfium {
                val core = ensureInitialized()
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@runPdfium AppResult.Error("Cannot open file descriptor for $uri")
                val doc = if (password.isNullOrEmpty()) {
                    core.newDocument(pfd)
                } else {
                    core.newDocument(pfd, password)
                }
                val pageCount = core.getPageCount(doc)
                val name = UriDisplayNameResolver.resolve(context, uri)
                AppResult.Success(
                    PdfDocumentHandle(
                        uri = uri,
                        displayName = name,
                        pdfiumCore = core,
                        pdfDocument = doc,
                        pageCount = pageCount,
                        password = password,
                        fileDescriptor = pfd,
                    ),
                )
            }
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                AppResult.Error("Password required or incorrect", e)
            } else {
                AppResult.Error(e.message ?: "Failed to open PDF", e)
            }
        }
    }

    fun closeDocument(handle: PdfDocumentHandle) {
        synchronized(pdfiumLock) {
            if (handle.isClosed) return
            opsInFlight++
            try {
                try {
                    handle.pdfiumCore.closeDocument(handle.pdfDocument)
                } catch (_: Exception) {
                }
                try {
                    handle.fileDescriptor.close()
                } catch (_: Exception) {
                }
                handle.isClosed = true
            } finally {
                opsInFlight--
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (pdfiumLock as Object).notifyAll()
            }
        }
    }

    fun getMeta(handle: PdfDocumentHandle): PdfMeta {
        return runPdfiumFor(handle, PdfMeta(title = null, author = null, pageCount = 0)) {
            val meta = handle.pdfiumCore.getDocumentMeta(handle.pdfDocument)
            PdfMeta(
                title = meta.title,
                author = meta.author,
                pageCount = handle.pageCount,
            )
        }
    }

    fun getPageSize(handle: PdfDocumentHandle, pageIndex: Int): Size {
        return runPdfiumFor(handle, Size(612, 792)) {
            handle.pdfiumCore.openPage(handle.pdfDocument, pageIndex)
            val width = handle.pdfiumCore.getPageWidthPoint(handle.pdfDocument, pageIndex)
            val height = handle.pdfiumCore.getPageHeightPoint(handle.pdfDocument, pageIndex)
            Size(width, height)
        }
    }

    fun getPageDisplayHeight(handle: PdfDocumentHandle, pageIndex: Int, targetWidth: Int): Int {
        val size = getPageSize(handle, pageIndex)
        val renderSize = PdfRenderLimits.computeRenderSize(size.width, size.height, targetWidth)
        return renderSize.height
    }

    fun renderPage(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        targetWidth: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap {
        return runPdfiumForRequired(handle) {
            val core = handle.pdfiumCore
            val doc = handle.pdfDocument
            core.openPage(doc, pageIndex)
            val pageWidth = core.getPageWidthPoint(doc, pageIndex)
            val pageHeight = core.getPageHeightPoint(doc, pageIndex)
            val renderSize = PdfRenderLimits.computeRenderSize(pageWidth, pageHeight, targetWidth)
            val bitmapWidth = renderSize.width
            val bitmapHeight = renderSize.height
            val bitmap = try {
                Bitmap.createBitmap(bitmapWidth, bitmapHeight, config)
            } catch (e: OutOfMemoryError) {
                throw PdfRenderException("Page too large to render (${bitmapWidth}x$bitmapHeight)", e)
            }
            try {
                core.renderPageBitmap(
                    doc,
                    bitmap,
                    pageIndex,
                    0,
                    0,
                    bitmapWidth,
                    bitmapHeight,
                    true,
                )
            } catch (e: Exception) {
                bitmap.recycle()
                throw PdfRenderException(e.message ?: "Pdfium render failed", e)
            }
            bitmap
        }
    }

    fun mapPageToDevice(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        pageX: Double,
        pageY: Double,
    ): android.graphics.Point {
        return runPdfiumFor(handle, android.graphics.Point(0, 0)) {
            handle.pdfiumCore.mapPageCoordsToDevice(
                handle.pdfDocument,
                pageIndex,
                startX,
                startY,
                sizeX,
                sizeY,
                0,
                pageX,
                pageY,
            )
        }
    }

    fun mapRectToDevice(
        handle: PdfDocumentHandle,
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        rect: RectF,
    ): RectF {
        return runPdfiumFor(handle, RectF()) {
            handle.pdfiumCore.mapRectToDevice(
                handle.pdfDocument,
                pageIndex,
                startX,
                startY,
                sizeX,
                sizeY,
                0,
                rect,
            )
        }
    }

    fun getBookmarks(handle: PdfDocumentHandle): List<PdfBookmark> {
        return runPdfiumFor(handle, emptyList()) {
            try {
                val bookmarks = handle.pdfiumCore.getTableOfContents(handle.pdfDocument)
                bookmarks.map { toBookmark(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun toBookmark(node: PdfDocument.Bookmark): PdfBookmark {
        return PdfBookmark(
            title = node.title ?: "",
            pageIndex = node.pageIdx.toInt(),
            children = node.children.map { toBookmark(it) },
        )
    }

    fun openInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? {
        return context.contentResolver.openFileDescriptor(uri, mode)
    }
}
