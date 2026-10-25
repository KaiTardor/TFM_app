package com.example.besharp.domain.resolution

import com.example.besharp.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionEngineTest {

    private val engine = ResolutionEngine()

    @Test
    fun `resolve returns INCONCLUSIVE when no jobs and no signals`() {
        val input = ResolutionInput(
            completedJobCount = 1,
            failedJobCount = 0,
            cancelledJobCount = 0,
            evidenceSignals = emptyList(),
            visualEstimates = emptyList()
        )
        val result = engine.resolve(input)
        assertEquals(FinalResolution.INCONCLUSIVE, result.resolution)
    }

    @Test
    fun `resolve returns AI_GENERATED_OR_EDITED when synthetic signal is detected`() {
        val syntheticSignal = EvidenceSignal(
            signalId = "sig-1",
            type = EvidenceType.SYNTHID_WATERMARK,
            outcome = EvidenceOutcome.DETECTED,
            verificationLevel = VerificationLevel.LOCALLY_VERIFIED,
            source = "test"
        )
        val input = ResolutionInput(
            completedJobCount = 1,
            failedJobCount = 0,
            cancelledJobCount = 0,
            evidenceSignals = listOf(syntheticSignal),
            visualEstimates = emptyList()
        )
        val result = engine.resolve(input)
        assertEquals(FinalResolution.AI_GENERATED_OR_EDITED, result.resolution)
        assertEquals(listOf("sig-1"), result.decisiveSignalIds)
    }

    @Test
    fun `resolve returns VERIFIED_CAPTURE when camera credential is valid`() {
        val captureSignal = EvidenceSignal(
            signalId = "sig-2",
            type = EvidenceType.C2PA_CREDENTIAL,
            outcome = EvidenceOutcome.DETECTED,
            verificationLevel = VerificationLevel.LOCALLY_VERIFIED,
            source = "test",
            claim = EvidenceClaim.CAMERA_CAPTURE,
            validationState = "trusted"
        )
        val input = ResolutionInput(
            completedJobCount = 1,
            failedJobCount = 0,
            cancelledJobCount = 0,
            evidenceSignals = listOf(captureSignal),
            visualEstimates = emptyList()
        )
        val result = engine.resolve(input)
        assertEquals(FinalResolution.VERIFIED_CAPTURE, result.resolution)
        assertEquals(listOf("sig-2"), result.decisiveSignalIds)
    }

    @Test
    fun `resolve returns POSSIBLY_SYNTHETIC when visual estimate is highly synthetic`() {
        val estimate = VisualEstimate(
            isLikelySynthetic = true,
            syntheticProbability = 95,
            isInconclusive = false
        )
        val input = ResolutionInput(
            completedJobCount = 1,
            failedJobCount = 0,
            cancelledJobCount = 0,
            evidenceSignals = emptyList(),
            visualEstimates = listOf(estimate)
        )
        val result = engine.resolve(input)
        assertEquals(FinalResolution.POSSIBLY_SYNTHETIC, result.resolution)
    }

    @Test
    fun `provider reported SynthID is not treated as verified provenance`() {
        val signal = EvidenceSignal(
            signalId = "reported-synthid",
            type = EvidenceType.SYNTHID_WATERMARK,
            outcome = EvidenceOutcome.DETECTED,
            verificationLevel = VerificationLevel.PROVIDER_REPORTED,
            source = "Custom",
            claim = EvidenceClaim.AI_GENERATION
        )

        val result = engine.resolve(
            ResolutionInput(evidenceSignals = listOf(signal), completedJobCount = 1)
        )

        assertEquals(FinalResolution.POSSIBLY_SYNTHETIC, result.resolution)
        assertEquals(listOf("reported-synthid"), result.decisiveSignalIds)
    }

    @Test
    fun `provider reported C2PA is not treated as verified provenance`() {
        val signal = EvidenceSignal(
            signalId = "reported-c2pa",
            type = EvidenceType.C2PA_CREDENTIAL,
            outcome = EvidenceOutcome.DETECTED,
            verificationLevel = VerificationLevel.PROVIDER_REPORTED,
            source = "Custom",
            claim = EvidenceClaim.AI_GENERATION
        )

        val result = engine.resolve(
            ResolutionInput(evidenceSignals = listOf(signal), completedJobCount = 1)
        )

        assertEquals(FinalResolution.POSSIBLY_SYNTHETIC, result.resolution)
    }

    @Test
    fun `invalid remote C2PA cannot confirm generation`() {
        val signal = EvidenceSignal(
            signalId = "invalid-c2pa",
            type = EvidenceType.C2PA_CREDENTIAL,
            outcome = EvidenceOutcome.DETECTED,
            verificationLevel = VerificationLevel.REMOTE_VERIFIED,
            source = "OpenAI",
            claim = EvidenceClaim.AI_GENERATION,
            validationState = "invalid"
        )

        val result = engine.resolve(
            ResolutionInput(evidenceSignals = listOf(signal), completedJobCount = 1)
        )

        assertEquals(FinalResolution.INCONCLUSIVE, result.resolution)
    }
}
