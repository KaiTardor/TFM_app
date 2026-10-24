package com.example.besharp.ui.screens.analysis

import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.CustomApiConfigurationStore
import com.example.besharp.data.analysis.AnalysisProviderResolver
import com.example.besharp.data.analysis.OpenAiProvenanceProvider
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.data.credentials.ProviderCredentialStore
import com.example.besharp.data.local.dao.AnalysisJobWithResult
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.privacy.RemoteConsentRecord
import com.example.besharp.data.privacy.RemoteConsentStore
import com.example.besharp.data.privacy.RemoteConsentTarget
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.work.AnalysisWorkState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import com.example.besharp.application.analysis.ReconcileAnalysisJobsUseCase
import com.example.besharp.application.analysis.CancelAnalysisJobsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisPreparationViewModelTest {
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recreationReconcilesQueuedWorkWithoutCreatingAnotherUniqueWork() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val scheduler = KeepScheduler().apply { uniqueJobs += job.jobId }
        val consent = AllowConsent(job)

        createViewModel(dao, scheduler, consent)
        advanceUntilIdle()
        val recreated = createViewModel(dao, scheduler, consent)
        advanceUntilIdle()

        assertEquals(0, scheduler.enqueueAttempts)
        assertEquals(setOf(job.jobId), scheduler.uniqueJobs)
        assertFalse(recreated.uiState.value.isRunning)
    }

    @Test
    fun cancellationDuringSchedulingCancelsPersistedAndScheduledJobs() = runTest(dispatcher) {
        val dao = FakeDao()
        val scheduler = BlockingScheduler()
        val consentJob = testJob()
        val viewModel = createViewModel(
            dao,
            scheduler,
            AllowConsent(consentJob),
            configuredProviders = setOf(CredentialProvider.OPENAI)
        )
        advanceUntilIdle()
        viewModel.addCapturedImage("content://new", "new.jpg")
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)

        viewModel.analyze()
        runCurrent()
        scheduler.release.complete(Unit)
        viewModel.cancelAnalysis()
        advanceUntilIdle()

        val jobs = dao.allJobs()
        assertEquals(1, jobs.size)
        assertEquals(AnalysisJobStatus.CANCELLED, jobs.single().status)
        assertTrue(scheduler.activeJobs.isEmpty())
        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun repeatedAnalyzeWhileSchedulingCreatesOneJob() = runTest(dispatcher) {
        val dao = FakeDao()
        val scheduler = KeepScheduler()
        val viewModel = createViewModel(
            dao,
            scheduler,
            AllowConsent(testJob()),
            configuredProviders = setOf(CredentialProvider.OPENAI)
        )
        advanceUntilIdle()
        viewModel.addCapturedImage("content://new", "new.jpg")
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)

        viewModel.analyze()
        viewModel.analyze()
        advanceUntilIdle()

        assertEquals(1, dao.allJobs().size)
        assertEquals(1, scheduler.enqueueAttempts)
    }

    @Test
    fun persistsEveryProviderJobBeforeSchedulingFirstWorker() = runTest(dispatcher) {
        val dao = FakeDao()
        val scheduler = InspectingScheduler { dao.allJobs().size }
        val custom = CustomApiConfiguration("Detector", "https://custom.example/analyze", "v1")
        val viewModel = createViewModel(
            dao,
            scheduler,
            AllowAllConsent,
            configuredProviders = setOf(CredentialProvider.OPENAI, CredentialProvider.CUSTOM_API),
            customConfiguration = custom
        )
        advanceUntilIdle()
        viewModel.addCapturedImage("content://atomic", "atomic.jpg")
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        viewModel.toggleModel(custom.providerId())

        viewModel.analyze()
        advanceUntilIdle()

        assertEquals(2, dao.allJobs().size)
        assertEquals(2, scheduler.jobsSeenAtFirstEnqueue)
        assertEquals(2, scheduler.enqueueAttempts)
    }

    @Test
    fun partialSchedulingFailureCancelsEntirePersistedBatch() = runTest(dispatcher) {
        val dao = FakeDao()
        val scheduler = FailSecondEnqueueScheduler()
        val viewModel = createViewModel(
            dao,
            scheduler,
            AllowConsent(testJob()),
            configuredProviders = setOf(CredentialProvider.OPENAI)
        )
        advanceUntilIdle()
        viewModel.addCapturedImage("content://one", "one.jpg")
        viewModel.addCapturedImage("content://two", "two.jpg")
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)

        viewModel.analyze()
        advanceUntilIdle()

        assertEquals(2, dao.allJobs().size)
        assertTrue(dao.allJobs().all { it.status == AnalysisJobStatus.CANCELLED })
        assertTrue(scheduler.activeJobs.isEmpty())
        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun transientReconciliationFailureDoesNotCancelJob() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)

        val viewModel = createViewModel(dao, ThrowingScheduler, AllowConsent(job))
        advanceUntilIdle()

        assertEquals(AnalysisJobStatus.QUEUED, dao.allJobs().single().status)
        assertFalse(viewModel.uiState.value.isReconciling)
        assertTrue(viewModel.uiState.value.batchError != null)
    }

    @Test
    fun absentHistoryClosesQueuedAndRunningJobsWithoutResendingAfterRecreation() = runTest(dispatcher) {
        for (status in listOf(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING)) {
            val job = testJob().copy(status = status)
            val dao = FakeDao(job)
            val scheduler = KeepScheduler()
            createViewModel(dao, scheduler, AllowConsent(job))
            advanceUntilIdle()
            val recreated = createViewModel(dao, scheduler, AllowConsent(job))
            advanceUntilIdle()

            assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
            assertTrue(dao.allJobs().single().errorMessage!!.contains("no se ha reenviado"))
            assertEquals(0, scheduler.enqueueAttempts)
            assertFalse(recreated.uiState.value.isReconciling)
            assertEquals(
                com.example.besharp.data.local.entity.ImageAssessmentStatus.COMPLETED,
                dao.getAssessment(job.assessmentId)?.status
            )
        }
    }

    @Test
    fun missingHistoryWithRoomFailureNeverEnqueuesAndCanCloseOnNextRecreation() = runTest(dispatcher) {
        val job = testJob().copy(status = AnalysisJobStatus.RUNNING)
        val dao = FakeDao(job).apply { failCancelJob = true }
        val scheduler = KeepScheduler()
        val failed = createViewModel(dao, scheduler, AllowConsent(job))
        advanceUntilIdle()
        assertEquals(AnalysisJobStatus.RUNNING, dao.allJobs().single().status)
        assertFalse(failed.uiState.value.isReconciling)
        assertTrue(failed.uiState.value.batchError != null)
        dao.failCancelJob = false
        createViewModel(dao, scheduler, AllowConsent(job))
        advanceUntilIdle()
        assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
        assertEquals(0, scheduler.enqueueAttempts)
    }

    @Test
    fun finishedWorkWithUnpersistedFailureIsClosedWithoutResending() = runTest(dispatcher) {
        for (status in listOf(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING)) {
            val job = testJob().copy(status = status)
            val dao = FakeDao(job)
            val scheduler = object : AnalysisScheduler by KeepScheduler() {
                override suspend fun workState(jobId: Long) = AnalysisWorkState.FINISHED
                override suspend fun enqueue(jobId: Long) = error("No debe reenviar")
            }
            createViewModel(dao, scheduler, AllowConsent(job))
            advanceUntilIdle()
            assertEquals(AnalysisJobStatus.FAILED, dao.allJobs().single().status)
            assertEquals("LOCAL_STORAGE", dao.allJobs().single().errorCategory)
        }
    }

    @Test
    fun reconciliationPropagatesCoroutineCancellation() = runTest(dispatcher) {
        val job = testJob()
        val repository = AnalysisQueueRepository(FakeDao(job))
        val useCase = ReconcileAnalysisJobsUseCase(repository, CancelledScheduler, AllowConsent(job))
        var cancelled = false
        try {
            useCase.execute()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun jobCancellationDoesNotSwallowCoroutineCancellation() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val useCase = CancelAnalysisJobsUseCase(AnalysisQueueRepository(dao), CancelledScheduler)
        var cancelled = false
        try {
            useCase.execute(listOf(job.jobId), "Cancelado")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
    }

    @Test
    fun customConsentDescriptionIncludesDestinationWithoutSecrets() = runTest(dispatcher) {
        val custom = CustomApiConfiguration(
            "Detector privado",
            "https://custom.example:8443/v1/analyze",
            "model-v2"
        )
        val targetJob = testJob().copy(
            providerId = custom.providerId(),
            destinationFingerprint = custom.destinationFingerprint()
        )
        val dao = FakeDao()
        val viewModel = createViewModel(
            dao,
            KeepScheduler(),
            AllowConsent(targetJob),
            configuredProviders = setOf(CredentialProvider.CUSTOM_API),
            customConfiguration = custom
        )
        advanceUntilIdle()
        viewModel.toggleModel(custom.providerId())

        val description = viewModel.selectedConsentPreview().providerNames
        assertTrue(description.contains("host: custom.example"))
        assertTrue(description.contains("puerto: 8443"))
        assertTrue(description.contains("ruta: /v1/analyze"))
        assertTrue(description.contains("modelo: model-v2"))
        assertFalse(description.contains("token", ignoreCase = true))
    }

    @Test
    fun consentInputTypesReflectTheSelectionWithoutDuplicates() = runTest(dispatcher) {
        val custom = CustomApiConfiguration("Detector", "https://custom.example/analyze", "v1")
        val viewModel = createViewModel(
            FakeDao(), KeepScheduler(), AllowAllConsent,
            configuredProviders = setOf(CredentialProvider.OPENAI, CredentialProvider.CUSTOM_API),
            customConfiguration = custom
        )
        advanceUntilIdle()
        val original = com.example.besharp.domain.model.AnalysisInputType.ORIGINAL_FILE
        val normalized = com.example.besharp.domain.model.AnalysisInputType.NORMALIZED_IMAGE
        assertTrue(viewModel.selectedConsentPreview().inputTypes.isEmpty())
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        assertEquals(setOf(original), viewModel.selectedConsentPreview().inputTypes)
        viewModel.toggleModel(custom.providerId())
        assertEquals(setOf(original, normalized), viewModel.selectedConsentPreview().inputTypes)
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        assertEquals(setOf(normalized), viewModel.selectedConsentPreview().inputTypes)
    }

    @Test
    fun consentOnlyAuthorizesTheDisplayedSelection() = runTest(dispatcher) {
        var accepted = 0
        val custom = CustomApiConfiguration("Detector", "https://custom.example/analyze", "v1")
        val consent = object : RemoteConsentStore by AllowAllConsent {
            override fun accept(targets: Set<RemoteConsentTarget>): RemoteConsentRecord {
                accepted++
                return AllowAllConsent.accept(targets)
            }
        }
        val viewModel = createViewModel(
            FakeDao(), KeepScheduler(), consent,
            configuredProviders = setOf(CredentialProvider.OPENAI, CredentialProvider.CUSTOM_API),
            customConfiguration = custom
        )
        advanceUntilIdle()
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        val displayed = viewModel.selectedConsentPreview()
        viewModel.toggleModel(custom.providerId())
        assertFalse(viewModel.acceptRemoteConsent(displayed))
        assertEquals(0, accepted)
        assertTrue(viewModel.acceptRemoteConsent(viewModel.selectedConsentPreview()))
        assertEquals(1, accepted)
    }

    @Test
    fun unknownStoredJobStateIsNotPresentedAsQueued() = runTest(dispatcher) {
        val job = testJob().copy(status = "not-a-state")
        val viewModel = createViewModel(FakeDao(job), KeepScheduler(), AllowAllConsent)
        advanceUntilIdle()
        val assessment = viewModel.observeAssessment(job.assessmentId).first()!!
        assertEquals(com.example.besharp.ui.model.AnalysisJobState.UNKNOWN, assessment.jobs.single().state)
    }

    @Test
    fun restoresPendingImagesAndConfiguredModels() = runTest(dispatcher) {
        val savedState = SavedStateHandle(
            mapOf(
                "analysis_image_uris" to arrayListOf("content://pending"),
                "analysis_image_names" to arrayListOf("pending.jpg"),
                "analysis_selected_models" to arrayListOf(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
            )
        )

        val viewModel = createViewModel(
            dao = FakeDao(),
            scheduler = KeepScheduler(),
            consent = AllowConsent(testJob()),
            configuredProviders = setOf(CredentialProvider.OPENAI),
            savedStateHandle = savedState
        )
        advanceUntilIdle()

        assertEquals(listOf("content://pending"), viewModel.uiState.value.images.map { it.uri })
        assertEquals(
            setOf(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID),
            viewModel.uiState.value.selectedModelIds
        )
    }

    @Test
    fun restoredImageValidationPreservesAdditionsAndRemovals() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeDao(), KeepScheduler(), AllowAllConsent)
        advanceUntilIdle()
        viewModel.addCapturedImage("content://valid", "valid.jpg")
        viewModel.addCapturedImage("content://invalid", "invalid.jpg")
        val reply = CompletableDeferred<Unit>()
        viewModel.validateRestoredImages { image ->
            reply.await()
            image.uri != "content://invalid"
        }
        runCurrent()
        viewModel.removeImage("content://valid")
        viewModel.addCapturedImage("content://new", "new.jpg")
        reply.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("content://new"), viewModel.uiState.value.images.map { it.uri })
    }

    @Test
    fun pendingImageReadCannotModifySubmittedBatchOrNextSelection() = runTest(dispatcher) {
        val dao = FakeDao()
        val viewModel = createViewModel(
            dao, KeepScheduler(), AllowAllConsent,
            configuredProviders = setOf(CredentialProvider.OPENAI)
        )
        advanceUntilIdle()
        viewModel.addCapturedImage("content://submitted", "submitted.jpg")
        viewModel.toggleModel(AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID)
        val reply = CompletableDeferred<List<SelectedAnalysisImage>>()
        viewModel.addImages { reply.await() }
        runCurrent()
        viewModel.analyze()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.images.isEmpty())
        viewModel.addCapturedImage("content://next", "next.jpg")
        reply.complete(listOf(SelectedAnalysisImage("content://late", "late.jpg")))
        advanceUntilIdle()

        assertEquals(1, dao.allJobs().size)
        assertEquals(listOf("content://next"), viewModel.uiState.value.images.map { it.uri })
    }

    @Test
    fun failedConsentRevocationDoesNotAnnounceSuccessOrCancelJobs() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val scheduler = KeepScheduler().apply { uniqueJobs += job.jobId }
        val consent = object : RemoteConsentStore by AllowAllConsent {
            override fun revoke() = error("storage failed")
        }
        val viewModel = createViewModel(dao, scheduler, consent)
        advanceUntilIdle()

        assertFalse(viewModel.revokeRemoteConsent())
        advanceUntilIdle()

        assertTrue(viewModel.remoteConsentAccepted.value)
        assertEquals(AnalysisJobStatus.QUEUED, dao.allJobs().single().status)
        assertTrue(job.jobId in scheduler.uniqueJobs)
    }

    @Test
    fun revocationCompletesCancellationEvenWhenTheViewModelIsClearedDuringCommit() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val scheduler = KeepScheduler()
        val owner = ViewModelStore()
        val consent = object : RemoteConsentStore by AllowAllConsent {
            override fun revoke() { owner.clear() }
        }
        val viewModel = createViewModel(dao, scheduler, consent)
        owner.put("analysis", viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.revokeRemoteConsent())
        assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
        assertTrue(scheduler.uniqueJobs.isEmpty())
    }

    @Test
    fun successfulConsentRevocationCancelsPersistedAndScheduledJobs() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val scheduler = KeepScheduler()
        var revoked = false
        val consent = object : RemoteConsentStore by AllowAllConsent {
            override fun revoke() { revoked = true }
            override fun getCurrentConsent() = if (revoked) null else AllowAllConsent.getCurrentConsent()
            override fun hasConsentFor(target: RemoteConsentTarget) = !revoked
            override fun hasConsentFor(targets: Set<RemoteConsentTarget>) = !revoked
        }
        val viewModel = createViewModel(dao, scheduler, consent)
        advanceUntilIdle()

        assertTrue(viewModel.revokeRemoteConsent())
        advanceUntilIdle()

        assertFalse(viewModel.remoteConsentAccepted.value)
        assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
        assertTrue(scheduler.uniqueJobs.isEmpty())
    }

    @Test
    fun imageReadFailurePreservesSelectionAndPublishesError() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeDao(), KeepScheduler(), AllowAllConsent)
        advanceUntilIdle()
        viewModel.addCapturedImage("content://kept", "kept.jpg")
        viewModel.addImages { error("resolver failed") }
        advanceUntilIdle()
        assertEquals(listOf("content://kept"), viewModel.uiState.value.images.map { it.uri })
        assertTrue(viewModel.uiState.value.batchError != null)
    }

    @Test
    fun revocationStillCancelsSchedulerWhenRoomFails() = runTest(dispatcher) {
        val dao = FakeDao().apply { failCancelAll = true }
        var cancelled = false
        val scheduler = object : AnalysisScheduler by KeepScheduler() {
            override suspend fun cancelAllRemote() { cancelled = true }
        }
        val viewModel = createViewModel(dao, scheduler, AllowAllConsent)
        advanceUntilIdle()
        assertTrue(viewModel.revokeRemoteConsent())
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(viewModel.uiState.value.batchError != null)
        assertFalse(viewModel.remoteConsentAccepted.value)
    }

    @Test
    fun cancellationAttemptsEveryJobEvenWhenSchedulerFails() = runTest(dispatcher) {
        val job = testJob()
        val dao = FakeDao(job)
        val attempted = mutableListOf<Long>()
        val scheduler = object : AnalysisScheduler by KeepScheduler() {
            override suspend fun cancel(jobId: Long) {
                attempted += jobId
                if (jobId == job.jobId) error("scheduler failed")
            }
        }
        try {
            CancelAnalysisJobsUseCase(AnalysisQueueRepository(dao), scheduler)
                .execute(listOf(job.jobId, 99), "Cancelado")
            org.junit.Assert.fail("Debe informar del fallo parcial")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(job.jobId, 99), attempted)
            assertEquals(AnalysisJobStatus.CANCELLED, dao.allJobs().single().status)
        }
    }

    private fun createViewModel(
        dao: FakeDao,
        scheduler: AnalysisScheduler,
        consent: RemoteConsentStore,
        configuredProviders: Set<CredentialProvider> = emptySet(),
        customConfiguration: CustomApiConfiguration? = null,
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): AnalysisPreparationViewModel {
        val repository = AnalysisQueueRepository(dao)
        val credentials = object : ProviderCredentialStore {
            override fun hasCredential(provider: CredentialProvider) = provider in configuredProviders
            override fun getCredential(provider: CredentialProvider): String =
                error("La preparacion no debe descifrar credenciales")
            override fun saveCredential(provider: CredentialProvider, credential: String) = Unit
            override fun deleteCredential(provider: CredentialProvider) = Unit
        }
        val custom = object : CustomApiConfigurationStore {
            override fun getConfiguration(): CustomApiConfiguration? = customConfiguration
            override fun saveConfiguration(configuration: CustomApiConfiguration) = Unit
            override fun deleteConfiguration() = Unit
        }
        return AnalysisPreparationViewModel(
            credentials,
            custom,
            repository,
            com.example.besharp.data.repository.AnalysisHistoryRepository(dao),
            AnalysisProviderResolver(credentials, custom),
            scheduler,
            consent,
            ioDispatcher = dispatcher,
            savedStateHandle = savedStateHandle
        )
    }

    private fun testJob() = AnalysisJobEntity(
        jobId = 42,
        assessmentId = 1,
        providerId = AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID,
        providerName = "OpenAI",
        modelId = OpenAiProvenanceProvider.MODEL_ID,
        capability = com.example.besharp.domain.model.AnalysisCapability.CONTENT_PROVENANCE.name,
        inputType = com.example.besharp.domain.model.AnalysisInputType.ORIGINAL_FILE.name,
        executionId = "execution-42",
        destinationFingerprint = "${AnalysisProviderResolver.OPENAI_PROVENANCE_PROVIDER_ID}:${OpenAiProvenanceProvider.MODEL_ID}",
        status = AnalysisJobStatus.QUEUED,
        createdAt = 1L
    )

    private object CancelledScheduler : AnalysisScheduler {
        override suspend fun enqueue(jobId: Long): Unit = throw CancellationException()
        override suspend fun cancel(jobId: Long): Unit = throw CancellationException()
        override suspend fun cancelAllRemote() = Unit
        override suspend fun workState(jobId: Long): AnalysisWorkState = throw CancellationException()
    }

    private class KeepScheduler : AnalysisScheduler {
        var enqueueAttempts = 0
        val uniqueJobs = mutableSetOf<Long>()

        override suspend fun enqueue(jobId: Long) {
            enqueueAttempts++
            uniqueJobs += jobId
        }

        override suspend fun cancel(jobId: Long) {
            uniqueJobs -= jobId
        }

        override suspend fun cancelAllRemote() {
            uniqueJobs.clear()
        }

        override suspend fun workState(jobId: Long) =
            if (jobId in uniqueJobs) AnalysisWorkState.ACTIVE else AnalysisWorkState.ABSENT
    }

    private class BlockingScheduler : AnalysisScheduler {
        val release = CompletableDeferred<Unit>()
        val activeJobs = mutableSetOf<Long>()

        override suspend fun enqueue(jobId: Long) {
            release.await()
            activeJobs += jobId
        }

        override suspend fun cancel(jobId: Long) {
            activeJobs -= jobId
        }

        override suspend fun cancelAllRemote() {
            activeJobs.clear()
        }

        override suspend fun workState(jobId: Long) =
            if (jobId in activeJobs) AnalysisWorkState.ACTIVE else AnalysisWorkState.ABSENT
    }

    private class FailSecondEnqueueScheduler : AnalysisScheduler {
        private var attempts = 0
        val activeJobs = mutableSetOf<Long>()

        override suspend fun enqueue(jobId: Long) {
            attempts++
            if (attempts == 2) error("temporary WorkManager failure")
            activeJobs += jobId
        }

        override suspend fun cancel(jobId: Long) {
            activeJobs -= jobId
        }

        override suspend fun cancelAllRemote() {
            activeJobs.clear()
        }

        override suspend fun workState(jobId: Long) =
            if (jobId in activeJobs) AnalysisWorkState.ACTIVE else AnalysisWorkState.ABSENT
    }

    private object ThrowingScheduler : AnalysisScheduler {
        override suspend fun enqueue(jobId: Long) = error("temporary WorkManager failure")
        override suspend fun cancel(jobId: Long) = Unit
        override suspend fun cancelAllRemote() = Unit
        override suspend fun workState(jobId: Long): AnalysisWorkState = error("temporary WorkManager failure")
    }

    private class InspectingScheduler(
        private val jobCount: () -> Int
    ) : AnalysisScheduler {
        var enqueueAttempts = 0
        var jobsSeenAtFirstEnqueue = -1

        override suspend fun enqueue(jobId: Long) {
            if (enqueueAttempts == 0) jobsSeenAtFirstEnqueue = jobCount()
            enqueueAttempts++
        }

        override suspend fun cancel(jobId: Long) = Unit
        override suspend fun cancelAllRemote() = Unit
        override suspend fun workState(jobId: Long) = AnalysisWorkState.ABSENT
    }

    private object AllowAllConsent : RemoteConsentStore {
        private val record = RemoteConsentRecord(3, 1L, setOf("all"))
        override fun getCurrentConsent() = record
        override fun hasConsentFor(targets: Set<RemoteConsentTarget>) = true
        override fun hasConsentFor(target: RemoteConsentTarget) = true
        override fun accept(targets: Set<RemoteConsentTarget>) = record
        override fun revoke() = Unit
    }

    private class AllowConsent(job: AnalysisJobEntity) : RemoteConsentStore {
        private val target = RemoteConsentTarget(
            job.providerId,
            job.destinationFingerprint,
            com.example.besharp.domain.model.AnalysisCapability.valueOf(job.capability),
            com.example.besharp.domain.model.AnalysisInputType.valueOf(job.inputType)
        )
        private val record = RemoteConsentRecord(3, 1L, setOf(target.key))
        override fun getCurrentConsent() = record
        override fun hasConsentFor(targets: Set<RemoteConsentTarget>) = record.matches(targets)
        override fun hasConsentFor(target: RemoteConsentTarget) = record.matches(setOf(target))
        override fun accept(targets: Set<RemoteConsentTarget>) = record
        override fun revoke() = Unit
    }

    private class FakeDao(job: AnalysisJobEntity? = null) : AnalysisQueueDao {
        var failCancelAll = false
        var failCancelJob = false
        private val assessments = mutableMapOf<Long, com.example.besharp.data.local.entity.ImageAssessmentEntity>().apply {
            job?.let {
                put(
                    it.assessmentId,
                    com.example.besharp.data.local.entity.ImageAssessmentEntity(
                        assessmentId = it.assessmentId,
                        imageUri = "content://restored",
                        imageDisplayName = "restored.jpg",
                        status = com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE,
                        expectedJobCount = 1,
                        createdAt = it.createdAt
                    )
                )
            }
        }
        private val rows = MutableStateFlow(
            job?.let {
                listOf(AnalysisJobWithResult(it, null))
            }.orEmpty()
        )
        private var nextId = (job?.jobId ?: 0L) + 1L
        private var nextAssessmentId = 1L

        override suspend fun insertAssessment(
            assessment: com.example.besharp.data.local.entity.ImageAssessmentEntity
        ): Long {
            val id = nextAssessmentId++
            assessments[id] = assessment.copy(assessmentId = id)
            return id
        }

        override suspend fun insertJob(job: AnalysisJobEntity): Long {
            val id = nextId++
            checkNotNull(assessments[job.assessmentId])
            rows.value = rows.value + AnalysisJobWithResult(
                job.copy(jobId = id),
                null
            )
            return id
        }
        override suspend fun activatePreparedAssessment(assessmentId: Long): Int {
            val current = assessments[assessmentId] ?: return 0
            val count = rows.value.count { it.job.assessmentId == assessmentId }
            if (current.expectedJobCount != count) return 0
            assessments[assessmentId] = current.copy(
                status = com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE
            )
            return 1
        }
        override suspend fun insertResult(result: AuthenticityResultEntity) = error("Not used")
        override suspend fun insertSignals(
            signals: List<com.example.besharp.data.local.entity.EvidenceSignalEntity>
        ) = error("Not used")
        override suspend fun getJob(jobId: Long) = rows.value.firstOrNull { it.job.jobId == jobId }?.job
        override suspend fun getAssessment(assessmentId: Long) = assessments[assessmentId]
        override suspend fun getJobsForAssessment(assessmentId: Long) =
            rows.value.filter { it.job.assessmentId == assessmentId }
        override suspend fun getReadyAssessmentIds() = assessments.values
            .filter { assessment ->
                assessment.status == com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE &&
                    getJobsForAssessment(assessment.assessmentId).let { jobs ->
                        jobs.size == assessment.expectedJobCount && jobs.none { it.job.status in ACTIVE }
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
            assessments[assessmentId] = current.copy(
                status = com.example.besharp.data.local.entity.ImageAssessmentStatus.COMPLETED,
                finalResolution = finalResolution
            )
            return 1
        }
        override suspend fun getActiveJobs() = rows.value.map { it.job }.filter { it.status in ACTIVE }
        override suspend fun getActiveJobsForProviderPrefix(providerPrefix: String) =
            getActiveJobs().filter { it.providerId.startsWith(providerPrefix) }
        override suspend fun transitionQueuedToRunning(jobId: Long, startedAt: Long) = update(jobId) {
            if (it.status == AnalysisJobStatus.QUEUED) it.copy(status = AnalysisJobStatus.RUNNING) else it
        }
        override suspend fun transitionRunningToQueuedForRetry(jobId: Long) = update(jobId) {
            if (it.status == AnalysisJobStatus.RUNNING) it.copy(status = AnalysisJobStatus.QUEUED) else it
        }
        override suspend fun transitionRunningToSucceeded(jobId: Long, completedAt: Long) = 0
        override suspend fun transitionRunningToFailed(
            jobId: Long,
            completedAt: Long,
            message: String,
            category: String,
            httpStatus: Int?
        ) = update(jobId) {
            if (it.status == AnalysisJobStatus.RUNNING) it.copy(
                status = AnalysisJobStatus.FAILED, errorMessage = message, errorCategory = category
            ) else it
        }

        override suspend fun transitionActiveToCancelled(jobId: Long, completedAt: Long, message: String): Int {
            if (failCancelJob) error("database failed")
            return update(jobId) {
                if (it.status in ACTIVE) it.copy(status = AnalysisJobStatus.CANCELLED, errorMessage = message) else it
            }
        }

        override suspend fun cancelAllActive(completedAt: Long, message: String): Int {
            if (failCancelAll) error("database failed")
            val count = getActiveJobs().size
            rows.value = rows.value.map { row ->
                if (row.job.status in ACTIVE) row.copy(job = row.job.copy(status = AnalysisJobStatus.CANCELLED))
                else row
            }
            return count
        }

        override fun observeRecentAssessments(limit: Int) = rows.map { current ->
            assessments.values.map { assessment ->
                com.example.besharp.data.local.dao.ImageAssessmentWithJobs(
                    assessment,
                    current.filter { it.job.assessmentId == assessment.assessmentId }
                )
            }
        }
        override fun observeAssessment(assessmentId: Long) = observeRecentAssessments(Int.MAX_VALUE)
            .map { entries -> entries.firstOrNull { it.assessment.assessmentId == assessmentId } }
        override fun observeAnalyzedImageUris(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun deleteForImage(imageUri: String) = Unit
        override suspend fun deleteOtherCompletedAssessments(
            imageUri: String,
            keepAssessmentId: Long
        ) = Unit

        fun allJobs() = rows.value.map { it.job }

        private fun update(jobId: Long, transform: (AnalysisJobEntity) -> AnalysisJobEntity): Int {
            var changed = 0
            rows.value = rows.value.map { row ->
                if (row.job.jobId == jobId) {
                    val updated = transform(row.job)
                    if (updated != row.job) changed = 1
                    row.copy(job = updated)
                } else row
            }
            return changed
        }

        companion object {
            private val ACTIVE = setOf(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING)
        }
    }
}
