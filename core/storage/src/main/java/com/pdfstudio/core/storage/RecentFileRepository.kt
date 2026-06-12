package com.pdfstudio.core.storage

import android.net.Uri
import com.pdfstudio.core.storage.entity.RecentFileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentFileRepository @Inject constructor(
    private val database: PdfDatabase,
) {
    fun observeRecentFiles(): Flow<List<RecentFileEntity>> {
        return database.recentFileDao().observeRecentFiles()
    }

    suspend fun recordOpen(uri: Uri, displayName: String, pageCount: Int) {
        database.recentFileDao().upsert(
            RecentFileEntity(
                uri = uri.toString(),
                displayName = displayName,
                pageCount = pageCount,
                lastOpenedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(uri: Uri) {
        database.recentFileDao().delete(uri.toString())
    }
}
