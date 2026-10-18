package com.example.besharp.data.privacy

import com.example.besharp.data.analysis.AnalysisProviderResolver
import com.example.besharp.data.analysis.OpenAiProvenanceProvider
import com.example.besharp.domain.model.AnalysisCapability
import com.example.besharp.domain.model.AnalysisInputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConsentRecordTest {
    private val openAi = target(
        AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID,
        "${AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID}:${OpenAiProvenanceProvider.MODEL_ID}",
        AnalysisCapability.CONTENT_PROVENANCE,
        AnalysisInputType.ORIGINAL_FILE
    )
    private val customA = target("custom:a", "fingerprint-a")
    private val customB = target("custom:b", "fingerprint-b")

    @Test
    fun consentCoversAnyPreviouslyAcceptedSubset() {
        val record = RemoteConsentRecord(
            RemoteAnalysisConsentStore.CURRENT_POLICY_VERSION,
            acceptedAt = 1L,
            destinationKeys = setOf(openAi.key, customA.key)
        )

        assertTrue(record.matches(setOf(openAi, customA)))
        assertTrue(record.matches(setOf(openAi)))
        assertFalse(record.matches(setOf(openAi, customB)))
        assertTrue(record.matches(setOf(customA)))
        assertFalse(record.matches(setOf(customB)))
    }

    @Test
    fun oldPolicyNeverCoversAWorkerDestination() {
        val record = RemoteConsentRecord(2, 1L, setOf(openAi.key))

        assertFalse(record.matches(setOf(openAi)))
        assertFalse(record.matches(emptySet()))
    }

    @Test
    fun distinguishesOriginalFromNormalizedInput() {
        val provenance = target(
            providerId = openAi.providerId,
            fingerprint = openAi.destinationFingerprint,
            capability = AnalysisCapability.CONTENT_PROVENANCE,
            inputType = AnalysisInputType.ORIGINAL_FILE
        )
        val visual = target(openAi.providerId, openAi.destinationFingerprint)

        assertFalse(provenance.key == visual.key)
    }

    private fun target(
        providerId: String,
        fingerprint: String,
        capability: AnalysisCapability = AnalysisCapability.VISUAL_ANALYSIS,
        inputType: AnalysisInputType = AnalysisInputType.NORMALIZED_IMAGE
    ) = RemoteConsentTarget(providerId, fingerprint, capability, inputType)
}
