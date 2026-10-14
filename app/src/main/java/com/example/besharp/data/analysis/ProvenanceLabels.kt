package com.example.besharp.data.analysis

import com.example.besharp.domain.model.EvidenceOutcome
import com.example.besharp.domain.model.EvidenceType

// Ambos proveedores guardan los mismos nombres para sus indicadores.
internal fun signalLabel(type: EvidenceType): String = when (type) {
    EvidenceType.C2PA_CREDENTIAL -> "C2PA"
    EvidenceType.SYNTHID_WATERMARK -> "SynthID"
    EvidenceType.VISUAL_ESTIMATE -> "estimacion visual"
    EvidenceType.METADATA_HINT -> "metadatos"
    EvidenceType.LOCAL_CLASSIFIER -> "clasificador local"
}

internal fun outcomeLabel(outcome: EvidenceOutcome): String = when (outcome) {
    EvidenceOutcome.DETECTED -> "detectado"
    EvidenceOutcome.NOT_DETECTED -> "no detectado"
    EvidenceOutcome.INCONCLUSIVE -> "inconcluso"
    EvidenceOutcome.NOT_CHECKED -> "no comprobado"
}
