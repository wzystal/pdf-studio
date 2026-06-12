package com.pdfstudio.feature.editor

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.pdfstudio.feature.editor.databinding.ViewEditorToolbarBinding

class EditorToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val binding = ViewEditorToolbarBinding.inflate(LayoutInflater.from(context), this, true)

    var onModeSelected: ((EditorMode) -> Unit)? = null
    var onUndo: (() -> Unit)? = null

    private val modeButtons: Map<EditorMode, MaterialButton> by lazy {
        mapOf(
            EditorMode.HIGHLIGHT to binding.btnHighlight,
            EditorMode.UNDERLINE to binding.btnUnderline,
            EditorMode.INK to binding.btnInk,
            EditorMode.NOTE to binding.btnNote,
            EditorMode.STAMP to binding.btnSignature,
        )
    }

    private val selectedTint: ColorStateList by lazy {
        ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer),
        )
    }
    private val normalTint: ColorStateList by lazy {
        ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer),
        )
    }

    init {
        orientation = VERTICAL
        binding.btnHighlight.setOnClickListener { selectMode(EditorMode.HIGHLIGHT) }
        binding.btnUnderline.setOnClickListener { selectMode(EditorMode.UNDERLINE) }
        binding.btnInk.setOnClickListener { selectMode(EditorMode.INK) }
        binding.btnNote.setOnClickListener { selectMode(EditorMode.NOTE) }
        binding.btnSignature.setOnClickListener { selectMode(EditorMode.STAMP) }
        binding.btnUndo.setOnClickListener { onUndo?.invoke() }
        setSelectedMode(EditorMode.HIGHLIGHT)
    }

    fun setSelectedMode(mode: EditorMode) {
        val strokePx = (2 * resources.displayMetrics.density).toInt()
        modeButtons.forEach { (editorMode, button) ->
            val selected = editorMode == mode
            button.isSelected = selected
            button.backgroundTintList = if (selected) selectedTint else normalTint
            button.strokeWidth = if (selected) strokePx else 0
            button.alpha = if (selected) 1f else 0.72f
        }
    }

    private fun selectMode(mode: EditorMode) {
        setSelectedMode(mode)
        onModeSelected?.invoke(mode)
    }
}
