package com.example.besharp.domain.model

enum class AnalysisVerdict {
    LIKELY_AUTHENTIC,
    INCONCLUSIVE,
    LIKELY_SYNTHETIC
}

enum class EvidenceType {
    VISUAL_ESTIMATE,
    C2PA_CREDENTIAL,
    SYNTHID_WATERMARK,
    METADATA_HINT,
    LOCAL_CLASSIFIER
}

enum class EvidenceOutcome {
    DETECTED,
    NOT_DETECTED,
    INCONCLUSIVE,
    NOT_CHECKED
}

enum class VerificationLevel {
    LOCALLY_VERIFIED,
    REMOTE_VERIFIED,
    PROVIDER_REPORTED,
    VISUAL_ESTIMATE
}

enum class EvidenceClaim {
    AI_GENERATION,
    AI_EDITING,
    CAMERA_CAPTURE,
    UNKNOWN
}

enum class AnalysisCapability {
    VISUAL_ANALYSIS,
    CONTENT_PROVENANCE,
    LOCAL_CLASSIFIER
}

enum class AnalysisInputType {
    ORIGINAL_FILE,
    NORMALIZED_IMAGE
}

enum class FinalResolution {
    AI_GENERATED_OR_EDITED,
    POSSIBLY_SYNTHETIC,
    VERIFIED_CAPTURE,
    NO_SUFFICIENT_EVIDENCE,
    INCONCLUSIVE
}

enum class AnalysisOrigin {
    DIRECT_PROVIDER,
    LOCAL_ANALYSIS
}

data class ProviderDescriptor(
    val providerId: String,
    val displayName: String,
    val modelId: String,
    val evidenceType: EvidenceType,
    val isRemote: Boolean,
    val capability: AnalysisCapability = AnalysisCapability.VISUAL_ANALYSIS,
    val inputType: AnalysisInputType = AnalysisInputType.NORMALIZED_IMAGE
)

data class EvidenceSignal(
    val signalId: String,
    val type: EvidenceType,
    val outcome: EvidenceOutcome,
    val verificationLevel: VerificationLevel,
    val source: String,
    val claim: EvidenceClaim = EvidenceClaim.UNKNOWN,
    val issuer: String? = null,
    val model: String? = null,
    val generatedAt: Long? = null,
    val explanation: String? = null,
    val validationState: String? = null
) {
    init {
        require(signalId.isNotBlank()) { "El identificador de señal no puede estar vacio" }
        require(source.isNotBlank()) { "La fuente de la señal no puede estar vacia" }
    }
}

data class AuthenticityResult(
    val provider: ProviderDescriptor,
    val verdict: AnalysisVerdict,
    val syntheticProbability: Int?,
    val summary: String,
    val indicators: List<String>,
    val analyzedAt: Long,
    val durationMillis: Long,
    val origin: AnalysisOrigin = AnalysisOrigin.DIRECT_PROVIDER,
    val verificationLevel: VerificationLevel = VerificationLevel.VISUAL_ESTIMATE,
    val evidenceSignals: List<EvidenceSignal> = emptyList()
) {
    init {
        require(syntheticProbability == null || syntheticProbability in 0..100) {
            "La probabilidad debe estar entre 0 y 100"
        }
        require(summary.isNotBlank()) { "El resumen no puede estar vacio" }
        require(durationMillis >= 0) { "La duracion no puede ser negativa" }
    }
}
