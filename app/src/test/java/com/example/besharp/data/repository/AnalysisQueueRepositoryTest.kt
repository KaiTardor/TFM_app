package com.example.besharp.data.repository

import com.example.besharp.data.analysis.AnalysisErrorCategory
import com.example.besharp.application.analysis.ReconcileAnalysisJobsUseCase
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.work.AnalysisWorkState
import com.example.besharp.data.privacy.RemoteConsentStore
import com.example.besharp.data.privacy.RemoteConsentTarget
import com.example.besharp.data.privacy.RemoteConsentRecord
import com.example.besharp.domain.model.*
import com.example.besharp.data.local.dao.AnalysisJobWithResult
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.dao.ImageAssessmentWithJobs
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.local.entity.ImageAssessmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisQueueRepositoryTest {
    private val dao = FakeAnalysisQueueDao()
    private val repository = AnalysisQueueRepository(dao)
    private val historyRepository = AnalysisHistoryRepository(dao)
    private val provider = ProviderDescriptor(
        providerId = "detector:test",
        displayName = "Detector",
        modelId = "v1",
        evidenceType = EvidenceType.LOCAL_CLASSIFIER,
        isRemote = false
    )

    private suspend fun readHistory() = historyRepository.observeAssessmentHistory().first().flatMap { it.jobs }

    private suspend fun AnalysisQueueRepository.enqueue(
        imageUri: String, imageDisplayName: String, provider: ProviderDescriptor
    ) = enqueueAssessment(imageUri, imageDisplayName, listOf(AnalysisJobRequest(provider))).jobs.single()

    @Test
    fun finalizationTriggersCleanupOnceIncludingCancellationWithoutAWorker() = runBlocking {
        val cleaned = mutableListOf<Long>()
        val owner = AnalysisQueueRepository(dao, onAssessmentCompleted = { cleaned += it })
        val cancelled = owner.enqueue("content://image/cancelled", "image.jpg", provider)
        owner.cancelAllActive()
        assertEquals(listOf(cancelled.assessmentId), cleaned)
        assertNull(owner.finalizeAssessmentIfReady(cancelled.assessmentId))
        owner.finalizeReadyAssessments()
        assertEquals(listOf(cancelled.assessmentId), cleaned)
    }

    @Test
    fun persistsAndRestoresSuccessfulResult() = runBlocking {
        val queued = repository.enqueue("content://image/1", "imagen.jpg", provider)
        repository.markStarted(queued.jobId)
        repository.saveSuccess(
            queued.jobId,
            AuthenticityResult(
                provider = provider,
                verdict = AnalysisVerdict.LIKELY_SYNTHETIC,
                syntheticProbability = 87,
                summary = "Patrones compatibles con generacion sintetica",
                indicators = listOf("textura", "reflejos"),
                analyzedAt = 123L,
                durationMillis = 45L,
                origin = AnalysisOrigin.LOCAL_ANALYSIS
            )
        )
        repository.finalizeAssessmentIfReady(queued.assessmentId)

        val entry = readHistory().single()
        assertEquals(AnalysisJobStatus.SUCCEEDED, entry.status)
        assertEquals(AnalysisVerdict.LIKELY_SYNTHETIC, entry.result?.verdict)
        assertEquals(listOf("textura", "reflejos"), entry.result?.indicators)
        assertEquals(AnalysisOrigin.LOCAL_ANALYSIS, entry.result?.origin)
        assertEquals(false, entry.result?.provider?.isRemote)
        assertEquals(setOf("content://image/1"), historyRepository.observeAnalyzedImageUris().first())
    }

    @Test
    fun normalizesFailureAndCancelsOnlyActiveJobs() = runBlocking {
        val failed = repository.enqueue("content://image/1", "uno.jpg", provider)
        val cancelled = repository.enqueue("content://image/2", "dos.jpg", provider)
        repository.markStarted(failed.jobId)
        repository.saveFailure(failed.jobId, AnalysisErrorCategory.PROVIDER_UNAVAILABLE, 503)
        repository.cancelAllActive()

        val history = readHistory().associateBy { it.jobId }
        assertEquals(AnalysisJobStatus.FAILED, history.getValue(failed.jobId).status)
        assertEquals(
            "El proveedor de analisis no esta disponible temporalmente (HTTP 503)",
            history.getValue(failed.jobId).errorMessage
        )
        assertEquals(AnalysisJobStatus.CANCELLED, history.getValue(cancelled.jobId).status)
        assertNull(history.getValue(cancelled.jobId).result)
    }

    @Test
    fun cancellationWinsRaceAgainstSuccessfulResult() = runBlocking {
        val queued = repository.enqueue("content://image/race", "race.jpg", provider)
        assertTrue(repository.markStarted(queued.jobId))
        assertTrue(repository.markCancelled(queued.jobId))

        val saved = repository.saveSuccess(
            queued.jobId,
            AuthenticityResult(
                provider = provider,
                verdict = AnalysisVerdict.INCONCLUSIVE,
                syntheticProbability = null,
                summary = "No debe persistirse",
                indicators = emptyList(),
                analyzedAt = 1L,
                durationMillis = 1L
            )
        )

        assertFalse(saved)
        val row = readHistory().single()
        assertEquals(AnalysisJobStatus.CANCELLED, row.status)
        assertNull(row.result)
    }

    @Test
    fun rejectsTransitionsThatDoNotStartFromExpectedState() = runBlocking {
        val queued = repository.enqueue("content://image/state", "state.jpg", provider)

        assertFalse(repository.saveFailure(queued.jobId))
        assertTrue(repository.markStarted(queued.jobId))
        assertFalse(repository.markStarted(queued.jobId))
        assertTrue(repository.saveFailure(
            queued.jobId,
            AnalysisErrorCategory.PROVIDER_UNAVAILABLE,
            503
        ))
        assertFalse(repository.markCancelled(queued.jobId))

        val entry = readHistory().single()
        assertEquals(AnalysisErrorCategory.PROVIDER_UNAVAILABLE.name, entry.errorCategory)
        assertEquals(503, entry.httpStatus)
    }

    @Test
    fun doesNotPersistFreeProviderOrRemoteMessages() = runBlocking {
        val untrustedProvider = provider.copy(displayName = "secret-from-remote")
        val queued = repository.enqueue("content://image/error", "error.jpg", untrustedProvider)
        repository.markStarted(queued.jobId)

        assertTrue(repository.saveFailure(queued.jobId, AnalysisErrorCategory.INVALID_RESPONSE))

        val message = readHistory().single().errorMessage.orEmpty()
        assertEquals("El proveedor de analisis devolvio una respuesta no valida", message)
        assertFalse(message.contains("secret-from-remote"))
    }

    @Test
    fun persistsMultipleSignalsAndFinalizesAssessmentOnce() = runBlocking {
        val queued = repository.enqueue(
            imageUri = "content://image/signals",
            imageDisplayName = "signals.png",
            provider = provider
        )
        val assessmentId = queued.assessmentId
        repository.markStarted(queued.jobId)
        repository.saveSuccess(
            queued.jobId,
            AuthenticityResult(
                provider = provider,
                verdict = AnalysisVerdict.INCONCLUSIVE,
                syntheticProbability = null,
                summary = "Procedencia comprobada",
                indicators = emptyList(),
                analyzedAt = 1L,
                durationMillis = 1L,
                evidenceSignals = listOf(
                    EvidenceSignal(
                        signalId = "c2pa",
                        type = EvidenceType.C2PA_CREDENTIAL,
                        outcome = EvidenceOutcome.DETECTED,
                        verificationLevel = VerificationLevel.REMOTE_VERIFIED,
                        source = "OpenAI",
                        claim = EvidenceClaim.AI_GENERATION,
                        validationState = "trusted"
                    ),
                    EvidenceSignal(
                        signalId = "synthid",
                        type = EvidenceType.SYNTHID_WATERMARK,
                        outcome = EvidenceOutcome.NOT_DETECTED,
                        verificationLevel = VerificationLevel.REMOTE_VERIFIED,
                        source = "OpenAI"
                    )
                )
            )
        )

        val completed = repository.finalizeAssessmentIfReady(assessmentId)
        val restored = readHistory().single().result

        assertEquals(FinalResolution.AI_GENERATED_OR_EDITED, completed?.assessment?.resolution)
        assertEquals(2, restored?.evidenceSignals?.size)
        assertNull(repository.finalizeAssessmentIfReady(assessmentId))
    }

    @Test
    fun createsAllAssessmentJobsBeforeActivatingIt() = runBlocking {
        val secondProvider = provider.copy(providerId = "detector:second", displayName = "Second")

        val queued = repository.enqueueAssessment(
            imageUri = "content://image/atomic",
            imageDisplayName = "atomic.jpg",
            requests = listOf(
                AnalysisJobRequest(provider),
                AnalysisJobRequest(secondProvider)
            )
        )

        val assessment = dao.assessment(queued.assessmentId)
        assertEquals(2, queued.jobs.size)
        assertEquals(2, assessment?.expectedJobCount)
        assertEquals(com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE, assessment?.status)
        assertEquals(2, dao.allJobs().count { it.assessmentId == queued.assessmentId })
    }

    @Test
    fun recoversReadyAssessmentAfterTerminalResultWasAlreadyStored() = runBlocking {
        val queued = repository.enqueue("content://image/recovery", "recovery.jpg", provider)
        repository.markStarted(queued.jobId)
        repository.saveSuccess(
            queued.jobId,
            AuthenticityResult(
                provider = provider,
                verdict = AnalysisVerdict.LIKELY_SYNTHETIC,
                syntheticProbability = 90,
                summary = "Indicios visuales",
                indicators = emptyList(),
                analyzedAt = 1L,
                durationMillis = 1L
            )
        )

        val recovered = repository.finalizeReadyAssessments()

        assertEquals(listOf(queued.assessmentId), recovered.map { it.assessmentId })
        assertEquals(FinalResolution.POSSIBLY_SYNTHETIC, recovered.single().assessment.resolution)
    }

    @Test
    fun inconclusiveCompletedRequestIsNotAnExecutionError() = runBlocking {
        val provenance = provider.copy(capability = AnalysisCapability.CONTENT_PROVENANCE)
        val queued = repository.enqueue("content://image/inconclusive", "image.jpg", provenance)
        repository.markStarted(queued.jobId)
        repository.saveSuccess(queued.jobId, AuthenticityResult(
            provider = provenance, verdict = AnalysisVerdict.INCONCLUSIVE, syntheticProbability = null,
            summary = "Sin señales.", indicators = emptyList(), analyzedAt = 1, durationMillis = 1
        ))
        val completed = repository.finalizeAssessmentIfReady(queued.assessmentId)!!
        assertEquals(FinalResolution.INCONCLUSIVE, completed.assessment.resolution)
        assertFalse(completed.hasExecutionErrors)
    }

    @Test
    fun failedOrCancelledJobsAreExecutionErrorsIndependentOfResolution() = runBlocking {
        val queued = repository.enqueue("content://image/error", "image.jpg", provider)
        repository.markStarted(queued.jobId)
        repository.saveFailure(queued.jobId)
        assertTrue(repository.finalizeAssessmentIfReady(queued.assessmentId)!!.hasExecutionErrors)
    }

    @Test
    fun assessmentCanBeObservedDirectlyAndDisappearsAfterDeletion() = runBlocking {
        val queued = repository.enqueue("content://image/direct", "image.jpg", provider)
        assertEquals(queued.assessmentId, historyRepository.observeAssessment(queued.assessmentId).first()?.assessmentId)
        repository.deleteForImage("content://image/direct")
        assertNull(historyRepository.observeAssessment(queued.assessmentId).first())
    }

    @Test
    fun finishedSystemWorkIsFailedLocallyInsteadOfBeingEnqueuedAgain() = runBlocking {
        val queued = repository.enqueue("content://image/finished", "image.jpg", provider)
        var enqueued = 0
        val scheduler = object : AnalysisScheduler {
            override suspend fun enqueue(jobId: Long) { enqueued++ }
            override suspend fun cancel(jobId: Long) = Unit
            override suspend fun cancelAllRemote() = Unit
            override suspend fun workState(jobId: Long) = AnalysisWorkState.FINISHED
        }
        val consent = object : RemoteConsentStore {
            override fun getCurrentConsent(): RemoteConsentRecord? = null
            override fun hasConsentFor(targets: Set<RemoteConsentTarget>) = true
            override fun hasConsentFor(target: RemoteConsentTarget) = true
            override fun accept(targets: Set<RemoteConsentTarget>): RemoteConsentRecord = error("unused")
            override fun revoke() = Unit
        }
        val reconcile = ReconcileAnalysisJobsUseCase(repository, scheduler, consent)
        assertTrue(reconcile.execute())
        assertTrue(reconcile.execute())
        assertEquals(0, enqueued)
        assertEquals(AnalysisJobStatus.FAILED, repository.getJob(queued.jobId)?.status)
        assertEquals(AnalysisErrorCategory.LOCAL_STORAGE.name, readHistory().single().errorCategory)
    }

    @Test
    fun persistsConflictingSignalsInFinalAssessment() = runBlocking {
        val queued = repository.enqueue("content://image/conflict", "conflict.jpg", provider)
        repository.markStarted(queued.jobId)
        repository.saveSuccess(
            queued.jobId,
            AuthenticityResult(
                provider = provider,
                verdict = AnalysisVerdict.INCONCLUSIVE,
                syntheticProbability = null,
                summary = "Credenciales contradictorias",
                indicators = emptyList(),
                analyzedAt = 1,
                durationMillis = 1,
                evidenceSignals = listOf(
                    EvidenceSignal(
                        signalId = "generated",
                        type = EvidenceType.C2PA_CREDENTIAL,
                        outcome = EvidenceOutcome.DETECTED,
                        verificationLevel = VerificationLevel.REMOTE_VERIFIED,
                        source = "test",
                        claim = EvidenceClaim.AI_GENERATION,
                        validationState = "trusted"
                    ),
                    EvidenceSignal(
                        signalId = "capture",
                        type = EvidenceType.C2PA_CREDENTIAL,
                        outcome = EvidenceOutcome.DETECTED,
                        verificationLevel = VerificationLevel.REMOTE_VERIFIED,
                        source = "test",
                        claim = EvidenceClaim.CAMERA_CAPTURE,
                        validationState = "trusted"
                    )
                )
            )
        )

        repository.finalizeAssessmentIfReady(queued.assessmentId)
        val assessment = historyRepository.observeAssessmentHistory().first().single()

        assertTrue(assessment.conflictingSignalIds.single().endsWith(":capture"))
    }

    private class FakeAnalysisQueueDao : AnalysisQueueDao {
        private val rows = MutableStateFlow<List<AnalysisJobWithResult>>(emptyList())
        private var nextId = 1L
        private var nextAssessmentId = 1L
        private val assessments = mutableMapOf<Long, ImageAssessmentEntity>()

        override suspend fun insertAssessment(assessment: ImageAssessmentEntity): Long {
            val id = nextAssessmentId++
            assessments[id] = assessment.copy(assessmentId = id)
            return id
        }

        override suspend fun insertJob(job: AnalysisJobEntity): Long {
            val id = nextId++
            checkNotNull(assessments[job.assessmentId])
            rows.value = rows.value + AnalysisJobWithResult(
                job = job.copy(jobId = id),
                result = null
            )
            return id
        }

        override suspend fun activatePreparedAssessment(assessmentId: Long): Int {
            val current = assessments[assessmentId] ?: return 0
            val count = rows.value.count { it.job.assessmentId == assessmentId }
            if (current.status != com.example.besharp.data.local.entity.ImageAssessmentStatus.PREPARING ||
                current.expectedJobCount != count
            ) return 0
            assessments[assessmentId] = current.copy(
                status = com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE
            )
            return 1
        }

        override suspend fun insertResult(result: AuthenticityResultEntity) {
            update(result.jobId) { it.copy(result = result.copy(resultId = result.jobId)) }
        }

        override suspend fun insertSignals(signals: List<EvidenceSignalEntity>) {
            signals.groupBy { it.jobId }.forEach { (jobId, jobSignals) ->
                update(jobId) { it.copy(signals = it.signals + jobSignals) }
            }
        }

        override suspend fun getJob(jobId: Long): AnalysisJobEntity? =
            rows.value.firstOrNull { it.job.jobId == jobId }?.job

        override suspend fun getAssessment(assessmentId: Long) = assessments[assessmentId]

        override suspend fun getJobsForAssessment(assessmentId: Long) =
            rows.value.filter { it.job.assessmentId == assessmentId }

        override suspend fun getReadyAssessmentIds(): List<Long> = assessments.values
            .filter { it.status == com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE }
            .filter { assessment ->
                val assessmentRows = rows.value.filter { it.job.assessmentId == assessment.assessmentId }
                assessmentRows.size == assessment.expectedJobCount &&
                    assessmentRows.none {
                        it.job.status == AnalysisJobStatus.QUEUED ||
                            it.job.status == AnalysisJobStatus.RUNNING
                    }
            }
            .map { it.assessmentId }

        override suspend fun completeAssessmentIfActive(
            assessmentId: Long,
            completedAt: Long,
            finalResolution: String,
            summary: String,
            reason: String,
            policyVersion: Int,
            decisiveSignalIdsJson: String,
            conflictingSignalIdsJson: String
        ): Int {
            val current = assessments[assessmentId] ?: return 0
            if (current.status != com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE) return 0
            val assessmentRows = rows.value.filter { it.job.assessmentId == assessmentId }
            if (assessmentRows.size != current.expectedJobCount || assessmentRows.any {
                    it.job.status == AnalysisJobStatus.QUEUED || it.job.status == AnalysisJobStatus.RUNNING
                }
            ) return 0
            assessments[assessmentId] = current.copy(
                status = com.example.besharp.data.local.entity.ImageAssessmentStatus.COMPLETED,
                completedAt = completedAt,
                finalResolution = finalResolution,
                summary = summary,
                resolutionReason = reason,
                resolutionPolicyVersion = policyVersion,
                decisiveSignalIdsJson = decisiveSignalIdsJson,
                conflictingSignalIdsJson = conflictingSignalIdsJson
            )
            return 1
        }

        override suspend fun getActiveJobs(): List<AnalysisJobEntity> = rows.value
            .map { it.job }
            .filter { it.status == AnalysisJobStatus.QUEUED || it.status == AnalysisJobStatus.RUNNING }

        override suspend fun getActiveJobsForProviderPrefix(providerPrefix: String): List<AnalysisJobEntity> =
            getActiveJobs().filter { it.providerId.startsWith(providerPrefix) }

        override suspend fun transitionQueuedToRunning(jobId: Long, startedAt: Long): Int =
            transition(jobId, AnalysisJobStatus.QUEUED) {
                it.copy(status = AnalysisJobStatus.RUNNING, startedAt = startedAt)
            }

        override suspend fun transitionRunningToQueuedForRetry(jobId: Long): Int =
            transition(jobId, AnalysisJobStatus.RUNNING) {
                it.copy(status = AnalysisJobStatus.QUEUED, startedAt = null)
            }

        override suspend fun transitionRunningToSucceeded(jobId: Long, completedAt: Long): Int =
            transition(jobId, AnalysisJobStatus.RUNNING) {
                it.copy(status = AnalysisJobStatus.SUCCEEDED, completedAt = completedAt)
            }

        override suspend fun transitionRunningToFailed(
            jobId: Long,
            completedAt: Long,
            message: String,
            category: String,
            httpStatus: Int?
        ): Int = transition(jobId, AnalysisJobStatus.RUNNING) {
            it.copy(
                status = AnalysisJobStatus.FAILED,
                completedAt = completedAt,
                errorMessage = message,
                errorCategory = category,
                httpStatus = httpStatus
            )
        }

        override suspend fun transitionActiveToCancelled(
            jobId: Long,
            completedAt: Long,
            message: String
        ): Int {
            val status = getJob(jobId)?.status ?: return 0
            if (status != AnalysisJobStatus.QUEUED && status != AnalysisJobStatus.RUNNING) return 0
            return transition(jobId, status) {
                it.copy(status = AnalysisJobStatus.CANCELLED, completedAt = completedAt, errorMessage = message)
            }
        }

        override suspend fun cancelAllActive(completedAt: Long, message: String): Int {
            val active = getActiveJobs()
            active.forEach { transitionActiveToCancelled(it.jobId, completedAt, message) }
            return active.size
        }

        override fun observeAssessment(assessmentId: Long) = observeRecentAssessments(Int.MAX_VALUE)
            .map { entries -> entries.firstOrNull { it.assessment.assessmentId == assessmentId } }

        override fun observeRecentAssessments(limit: Int): Flow<List<ImageAssessmentWithJobs>> =
            rows.map { current ->
                assessments.values.map { assessment ->
                    ImageAssessmentWithJobs(
                        assessment,
                        current.filter { it.job.assessmentId == assessment.assessmentId }
                    )
                }.sortedByDescending { it.assessment.createdAt }.take(limit)
            }
        override fun observeAnalyzedImageUris(): Flow<List<String>> = rows.map { current ->
            current.mapNotNull { row ->
                assessments[row.job.assessmentId]
                    ?.takeIf {
                        it.status == com.example.besharp.data.local.entity.ImageAssessmentStatus.COMPLETED &&
                            it.finalResolution != null
                    }
                    ?.imageUri
            }.distinct()
        }

        override suspend fun deleteForImage(imageUri: String) {
            val assessmentIds = assessments.values
                .filter { it.imageUri == imageUri }
                .mapTo(mutableSetOf()) { it.assessmentId }
            rows.value = rows.value.filterNot { it.job.assessmentId in assessmentIds }
            assessments.entries.removeAll { it.value.imageUri == imageUri }
        }

        override suspend fun deleteOtherCompletedAssessments(
            imageUri: String,
            keepAssessmentId: Long
        ) {
            val deletedIds = assessments.values
                .filter {
                    it.imageUri == imageUri &&
                        it.status == com.example.besharp.data.local.entity.ImageAssessmentStatus.COMPLETED &&
                        it.assessmentId != keepAssessmentId
                }
                .mapTo(mutableSetOf()) { it.assessmentId }
            assessments.keys.removeAll(deletedIds)
            rows.value = rows.value.filterNot { it.job.assessmentId in deletedIds }
        }

        private fun updateJob(jobId: Long, transform: (AnalysisJobEntity) -> AnalysisJobEntity) =
            update(jobId) { it.copy(job = transform(it.job)) }

        private fun transition(
            jobId: Long,
            expectedStatus: String,
            transform: (AnalysisJobEntity) -> AnalysisJobEntity
        ): Int {
            if (rows.value.none { it.job.jobId == jobId && it.job.status == expectedStatus }) return 0
            updateJob(jobId, transform)
            return 1
        }

        private fun update(jobId: Long, transform: (AnalysisJobWithResult) -> AnalysisJobWithResult) {
            rows.value = rows.value.map { if (it.job.jobId == jobId) transform(it) else it }
        }

        fun assessment(assessmentId: Long) = assessments[assessmentId]

        fun allJobs() = rows.value.map { it.job }
    }
}
