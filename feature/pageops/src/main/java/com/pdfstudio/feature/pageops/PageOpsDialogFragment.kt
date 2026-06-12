package com.pdfstudio.feature.pageops

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class PageOpsDialogFragment : DialogFragment() {

    interface Callback {
        fun onRotatePage(degrees: Int)
        fun onDeletePage()
        fun onMergePdfs()
        fun onSplitPdf()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is Callback) {
            throw IllegalStateException("Host must implement PageOpsDialogFragment.Callback")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.page_operations)
            .setItems(
                arrayOf(
                    getString(R.string.rotate_90),
                    getString(R.string.rotate_180),
                    getString(R.string.delete_page),
                    getString(R.string.merge_pdfs),
                    getString(R.string.split_pdf),
                )
            ) { _, which ->
                val callback = activity as Callback
                when (which) {
                    0 -> callback.onRotatePage(90)
                    1 -> callback.onRotatePage(180)
                    2 -> callback.onDeletePage()
                    3 -> callback.onMergePdfs()
                    4 -> callback.onSplitPdf()
                }
            }
            .create()

    companion object {
        fun newInstance() = PageOpsDialogFragment()
    }
}
