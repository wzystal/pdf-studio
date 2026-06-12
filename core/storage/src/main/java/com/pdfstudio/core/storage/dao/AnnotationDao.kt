package com.pdfstudio.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.pdfstudio.core.storage.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE documentUri = :documentUri ORDER BY pageIndex, createdAt")
    fun observeAnnotations(documentUri: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getForPage(documentUri: String, pageIndex: Int): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE documentUri = :documentUri")
    suspend fun getAll(documentUri: String): List<AnnotationEntity>

    @Insert
    suspend fun insert(entity: AnnotationEntity): Long

    @Delete
    suspend fun delete(entity: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM annotations WHERE documentUri = :documentUri")
    suspend fun deleteAllForDocument(documentUri: String)
}
