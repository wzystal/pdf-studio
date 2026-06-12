package com.pdfstudio.core.pdfengine

import android.content.Context
import android.net.Uri
import com.pdfstudio.core.common.result.AppResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageOperationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    fun rotatePage(sourceUri: Uri, pageIndex: Int, rotation: Int): AppResult<Unit> {
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                        return AppResult.Error("Invalid page index")
                    }
                    val page = doc.getPage(pageIndex)
                    page.rotation = (page.rotation + rotation) % 360
                    saveToUri(doc, sourceUri)
                }
            } ?: return AppResult.Error("Cannot open source")
            AppResult.Success(Unit)
        }.getOrElse { AppResult.Error(it.message ?: "Rotate failed", it) }
    }

    fun deletePage(sourceUri: Uri, pageIndex: Int): AppResult<Unit> {
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                        return AppResult.Error("Invalid page index")
                    }
                    doc.removePage(pageIndex)
                    saveToUri(doc, sourceUri)
                }
            } ?: return AppResult.Error("Cannot open source")
            AppResult.Success(Unit)
        }.getOrElse { AppResult.Error(it.message ?: "Delete failed", it) }
    }

    fun merge(sources: List<Uri>, outputUri: Uri): AppResult<Unit> {
        return runCatching {
            val merger = PDFMergerUtility()
            sources.forEach { uri ->
                context.contentResolver.openInputStream(uri)?.use { merger.addSource(it) }
            }
            context.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                merger.destinationStream = out
                merger.mergeDocuments(null)
            } ?: return AppResult.Error("Cannot open output")
            AppResult.Success(Unit)
        }.getOrElse { AppResult.Error(it.message ?: "Merge failed", it) }
    }

    fun split(sourceUri: Uri, ranges: List<IntRange>, outputUris: List<Uri>): AppResult<List<Uri>> {
        if (ranges.size != outputUris.size) {
            return AppResult.Error("Ranges and outputs count mismatch")
        }
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { sourceDoc ->
                    val results = mutableListOf<Uri>()
                    ranges.zip(outputUris).forEach { (range, outUri) ->
                        PDDocument().use { newDoc ->
                            range.forEach { pageIdx ->
                                if (pageIdx in 0 until sourceDoc.numberOfPages) {
                                    newDoc.importPage(sourceDoc.getPage(pageIdx))
                                }
                            }
                            context.contentResolver.openOutputStream(outUri, "wt")?.use { out ->
                                newDoc.save(out)
                            }
                            results.add(outUri)
                        }
                    }
                    AppResult.Success(results)
                }
            } ?: AppResult.Error("Cannot open source")
        }.getOrElse { AppResult.Error(it.message ?: "Split failed", it) }
    }

    fun saveCopy(sourceUri: Uri, outputUri: Uri): AppResult<Unit> {
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    context.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                        doc.save(out)
                    } ?: return AppResult.Error("Cannot open output")
                }
            } ?: return AppResult.Error("Cannot open source")
            AppResult.Success(Unit)
        }.getOrElse { AppResult.Error(it.message ?: "Save failed", it) }
    }

    private fun saveToUri(doc: PDDocument, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            doc.save(out)
        } ?: throw IllegalStateException("Cannot write to $uri")
    }

    fun getPageCount(uri: Uri): Int {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { it.numberOfPages }
        } ?: 0
    }

    fun getPageSize(uri: Uri, pageIndex: Int): PDRectangle? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                if (pageIndex in 0 until doc.numberOfPages) {
                    doc.getPage(pageIndex).mediaBox
                } else null
            }
        }
    }
}
