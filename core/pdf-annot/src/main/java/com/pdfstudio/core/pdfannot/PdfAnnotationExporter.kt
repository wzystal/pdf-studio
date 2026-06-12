package com.pdfstudio.core.pdfannot

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.pdfstudio.core.common.result.AppResult
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfannot.model.PdfAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfAnnotationExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val annotationManager: AnnotationManager,
) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun exportWithAnnotations(
        sourceUri: Uri,
        outputUri: Uri,
        documentUri: String,
    ): AppResult<Unit> {
        return try {
            val annotations = annotationManager.getAll(documentUri)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    annotations.groupBy { it.pageIndex }.forEach { (pageIndex, pageAnnots) ->
                        if (pageIndex in 0 until doc.numberOfPages) {
                            val page = doc.getPage(pageIndex)
                            pageAnnots.forEach { applyAnnotation(doc, page, pageIndex, it) }
                        }
                    }
                    context.contentResolver.openOutputStream(outputUri, "wt")?.use { out ->
                        doc.save(out)
                    } ?: return AppResult.Error("Cannot write output")
                }
            } ?: return AppResult.Error("Cannot read source")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Export failed", e)
        }
    }

    private fun applyAnnotation(
        doc: PDDocument,
        page: PDPage,
        pageIndex: Int,
        annotation: PdfAnnotation,
    ) {
        val mediaBox = page.mediaBox
        when (annotation.type) {
            AnnotationType.HIGHLIGHT,
            AnnotationType.UNDERLINE,
            AnnotationType.STRIKETHROUGH,
            -> {
                val subtype = when (annotation.type) {
                    AnnotationType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    AnnotationType.STRIKETHROUGH -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                    else -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                }
                val rect = toPdfRect(mediaBox, annotation)
                val markup = PDAnnotationTextMarkup(subtype)
                markup.rectangle = rect
                markup.color = colorComponents(annotation.color)
                page.annotations.add(markup)
            }
            AnnotationType.FREE_TEXT -> {
                val rect = toPdfRect(mediaBox, annotation)
                val textAnnot = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText().apply {
                    rectangle = rect
                    contents = annotation.text ?: ""
                }
                page.annotations.add(textAnnot)
            }
            AnnotationType.INK -> {
                drawInk(doc, page, mediaBox, annotation)
            }
            AnnotationType.STAMP -> {
                drawStamp(doc, page, mediaBox, annotation)
            }
        }
    }

    private fun drawInk(
        doc: PDDocument,
        page: PDPage,
        mediaBox: PDRectangle,
        annotation: PdfAnnotation,
    ) {
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val r = ((annotation.color shr 16) and 0xFF) / 255f
            val g = ((annotation.color shr 8) and 0xFF) / 255f
            val b = (annotation.color and 0xFF) / 255f
            cs.setStrokingColor(r, g, b)
            cs.setLineWidth(2f)
            annotation.inkPoints.forEach { stroke ->
                if (stroke.isEmpty()) return@forEach
                val first = stroke.first()
                cs.moveTo(first.first * mediaBox.width, mediaBox.height - first.second * mediaBox.height)
                stroke.drop(1).forEach { (x, y) ->
                    cs.lineTo(x * mediaBox.width, mediaBox.height - y * mediaBox.height)
                }
                cs.stroke()
            }
        }
    }

    private fun drawStamp(
        doc: PDDocument,
        page: PDPage,
        mediaBox: PDRectangle,
        annotation: PdfAnnotation,
    ) {
        val base64 = annotation.imageBase64 ?: return
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val rect = toPdfRect(mediaBox, annotation)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val image = JPEGFactory.createFromImage(doc, bitmap)
            cs.drawImage(image, rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
        }
        bitmap.recycle()
    }

    private fun toPdfRect(mediaBox: PDRectangle, annotation: PdfAnnotation): PDRectangle {
        val left = annotation.normalizedLeft * mediaBox.width
        val right = annotation.normalizedRight * mediaBox.width
        val top = mediaBox.height - annotation.normalizedTop * mediaBox.height
        val bottom = mediaBox.height - annotation.normalizedBottom * mediaBox.height
        return PDRectangle(
            minOf(left, right),
            minOf(bottom, top),
            kotlin.math.abs(right - left),
            kotlin.math.abs(top - bottom),
        )
    }

    private fun colorComponents(color: Int): com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(
            floatArrayOf(r, g, b),
            com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE,
        )
    }
}
