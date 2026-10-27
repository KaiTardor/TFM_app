package com.example.besharp.domain.resolution

// Decide la resolucion final por prioridad de evidencias, sin promediar resultados.

import com.example.besharp.domain.model.*

class ResolutionEngine(
    private val policy: ResolutionPolicy = ResolutionPolicy()
) {
    // Una estimacion visual o senal reportada no basta para confirmar generacion con IA.
    fun resolve(input: ResolutionInput): FinalAssessment {
        val syntheticSignals = input.evidenceSignals.filter(::isConfirmingSyntheticSignal)
        val captureSignals = input.evidenceSignals.filter(::isTrustedCaptureSignal)

        if (syntheticSignals.isNotEmpty()) {
            return FinalAssessment(
                resolution = FinalResolution.AI_GENERATED_OR_EDITED,
                summary = "Se detecto una señal de procedencia de IA.",
                reason = "Una señal de generacion o edicion con IA prevalece sobre las estimaciones visuales.",
                policyVersion = policy.version,
                decisiveSignalIds = syntheticSignals.map(EvidenceSignal::signalId),
                conflictingSignalIds = captureSignals.map(EvidenceSignal::signalId)
            )
        }

        val reportedSyntheticSignals = input.evidenceSignals.filter(::isReportedSyntheticSignal)
        if (reportedSyntheticSignals.isNotEmpty()) {
            return FinalAssessment(
                resolution = FinalResolution.POSSIBLY_SYNTHETIC,
                summary = "Un proveedor reporto una señal asociada a contenido sintetico.",
                reason = "La señal no procede de una verificacion local o remota independiente.",
                policyVersion = policy.version,
                decisiveSignalIds = reportedSyntheticSignals.map(EvidenceSignal::signalId),
                conflictingSignalIds = captureSignals.map(EvidenceSignal::signalId)
            )
        }

        val strongVisualEstimates = input.visualEstimates.filter { estimate ->
            estimate.isLikelySynthetic &&
                (estimate.syntheticProbability == null ||
                    estimate.syntheticProbability >= policy.possibleSyntheticThreshold)
        }
        if (strongVisualEstimates.isNotEmpty()) {
            return FinalAssessment(
                resolution = FinalResolution.POSSIBLY_SYNTHETIC,
                summary = "Uno o varios analizadores encontraron indicios de contenido sintetico.",
                reason = "Existen estimaciones visuales fuertes, pero ninguna señal de procedencia confirmatoria.",
                policyVersion = policy.version,
                decisiveSignalIds = emptyList(),
                conflictingSignalIds = captureSignals.map(EvidenceSignal::signalId)
            )
        }

        if (captureSignals.isNotEmpty()) {
            return FinalAssessment(
                resolution = FinalResolution.VERIFIED_CAPTURE,
                summary = "El origen de captura esta respaldado por una credencial valida.",
                reason = "Se verifico una credencial C2PA confiable de captura sin evidencia sintetica contradictoria.",
                policyVersion = policy.version,
                decisiveSignalIds = captureSignals.map(EvidenceSignal::signalId)
            )
        }

        // La ausencia de señales no permite afirmar que la imagen sea autentica.
        val hasUsableNegativeEvidence = input.evidenceSignals.any {
            it.outcome == EvidenceOutcome.NOT_DETECTED
        }
        val hasConclusiveVisualEstimate = input.visualEstimates.any { !it.isInconclusive }
        if (hasUsableNegativeEvidence || hasConclusiveVisualEstimate) {
            return FinalAssessment(
                resolution = FinalResolution.NO_SUFFICIENT_EVIDENCE,
                summary = "No se encontraron indicios suficientes.",
                reason = "La ausencia de señales detectables no demuestra que la imagen sea autentica.",
                policyVersion = policy.version,
                decisiveSignalIds = emptyList()
            )
        }

        val totalTerminalJobs = input.completedJobCount + input.failedJobCount + input.cancelledJobCount
        val reason = when {
            input.failedJobCount > 0 -> "Los analizadores disponibles fallaron o no devolvieron evidencia util."
            totalTerminalJobs == 0 -> "La evaluacion no contiene resultados."
            else -> "Los resultados disponibles son inconclusos."
        }
        return FinalAssessment(
            resolution = FinalResolution.INCONCLUSIVE,
            summary = "No fue posible alcanzar una conclusion.",
            reason = reason,
            policyVersion = policy.version,
            decisiveSignalIds = emptyList()
        )
    }

    // C2PA debe estar validado y declarar uso de IA; la firma por si sola no confirma generacion.
    private fun isConfirmingSyntheticSignal(signal: EvidenceSignal): Boolean {
        if (signal.outcome != EvidenceOutcome.DETECTED) return false
        return when (signal.type) {
            EvidenceType.SYNTHID_WATERMARK ->
                signal.verificationLevel == VerificationLevel.LOCALLY_VERIFIED ||
                    signal.verificationLevel == VerificationLevel.REMOTE_VERIFIED
            EvidenceType.C2PA_CREDENTIAL ->
                (signal.verificationLevel == VerificationLevel.LOCALLY_VERIFIED ||
                    signal.verificationLevel == VerificationLevel.REMOTE_VERIFIED) &&
                    (signal.validationState.equals("trusted", ignoreCase = true) ||
                        signal.validationState.equals("valid", ignoreCase = true)) &&
                    (signal.claim == EvidenceClaim.AI_GENERATION ||
                        signal.claim == EvidenceClaim.AI_EDITING)
            else -> false
        }
    }

    private fun isReportedSyntheticSignal(signal: EvidenceSignal): Boolean {
        if (signal.outcome != EvidenceOutcome.DETECTED ||
            signal.verificationLevel != VerificationLevel.PROVIDER_REPORTED
        ) return false
        return signal.type == EvidenceType.SYNTHID_WATERMARK ||
            signal.type == EvidenceType.C2PA_CREDENTIAL &&
            (signal.claim == EvidenceClaim.AI_GENERATION || signal.claim == EvidenceClaim.AI_EDITING)
    }

    private fun isTrustedCaptureSignal(signal: EvidenceSignal): Boolean =
        signal.type == EvidenceType.C2PA_CREDENTIAL &&
            signal.outcome == EvidenceOutcome.DETECTED &&
            signal.claim == EvidenceClaim.CAMERA_CAPTURE &&
            signal.validationState.equals("trusted", ignoreCase = true) &&
            (signal.verificationLevel == VerificationLevel.LOCALLY_VERIFIED ||
                signal.verificationLevel == VerificationLevel.REMOTE_VERIFIED)
}
