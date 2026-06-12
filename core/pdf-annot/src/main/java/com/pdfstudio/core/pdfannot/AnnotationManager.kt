package com.pdfstudio.core.pdfannot

import android.graphics.RectF
import com.pdfstudio.core.pdfannot.model.AnnotationType
import com.pdfstudio.core.pdfannot.model.PdfAnnotation
import com.pdfstudio.core.storage.PdfDatabase
import com.pdfstudio.core.storage.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationManager @Inject constructor(
    private val database: PdfDatabase,
) {
    val undoStack = UndoStack()

    fun observeAnnotations(documentUri: String): Flow<List<PdfAnnotation>> {
        return database.annotationDao().observeAnnotations(documentUri).map { list ->
            list.map { entity ->
                PdfAnnotation.fromEntity(
                    id = entity.id,
                    documentUri = entity.documentUri,
                    pageIndex = entity.pageIndex,
                    type = entity.type,
                    color = entity.color,
                    payload = entity.payload,
                    createdAt = entity.createdAt,
                )
            }
        }
    }

    suspend fun getForPage(documentUri: String, pageIndex: Int): List<PdfAnnotation> {
        return database.annotationDao().getForPage(documentUri, pageIndex).map { entity ->
            PdfAnnotation.fromEntity(
                id = entity.id,
                documentUri = entity.documentUri,
                pageIndex = entity.pageIndex,
                type = entity.type,
                color = entity.color,
                payload = entity.payload,
                createdAt = entity.createdAt,
            )
        }
    }

    suspend fun addHighlight(
        documentUri: String,
        pageIndex: Int,
        rect: RectF,
        color: Int,
        type: AnnotationType = AnnotationType.HIGHLIGHT,
    ): PdfAnnotation {
        val annotation = PdfAnnotation(
            documentUri = documentUri,
            pageIndex = pageIndex,
            type = type,
            color = color,
            normalizedLeft = rect.left,
            normalizedTop = rect.top,
            normalizedRight = rect.right,
            normalizedBottom = rect.bottom,
        )
        return insert(annotation)
    }

    suspend fun addInk(
        documentUri: String,
        pageIndex: Int,
        strokes: List<List<Pair<Float, Float>>>,
        color: Int,
    ): PdfAnnotation {
        val annotation = PdfAnnotation(
            documentUri = documentUri,
            pageIndex = pageIndex,
            type = AnnotationType.INK,
            color = color,
            normalizedLeft = 0f,
            normalizedTop = 0f,
            normalizedRight = 1f,
            normalizedBottom = 1f,
            inkPoints = strokes,
        )
        return insert(annotation)
    }

    suspend fun addFreeText(
        documentUri: String,
        pageIndex: Int,
        rect: RectF,
        text: String,
        color: Int,
    ): PdfAnnotation {
        val annotation = PdfAnnotation(
            documentUri = documentUri,
            pageIndex = pageIndex,
            type = AnnotationType.FREE_TEXT,
            color = color,
            normalizedLeft = rect.left,
            normalizedTop = rect.top,
            normalizedRight = rect.right,
            normalizedBottom = rect.bottom,
            text = text,
        )
        return insert(annotation)
    }

    suspend fun addStamp(
        documentUri: String,
        pageIndex: Int,
        rect: RectF,
        imageBase64: String,
    ): PdfAnnotation {
        val annotation = PdfAnnotation(
            documentUri = documentUri,
            pageIndex = pageIndex,
            type = AnnotationType.STAMP,
            color = 0,
            normalizedLeft = rect.left,
            normalizedTop = rect.top,
            normalizedRight = rect.right,
            normalizedBottom = rect.bottom,
            imageBase64 = imageBase64,
        )
        return insert(annotation)
    }

    private suspend fun insert(annotation: PdfAnnotation, recordUndo: Boolean = true): PdfAnnotation {
        val id = database.annotationDao().insert(
            AnnotationEntity(
                documentUri = annotation.documentUri,
                pageIndex = annotation.pageIndex,
                type = annotation.type.name,
                color = annotation.color,
                payload = annotation.toPayload(),
                createdAt = annotation.createdAt,
            )
        )
        val saved = annotation.copy(id = id)
        if (recordUndo) {
            undoStack.push(UndoAction.Added(saved))
        }
        return saved
    }

    suspend fun remove(annotation: PdfAnnotation) {
        database.annotationDao().deleteById(annotation.id)
        undoStack.push(UndoAction.Removed(annotation))
    }

    suspend fun undo(): Boolean {
        when (val action = undoStack.pop()) {
            is UndoAction.Added -> {
                database.annotationDao().deleteById(action.annotation.id)
                return true
            }
            is UndoAction.Removed -> {
                insert(action.annotation.copy(id = 0), recordUndo = false)
                return true
            }
            null -> return false
        }
    }

    suspend fun getAll(documentUri: String): List<PdfAnnotation> {
        return database.annotationDao().getAll(documentUri).map { entity ->
            PdfAnnotation.fromEntity(
                id = entity.id,
                documentUri = entity.documentUri,
                pageIndex = entity.pageIndex,
                type = entity.type,
                color = entity.color,
                payload = entity.payload,
                createdAt = entity.createdAt,
            )
        }
    }
}
