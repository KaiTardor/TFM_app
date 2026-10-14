package com.example.besharp.ui.common

// Convierte estados y evidencias en textos para la interfaz.

import com.example.besharp.domain.model.AnalysisVerdict
import com.example.besharp.domain.model.EvidenceOutcome
import com.example.besharp.domain.model.EvidenceType
import com.example.besharp.domain.model.FinalResolution
import com.example.besharp.domain.model.VerificationLevel
import com.example.besharp.ui.model.AnalysisJobState
import com.example.besharp.ui.model.AnalysisJobUi
import com.example.besharp.ui.model.AssessmentState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun resolutionLabel(
    resolution: FinalResolution?,
    assessmentState: AssessmentState? = null
): String = when (resolution) {
    FinalResolution.AI_GENERATED_OR_EDITED -> "GENERADA O EDITADA CON IA"
    FinalResolution.POSSIBLY_SYNTHETIC -> "POSIBLE CONTENIDO SINTETICO"
    FinalResolution.VERIFIED_CAPTURE -> "ORIGEN DE CAPTURA VERIFICADO"
    FinalResolution.NO_SUFFICIENT_EVIDENCE -> "SIN INDICIOS SUFICIENTES"
    FinalResolution.INCONCLUSIVE -> "RESULTADO INCONCLUSO"
    null -> if (assessmentState == AssessmentState.COMPLETED) {
        "RESOLUCION NO DISPONIBLE"
    } else {
        "EVALUACION EN CURSO"
    }
}

fun evidenceTypeLabel(type: EvidenceType): String = when (type) {
    EvidenceType.VISUAL_ESTIMATE -> "Estimacion visual"
    EvidenceType.C2PA_CREDENTIAL -> "C2PA"
    EvidenceType.SYNTHID_WATERMARK -> "SynthID"
    EvidenceType.METADATA_HINT -> "Metadatos"
    EvidenceType.LOCAL_CLASSIFIER -> "Clasificador local"
}

fun outcomeLabel(outcome: EvidenceOutcome): String = when (outcome) {
    EvidenceOutcome.DETECTED -> "detectado"
    EvidenceOutcome.NOT_DETECTED -> "no detectado"
    EvidenceOutcome.INCONCLUSIVE -> "inconcluso"
    EvidenceOutcome.NOT_CHECKED -> "no comprobado"
}

fun verificationLabel(level: VerificationLevel): String = when (level) {
    VerificationLevel.LOCALLY_VERIFIED -> "Verificado localmente"
    VerificationLevel.REMOTE_VERIFIED -> "Verificado remotamente"
    VerificationLevel.PROVIDER_REPORTED -> "Reportado por el proveedor"
    VerificationLevel.VISUAL_ESTIMATE -> "Estimacion visual"
}

fun providerStatusLabel(entry: AnalysisJobUi): String = when (entry.state) {
    AnalysisJobState.QUEUED -> "En cola"
    AnalysisJobState.RUNNING -> "En curso"
    AnalysisJobState.FAILED -> "Error parcial"
    AnalysisJobState.CANCELLED -> "Cancelado"
    AnalysisJobState.SUCCEEDED -> when (entry.result?.verdict) {
        AnalysisVerdict.LIKELY_SYNTHETIC -> "Indicios sinteticos"
        AnalysisVerdict.LIKELY_AUTHENTIC -> "Indicios visuales bajos"
        AnalysisVerdict.INCONCLUSIVE -> "Sin conclusion individual"
        null -> "Completado"
    }
    AnalysisJobState.UNKNOWN -> "Estado desconocido"
}

private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

fun formatAnalysisDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(dateFormat)
