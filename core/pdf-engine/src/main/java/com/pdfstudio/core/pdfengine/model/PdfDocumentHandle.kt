package com.pdfstudio.core.pdfengine.model

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore

data class PdfDocumentHandle(
    val uri: Uri,
    val displayName: String,
    val pdfiumCore: PdfiumCore,
    val pdfDocument: PdfDocument,
    val pageCount: Int,
    val password: String? = null,
    /** 必须持有 PFD，否则 GC 后文件描述符关闭会导致渲染黑屏/失败 */
    val fileDescriptor: ParcelFileDescriptor,
) {
    @Volatile
    var isClosed: Boolean = false
}

data class PdfMeta(
    val title: String?,
    val author: String?,
    val pageCount: Int,
)

data class PdfBookmark(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfBookmark> = emptyList(),
)

data class PdfSearchResult(
    val pageIndex: Int,
    val startIndex: Int,
    val length: Int,
    val snippet: String,
)
