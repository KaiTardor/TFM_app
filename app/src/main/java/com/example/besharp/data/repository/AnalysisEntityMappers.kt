package com.example.besharp.data.repository

// Convierte evidencias entre Room y dominio, y describe el destino autorizado de un trabajo.

import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.privacy.RemoteConsentTarget
import com.example.besharp.domain.model.AnalysisCapability
import com.example.besharp.domain.model.AnalysisInputType
import com.example.besharp.domain.model.EvidenceClaim
import com.example.besharp.domain.model.EvidenceOutcome
import com.example.besharp.domain.model.EvidenceSignal
import com.example.besharp.domain.model.EvidenceType
import com.example.besharp.domain.model.VerificationLevel

internal fun EvidenceSignal.toEntity(jobId: Long) = EvidenceSignalEntity(
    signalId = "$jobId:$signalId",
    jobId = jobId,
    type = type.name,
    outcome = outcome.name,
    verificationLevel = verificationLevel.name,
    source = source,
    claim = claim.name,
    issuer = issuer,
    model = model,
    generatedAt = generatedAt,
    explanation = explanation,
    validationState = validationState
)

internal fun EvidenceSignalEntity.toDomain() = EvidenceSignal(
    signalId = signalId,
    type = enumValueOf<EvidenceType>(type),
    outcome = enumValueOf<EvidenceOutcome>(outcome),
    verificationLevel = enumValueOf<VerificationLevel>(verificationLevel),
    source = source,
    claim = enumValueOf<EvidenceClaim>(claim),
    issuer = issuer,
    model = model,
    generatedAt = generatedAt,
    explanation = explanation,
    validationState = validationState
)

internal fun AnalysisExecutionJob.toConsentTarget() = RemoteConsentTarget(
    providerId = providerId,
    destinationFingerprint = destinationFingerprint,
    capability = enumValueOf<AnalysisCapability>(capability),
    inputType = enumValueOf<AnalysisInputType>(inputType)
)
