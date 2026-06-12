package com.pdfstudio.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pdfstudio.feature.filelist.FileListFragment
import com.pdfstudio.feature.reader.ReaderActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), FileListFragment.Callback {

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { openReader(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, FileListFragment.newInstance())
                .commit()
        }
    }

    override fun onOpenPdfPicker() {
        openPdfLauncher.launch(arrayOf("application/pdf"))
    }

    override fun onOpenRecent(uri: String) {
        openReader(Uri.parse(uri))
    }

    private fun openReader(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Uri may not support persistable permission (e.g. recent list replay).
        }
        startActivity(ReaderActivity.createIntent(this, uri))
    }
}
