package com.example.besharp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_assessments",
    indices = [
        Index(value = ["imageUri"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class ImageAssessmentEntity(
    @PrimaryKey(autoGenerate = true)
    val assessmentId: Long = 0,
    val imageUri: String,
    val imageDisplayName: String,
    val status: String = ImageAssessmentStatus.PREPARING,
    val expectedJobCount: Int,
    val createdAt: Long,
    val completedAt: Long? = null,
    val finalResolution: String? = null,
    val summary: String? = null,
    val resolutionReason: String? = null,
    val resolutionPolicyVersion: Int? = null,
    val decisiveSignalIdsJson: String = "[]",
    val conflictingSignalIdsJson: String = "[]"
) {
    init {
        require(expectedJobCount > 0) { "Una evaluacion debe contener al menos un trabajo" }
    }
}

object ImageAssessmentStatus {
    const val PREPARING = "PREPARING"
    const val ACTIVE = "ACTIVE"
    const val COMPLETED = "COMPLETED"
}
