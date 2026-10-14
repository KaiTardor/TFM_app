package com.example.besharp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.ForeignKey

/**
 * Entidad que representa un trabajo de análisis en la base de datos local.
 */
@Entity(
    tableName = "analysis_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ImageAssessmentEntity::class,
            parentColumns = ["assessmentId"],
            childColumns = ["assessmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["assessmentId"]),
        Index(value = ["providerId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)

// Definición de la clase de datos para un trabajo de análisis.
data class AnalysisJobEntity(
    @PrimaryKey(autoGenerate = true)
    val jobId: Long = 0,
    val assessmentId: Long = 0,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    @ColumnInfo(defaultValue = "'VISUAL_ANALYSIS'") val capability: String = "VISUAL_ANALYSIS",
    @ColumnInfo(defaultValue = "'NORMALIZED_IMAGE'") val inputType: String = "NORMALIZED_IMAGE",
    @ColumnInfo(defaultValue = "''") val executionId: String,
    @ColumnInfo(defaultValue = "''") val destinationFingerprint: String,
    val customEndpointUrl: String? = null,
    val customParametersJson: String? = null,
    val status: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val errorCategory: String? = null,
    val httpStatus: Int? = null
)

// Definición de los posibles estados de un trabajo de análisis.
object AnalysisJobStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
