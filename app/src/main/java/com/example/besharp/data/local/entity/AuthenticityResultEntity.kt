package com.example.besharp.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * Entidad que representa el resultado de un análisis de autenticidad en la base de datos local.
 */
@Entity(
    tableName = "authenticity_results",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisJobEntity::class,
            parentColumns = ["jobId"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["jobId"], unique = true)]
)

// Definición de la clase de datos para un resultado de autenticidad.
data class AuthenticityResultEntity(
    @PrimaryKey(autoGenerate = true)
    val resultId: Long = 0,
    val jobId: Long,
    val verdict: String,
    val syntheticProbability: Int?,
    val evidenceType: String,
    @ColumnInfo(defaultValue = "'VISUAL_ESTIMATE'") val verificationLevel: String,
    val summary: String,
    val indicatorsJson: String,
    val analyzedAt: Long,
    val durationMillis: Long,
    val origin: String
)
