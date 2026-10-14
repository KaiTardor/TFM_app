package com.example.besharp.data.privacy

import com.example.besharp.domain.model.AnalysisCapability
import com.example.besharp.domain.model.AnalysisInputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConsentRecordTest {
    private val gemini = target("gemini", "gemini")
    private val customA = target("custom:a", "fingerprint-a")
    private val customB = target("custom:b", "fingerprint-b")

    @Test
    fun consentCoversAnyPreviouslyAcceptedSubset() {
        val record = RemoteConsentRecord(
            RemoteAnalysisConsentStore.CURRENT_POLICY_VERSION,
            acceptedAt = 1L,
            destinationKeys = setOf(gemini.key, customA.key)
        )

        assertTrue(record.matches(setOf(gemini, customA)))
        assertTrue(record.matches(setOf(gemini)))
        assertFalse(record.matches(setOf(gemini, customB)))
        assertTrue(record.matches(setOf(customA)))
        assertFalse(record.matches(setOf(customB)))
    }

    @Test
    fun oldPolicyNeverCoversAWorkerDestination() {
        val record = RemoteConsentRecord(2, 1L, setOf(gemini.key))

        assertFalse(record.matches(setOf(gemini)))
        assertFalse(record.matches(emptySet()))
    }

    @Test
    fun distinguishesOriginalFromNormalizedInput() {
        val provenance = target(
            providerId = "openai:content-provenance",
            fingerprint = "openai",
            capability = AnalysisCapability.CONTENT_PROVENANCE,
            inputType = AnalysisInputType.ORIGINAL_FILE
        )
        val visual = target("openai:visual", "openai")

        assertFalse(provenance.key == visual.key)
    }

    private fun target(
        providerId: String,
        fingerprint: String,
        capability: AnalysisCapability = AnalysisCapability.VISUAL_ANALYSIS,
        inputType: AnalysisInputType = AnalysisInputType.NORMALIZED_IMAGE
    ) = RemoteConsentTarget(providerId, fingerprint, capability, inputType)
}
