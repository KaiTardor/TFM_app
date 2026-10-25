package com.example.besharp.domain.resolution

import com.example.besharp.domain.model.EvidenceSignal
import com.example.besharp.domain.model.FinalResolution

data class FinalAssessment(
    val resolution: FinalResolution,
    val summary: String,
    val reason: String,
    val policyVersion: Int,
    val decisiveSignalIds: List<String>,
    val conflictingSignalIds: List<String> = emptyList()
)

data class ResolutionInput(
    val visualEstimates: List<VisualEstimate> = emptyList(),
    val evidenceSignals: List<EvidenceSignal> = emptyList(),
    val completedJobCount: Int = 0,
    val failedJobCount: Int = 0,
    val cancelledJobCount: Int = 0
) {
    init {
        require(completedJobCount >= 0)
        require(failedJobCount >= 0)
        require(cancelledJobCount >= 0)
    }
}

data class VisualEstimate(
    val syntheticProbability: Int?,
    val isLikelySynthetic: Boolean,
    val isInconclusive: Boolean = false
) {
    init {
        require(syntheticProbability == null || syntheticProbability in 0..100)
    }
}
