package com.example.besharp.domain.model

data class GalleryPhoto(
    val photoId: Long,
    val contentUri: String,
    val dateTaken: Long,
    val displayName: String,
    val mimeType: String?
)

data class AnalysisHistoryEntry(
    val jobId: Long,
    val providerName: String,
    val status: String,
    val errorMessage: String?,
    val errorCategory: String?,
    val httpStatus: Int?,
    val result: AuthenticityResult?
)

data class AssessmentHistoryEntry(
    val assessmentId: Long,
    val imageUri: String,
    val imageDisplayName: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val finalResolution: FinalResolution?,
    val summary: String?,
    val reason: String?,
    val policyVersion: Int?,
    val conflictingSignalIds: List<String>,
    val jobs: List<AnalysisHistoryEntry>
)
