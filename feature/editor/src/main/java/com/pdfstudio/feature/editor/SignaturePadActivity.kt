package com.pdfstudio.feature.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import com.pdfstudio.feature.editor.databinding.ActivitySignaturePadBinding
import java.io.ByteArrayOutputStream

class SignaturePadActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignaturePadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignaturePadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnClear.setOnClickListener { binding.signaturePad.clear() }
        binding.btnDone.setOnClickListener {
            val bitmap = binding.signaturePad.exportBitmap()
            val base64 = bitmap.toBase64Png()
            bitmap.recycle()
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SIGNATURE_BASE64, base64))
            finish()
        }
    }

    private fun Bitmap.toBase64Png(): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val EXTRA_SIGNATURE_BASE64 = "signature_base64"

        fun createIntent(context: Context) = Intent(context, SignaturePadActivity::class.java)
    }
}
