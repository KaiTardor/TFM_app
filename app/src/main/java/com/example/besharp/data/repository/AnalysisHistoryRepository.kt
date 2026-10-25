package com.example.besharp.data.repository

// Lee las evaluaciones guardadas para mostrarlas en pantalla.

import com.example.besharp.data.local.dao.AnalysisJobWithResult
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.dao.ImageAssessmentWithJobs
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.util.enumValueOrDefault
import com.example.besharp.data.util.enumValueOrNull
import com.example.besharp.domain.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnalysisHistoryRepository(
    private val dao: AnalysisQueueDao
) {
    fun observeAssessmentHistory(limit: Int = 100): Flow<List<AssessmentHistoryEntry>> =
        dao.observeRecentAssessments(limit).map { rows ->
            rows.map(::toAssessmentEntry)
        }

    fun observeAssessment(assessmentId: Long): Flow<AssessmentHistoryEntry?> =
        dao.observeAssessment(assessmentId).map { it?.let(::toAssessmentEntry) }

    private fun toAssessmentEntry(row: ImageAssessmentWithJobs): AssessmentHistoryEntry {
        val assessment = row.assessment
        return AssessmentHistoryEntry(
            assessmentId = assessment.assessmentId,
            imageUri = assessment.imageUri,
            imageDisplayName = assessment.imageDisplayName,
            status = assessment.status,
            createdAt = assessment.createdAt,
            completedAt = assessment.completedAt,
            finalResolution = assessment.finalResolution?.let { enumValueOrNull<FinalResolution>(it) },
            summary = assessment.summary,
            reason = assessment.resolutionReason,
            policyVersion = assessment.resolutionPolicyVersion,
            conflictingSignalIds = parseStringList(assessment.conflictingSignalIdsJson),
            jobs = row.jobs.mapNotNull { job -> runCatching { toHistoryEntry(job) }.getOrNull() }
        )
    }

    fun observeAnalyzedImageUris(): Flow<Set<String>> =
        dao.observeAnalyzedImageUris().map { it.toSet() }

    private fun toHistoryEntry(row: AnalysisJobWithResult): AnalysisHistoryEntry {
        val job = row.job
        return AnalysisHistoryEntry(
            jobId = job.jobId,
            providerName = job.providerName,
            status = job.status,
            errorMessage = job.errorMessage,
            result = row.result?.let { result ->
                val origin = enumValueOrDefault(result.origin, AnalysisOrigin.DIRECT_PROVIDER)
                AuthenticityResult(
                    provider = ProviderDescriptor(
                        providerId = job.providerId,
                        displayName = job.providerName,
                        modelId = job.modelId,
                        evidenceType = enumValueOrDefault(result.evidenceType, EvidenceType.VISUAL_ESTIMATE),
                        isRemote = origin != AnalysisOrigin.LOCAL_ANALYSIS,
                        capability = enumValueOrDefault(job.capability, AnalysisCapability.VISUAL_ANALYSIS),
                        inputType = enumValueOrDefault(job.inputType, AnalysisInputType.NORMALIZED_IMAGE)
                    ),
                    verdict = enumValueOrDefault(result.verdict, AnalysisVerdict.INCONCLUSIVE),
                    syntheticProbability = result.syntheticProbability,
                    summary = result.summary,
                    indicators = parseStringList(result.indicatorsJson),
                    analyzedAt = result.analyzedAt,
                    durationMillis = result.durationMillis,
                    origin = origin,
                    verificationLevel = enumValueOrDefault(
                        result.verificationLevel,
                        VerificationLevel.VISUAL_ESTIMATE
                    ),
                    evidenceSignals = row.signals.map(EvidenceSignalEntity::toDomain)
                )
            }
        )
    }

    private fun parseStringList(json: String): List<String> = runCatching {
        JsonParser.parseString(json).asJsonArray.map { it.asString }
    }.getOrDefault(emptyList())
}
