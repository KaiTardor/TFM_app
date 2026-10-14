package com.example.besharp.ui.model

import com.example.besharp.domain.model.AuthenticityResult
import com.example.besharp.domain.model.FinalResolution

enum class AnalysisJobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
}

enum class AssessmentState {
    PREPARING,
    ACTIVE,
    COMPLETED,
    UNKNOWN
}

data class AnalysisJobUi(
    val jobId: Long,
    val providerName: String,
    val state: AnalysisJobState,
    val errorMessage: String?,
    val result: AuthenticityResult?
)

data class AssessmentUi(
    val assessmentId: Long,
    val imageUri: String,
    val imageDisplayName: String,
    val state: AssessmentState,
    val createdAt: Long,
    val completedAt: Long?,
    val finalResolution: FinalResolution?,
    val summary: String?,
    val reason: String?,
    val policyVersion: Int?,
    val conflictingSignalIds: List<String>,
    val jobs: List<AnalysisJobUi>
) {
    val signalCount: Int get() = jobs.sumOf { it.result?.evidenceSignals?.size ?: 0 }
}

internal fun latestAssessmentForImage(assessments: List<AssessmentUi>, imageUri: String): AssessmentUi? =
    assessments.asSequence()
        .filter { it.imageUri == imageUri }
        .maxWithOrNull(
            compareBy<AssessmentUi> { it.state == AssessmentState.COMPLETED }
                .thenBy {
                    if (it.state == AssessmentState.COMPLETED) it.completedAt ?: it.createdAt
                    else it.createdAt
                }
        )
