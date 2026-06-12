package com.pdfstudio.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val type: String,
    val color: Int,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
)
