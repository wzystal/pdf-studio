package com.pdfstudio.core.pdfannot

import com.pdfstudio.core.pdfannot.model.PdfAnnotation

class UndoStack(private val maxSize: Int = 50) {
    private val stack = ArrayDeque<UndoAction>()

    fun push(action: UndoAction) {
        stack.addLast(action)
        while (stack.size > maxSize) {
            stack.removeFirst()
        }
    }

    fun pop(): UndoAction? = stack.removeLastOrNull()

    fun clear() = stack.clear()

    fun isEmpty(): Boolean = stack.isEmpty()
}

sealed class UndoAction {
    data class Added(val annotation: PdfAnnotation) : UndoAction()
    data class Removed(val annotation: PdfAnnotation) : UndoAction()
}
