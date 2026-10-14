package com.example.besharp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Referencia local a una foto; sus bytes no se guardan en Room. */
@Entity(
    tableName = "photos",
    indices = [Index(value = ["contentUri"], unique = true)]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val photoId: Long = 0,

    val contentUri: String,
    val dateTaken: Long,
    val displayName: String,
    val mimeType: String? = null,
    val isVisible: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)
