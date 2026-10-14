package com.example.besharp.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.SharedPreferencesCustomApiConfigurationStore
import com.example.besharp.data.local.database.AppDatabase
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.local.entity.ImageAssessmentEntity
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.local.entity.PhotoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisPersistenceInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulResultAndJobStateArePersistedTogether() = runBlocking {
        val dao = database.analysisQueueDao()
        val assessmentId = dao.insertAssessment(testAssessment("content://image/1"))
        val jobId = dao.insertJob(testJob(assessmentId, "content://image/1"))
        assertEquals(1, dao.transitionQueuedToRunning(jobId, 150L))

        dao.saveSuccessfulResult(
            jobId = jobId,
            completedAt = 200L,
            result = testResult(jobId)
        )

        val row = dao.observeRecentAssessments(10).first().flatMap { it.jobs }.single()
        assertEquals(AnalysisJobStatus.SUCCEEDED, row.job.status)
        assertEquals("LIKELY_SYNTHETIC", row.result?.verdict)
        assertEquals(84, row.result?.syntheticProbability)
    }

    @Test
    fun deletingJobByImageCascadesToItsResult() = runBlocking {
        val dao = database.analysisQueueDao()
        val assessmentId = dao.insertAssessment(testAssessment("content://image/2"))
        val jobId = dao.insertJob(testJob(assessmentId, "content://image/2"))
        dao.transitionQueuedToRunning(jobId, 150L)
        dao.saveSuccessfulResult(jobId, 200L, testResult(jobId))

        dao.deleteForImage("content://image/2")

        assertTrue(dao.observeRecentAssessments(10).first().isEmpty())
    }

    @Test
    fun resultOrSignalInsertFailureRollsBackSuccessfulStateAndPartialEvidence() = runBlocking {
        val dao = database.analysisQueueDao()
        for (failSignals in listOf(false, true)) {
            val uri = "content://image/rollback/$failSignals"
            val assessmentId = dao.insertAssessment(testAssessment(uri))
            val jobId = dao.insertJob(testJob(assessmentId, uri).copy(executionId = uri))
            dao.transitionQueuedToRunning(jobId, 150L)

            val failed = runCatching {
                dao.saveSuccessfulResult(
                    jobId,
                    200L,
                    testResult(if (failSignals) jobId else -1L),
                    if (failSignals) listOf(testSignal("invalid", -1L, "C2PA_CREDENTIAL", "DETECTED"))
                    else emptyList()
                )
            }

            assertTrue(failed.isFailure)
            val row = dao.getJobsForAssessment(assessmentId).single()
            assertEquals(AnalysisJobStatus.RUNNING, row.job.status)
            assertEquals(null, row.result)
            assertTrue(row.signals.isEmpty())
        }
    }

    @Test
    fun customApiParametersSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SharedPreferencesCustomApiConfigurationStore(context)
        store.deleteConfiguration()
        store.saveConfiguration(
            CustomApiConfiguration(
                displayName = "Detector",
                endpointUrl = "https://example.com/analyze",
                modelId = "v1",
                parameters = mapOf("threshold" to "0.75", "mode" to "strict")
            )
        )

        val restored = SharedPreferencesCustomApiConfigurationStore(context).getConfiguration()

        assertEquals(mapOf("threshold" to "0.75", "mode" to "strict"), restored?.parameters)
        store.deleteConfiguration()
    }

    @Test
    fun cancelledJobCannotPersistLateResult() = runBlocking {
        val dao = database.analysisQueueDao()
        val assessmentId = dao.insertAssessment(testAssessment("content://image/race"))
        val jobId = dao.insertJob(testJob(assessmentId, "content://image/race"))
        assertEquals(1, dao.transitionQueuedToRunning(jobId, 150L))
        assertEquals(1, dao.transitionActiveToCancelled(jobId, 175L, "Cancelado"))

        assertFalse(dao.saveSuccessfulResult(jobId, 200L, testResult(jobId)))

        val row = dao.observeRecentAssessments(10).first().flatMap { it.jobs }.single()
        assertEquals(AnalysisJobStatus.CANCELLED, row.job.status)
        assertEquals(null, row.result)
    }

    @Test
    fun persistsMultipleEvidenceSignalsForOneResult() = runBlocking {
        val dao = database.analysisQueueDao()
        val assessmentId = dao.insertAssessment(testAssessment("content://image/signals"))
        val jobId = dao.insertJob(testJob(assessmentId, "content://image/signals"))
        dao.transitionQueuedToRunning(jobId, 150L)

        assertTrue(
            dao.saveSuccessfulResult(
                jobId = jobId,
                completedAt = 200L,
                result = testResult(jobId),
                signals = listOf(
                    testSignal("c2pa", jobId, "C2PA_CREDENTIAL", "DETECTED"),
                    testSignal("synthid", jobId, "SYNTHID_WATERMARK", "NOT_DETECTED")
                )
            )
        )

        val row = dao.getJobsForAssessment(assessmentId).single()
        assertEquals(2, row.signals.size)
        assertEquals(setOf("DETECTED", "NOT_DETECTED"), row.signals.map { it.outcome }.toSet())
    }

    @Test
    fun createsAndActivatesCompleteAssessmentAtomically() = runBlocking {
        val dao = database.analysisQueueDao()
        val created = dao.createAssessmentWithJobs(
            assessment = testAssessment("content://image/atomic", expectedJobCount = 2),
            jobs = listOf(
                testJob(0, "content://image/atomic", "provider:a"),
                testJob(0, "content://image/atomic", "provider:b")
            )
        )

        val assessment = dao.getAssessment(created.assessmentId)
        assertEquals(2, created.jobIds.size)
        assertEquals("ACTIVE", assessment?.status)
        assertEquals(2, dao.getJobsForAssessment(created.assessmentId).size)
    }

    @Test
    fun incompleteAssessmentCannotBeFinalized() = runBlocking {
        val dao = database.analysisQueueDao()
        val assessmentId = dao.insertAssessment(
            testAssessment("content://image/incomplete", expectedJobCount = 2).copy(status = "ACTIVE")
        )
        val jobId = dao.insertJob(testJob(assessmentId, "content://image/incomplete"))
        dao.transitionQueuedToRunning(jobId, 150L)
        dao.saveSuccessfulResult(jobId, 200L, testResult(jobId))

        val changed = dao.completeAssessmentIfActive(
            assessmentId = assessmentId,
            completedAt = 300L,
            finalResolution = "POSSIBLY_SYNTHETIC",
            summary = "No debe guardarse",
            reason = "Lote incompleto",
            policyVersion = 2,
            decisiveSignalIdsJson = "[]",
            conflictingSignalIdsJson = "[]"
        )

        assertEquals(0, changed)
        assertEquals("ACTIVE", dao.getAssessment(assessmentId)?.status)
    }

    @Test
    fun partialAccessHidesButPreservesUnselectedPhotos() = runBlocking {
        val photoDao = database.photoDao()
        val first = testPhoto("content://media/1", "one.jpg")
        val second = testPhoto("content://media/2", "two.jpg")
        photoDao.upsertPhotos(listOf(first, second))
        val stored = photoDao.getAllPhotosSync()
        val selected = stored.single { it.contentUri == first.contentUri }

        photoDao.synchronize(listOf(selected.copy(isVisible = true)), emptyList(), partialAccess = true)

        assertEquals(listOf(first.contentUri), photoDao.getAllPhotosFlow().first().map { it.contentUri })
        assertEquals(2, photoDao.getAllPhotosSync().size)
    }

    @Test
    fun keepsOnlyChosenCompletedAssessmentForImage() = runBlocking {
        val dao = database.analysisQueueDao()
        val uri = "content://image/replacement"
        suspend fun completedAssessment(createdAt: Long): Long {
            val created = dao.createAssessmentWithJobs(
                testAssessment(uri).copy(createdAt = createdAt),
                listOf(testJob(0, uri).copy(createdAt = createdAt))
            )
            val jobId = created.jobIds.single()
            dao.transitionQueuedToRunning(jobId, createdAt + 1)
            dao.saveSuccessfulResult(jobId, createdAt + 2, testResult(jobId))
            dao.completeAssessmentIfActive(
                assessmentId = created.assessmentId,
                completedAt = createdAt + 3,
                finalResolution = "POSSIBLY_SYNTHETIC",
                summary = "Resultado",
                reason = "Prueba",
                policyVersion = 2,
                decisiveSignalIdsJson = "[]",
                conflictingSignalIdsJson = "[]"
            )
            return created.assessmentId
        }
        val older = completedAssessment(100)
        val newest = completedAssessment(200)

        dao.deleteOtherCompletedAssessments(uri, newest)

        val remaining = dao.observeRecentAssessments(10).first()
        assertEquals(listOf(newest), remaining.map { it.assessment.assessmentId })

        dao.deleteOtherCompletedAssessments(uri, older)

        val afterStaleDecision = dao.observeRecentAssessments(10).first()
        assertEquals(listOf(newest), afterStaleDecision.map { it.assessment.assessmentId })
    }

    @Test
    fun customProviderPrefixDoesNotMatchSimilarlyNamedProviders() = runBlocking {
        val dao = database.analysisQueueDao()
        val uri = "content://image/prefix"
        val assessmentId = dao.insertAssessment(testAssessment(uri, expectedJobCount = 3))
        for (id in listOf("custom-api:hash", "custom-api-other:hash", "custom-api")) {
            dao.insertJob(testJob(assessmentId, uri, id).copy(executionId = id))
        }

        val jobs = dao.getActiveJobsForProviderPrefix(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX)
        assertEquals(listOf("custom-api:hash"), jobs.map { it.providerId })
    }

    @Test
    fun directAssessmentObservationDoesNotDependOnHistoryWindow() = runBlocking {
        val dao = database.analysisQueueDao()
        val first = dao.insertAssessment(testAssessment("content://old"))
        dao.insertAssessment(testAssessment("content://new"))
        assertFalse(dao.observeRecentAssessments(1).first().any { it.assessment.assessmentId == first })
        assertEquals(first, dao.observeAssessment(first).first()?.assessment?.assessmentId)
    }

    @Test
    fun emptyValidMediaStoreCursorIsNotAnError() = runBlocking {
        val cursor = android.database.MatrixCursor(arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.MIME_TYPE,
            android.provider.MediaStore.Images.Media.DATE_TAKEN,
            android.provider.MediaStore.Images.Media.DATE_ADDED
        ))
        assertTrue(com.example.besharp.data.local.MediaStoreHelper.readImages(cursor).isEmpty())
        assertTrue(cursor.isClosed)
    }

    private fun testAssessment(imageUri: String, expectedJobCount: Int = 1) = ImageAssessmentEntity(
        imageUri = imageUri,
        imageDisplayName = "imagen.jpg",
        expectedJobCount = expectedJobCount,
        createdAt = 100L
    )

    private fun testJob(
        assessmentId: Long,
        @Suppress("UNUSED_PARAMETER") imageUri: String,
        providerId: String = "detector:test"
    ) = AnalysisJobEntity(
        assessmentId = assessmentId,
        providerId = providerId,
        providerName = "Detector",
        modelId = "v1",
        executionId = "execution-1",
        destinationFingerprint = "detector:test",
        status = AnalysisJobStatus.QUEUED,
        createdAt = 100L
    )

    private fun testResult(jobId: Long) = AuthenticityResultEntity(
        jobId = jobId,
        verdict = "LIKELY_SYNTHETIC",
        syntheticProbability = 84,
        evidenceType = "LOCAL_CLASSIFIER",
        verificationLevel = "LOCALLY_VERIFIED",
        summary = "Resultado de prueba",
        indicatorsJson = "[]",
        analyzedAt = 150L,
        durationMillis = 50L,
        origin = "LOCAL_ANALYSIS"
    )

    private fun testSignal(
        signalId: String,
        jobId: Long,
        type: String,
        outcome: String
    ) = EvidenceSignalEntity(
        signalId = signalId,
        jobId = jobId,
        type = type,
        outcome = outcome,
        verificationLevel = "REMOTE_VERIFIED",
        source = "test",
        claim = "UNKNOWN"
    )

    private fun testPhoto(uri: String, name: String) = PhotoEntity(
        contentUri = uri,
        dateTaken = 100L,
        displayName = name,
        mimeType = "image/jpeg",
        createdAt = 100L,
        updatedAt = 100L
    )
}
