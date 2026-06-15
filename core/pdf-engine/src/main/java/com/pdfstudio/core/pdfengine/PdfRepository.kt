package com.pdfstudio.core.pdfengine

import android.net.Uri
import com.pdfstudio.core.common.result.AppResult
import com.pdfstudio.core.pdfengine.model.PdfDocumentHandle
import com.pdfstudio.core.pdfengine.model.PdfMeta
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfRepository @Inject constructor(
    private val pdfEngine: PdfEngine,
) {
    @Volatile
    private var currentDocument: PdfDocumentHandle? = null

    fun getCurrentDocument(): PdfDocumentHandle? = currentDocument

    fun open(uri: Uri, password: String? = null): AppResult<PdfDocumentHandle> {
        close()
        return when (val result = pdfEngine.openDocument(uri, password)) {
            is AppResult.Success -> {
                currentDocument = result.data
                result
            }
            is AppResult.Error -> result
        }
    }

    fun getMeta(): AppResult<PdfMeta> {
        val doc = currentDocument ?: return AppResult.Error("No document open")
        return AppResult.Success(pdfEngine.getMeta(doc))
    }

    fun close() {
        val doc = currentDocument
        currentDocument = null
        doc?.let { pdfEngine.closeDocument(it) }
    }

    fun requiresPassword(result: AppResult.Error): Boolean {
        return result.message.contains("password", ignoreCase = true)
    }
}
