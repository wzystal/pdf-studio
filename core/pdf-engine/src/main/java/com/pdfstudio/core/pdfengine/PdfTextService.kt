package com.pdfstudio.core.pdfengine

import android.content.Context
import android.net.Uri
import com.pdfstudio.core.pdfengine.model.PdfSearchResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfTextService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    fun extractPageText(uri: Uri, pageIndex: Int): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                if (pageIndex !in 0 until doc.numberOfPages) return ""
                val stripper = PDFTextStripper().apply {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                }
                stripper.getText(doc)
            }
        } ?: ""
    }

    fun searchText(uri: Uri, query: String, pageCount: Int): List<PdfSearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<PdfSearchResult>()
        val lowerQuery = query.lowercase()
        for (page in 0 until pageCount) {
            val pageText = extractPageText(uri, page)
            var index = pageText.lowercase().indexOf(lowerQuery)
            while (index >= 0) {
                val snippet = pageText.substring(
                    index.coerceAtLeast(0),
                    (index + query.length + 30).coerceAtMost(pageText.length),
                )
                results.add(
                    PdfSearchResult(
                        pageIndex = page,
                        startIndex = index,
                        length = query.length,
                        snippet = snippet.trim(),
                    )
                )
                index = pageText.lowercase().indexOf(lowerQuery, index + 1)
            }
        }
        return results
    }
}
