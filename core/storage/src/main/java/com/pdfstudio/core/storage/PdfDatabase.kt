package com.pdfstudio.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pdfstudio.core.storage.dao.AnnotationDao
import com.pdfstudio.core.storage.dao.RecentFileDao
import com.pdfstudio.core.storage.entity.AnnotationEntity
import com.pdfstudio.core.storage.entity.RecentFileEntity

@Database(
    entities = [RecentFileEntity::class, AnnotationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PdfDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun annotationDao(): AnnotationDao
}
