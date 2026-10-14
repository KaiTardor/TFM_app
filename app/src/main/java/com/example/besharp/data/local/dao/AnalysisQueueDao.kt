package com.example.besharp.data.local.dao

// Gestiona en Room las transacciones y estados de evaluaciones, trabajos y evidencias.

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.local.entity.ImageAssessmentEntity
import kotlinx.coroutines.flow.Flow

// Une cada trabajo con su resultado y evidencias.
data class AnalysisJobWithResult(
    @Embedded val job: AnalysisJobEntity,
    @Relation(parentColumn = "jobId", entityColumn = "jobId")
    val result: AuthenticityResultEntity?,
    @Relation(parentColumn = "jobId", entityColumn = "jobId")
    val signals: List<EvidenceSignalEntity> = emptyList()
)

data class ImageAssessmentWithJobs(
    @Embedded val assessment: ImageAssessmentEntity,
    @Relation(
        entity = AnalysisJobEntity::class,
        parentColumn = "assessmentId",
        entityColumn = "assessmentId"
    )
    val jobs: List<AnalysisJobWithResult>
)

data class CreatedAssessmentJobs(
    val assessmentId: Long,
    val jobIds: List<Long>
)

@Dao
interface AnalysisQueueDao {
    @Insert
    suspend fun insertAssessment(assessment: ImageAssessmentEntity): Long

    @Insert
    suspend fun insertJob(job: AnalysisJobEntity): Long

    @Query(
        """
        UPDATE image_assessments
        SET status = 'ACTIVE'
        WHERE assessmentId = :assessmentId AND status = 'PREPARING'
          AND expectedJobCount = (
              SELECT COUNT(*) FROM analysis_jobs WHERE assessmentId = :assessmentId
          )
        """
    )
    suspend fun activatePreparedAssessment(assessmentId: Long): Int

    @Transaction
    suspend fun createAssessmentWithJobs(
        assessment: ImageAssessmentEntity,
        jobs: List<AnalysisJobEntity>
    ): CreatedAssessmentJobs {
        require(jobs.isNotEmpty()) { "Una evaluacion debe incluir trabajos" }
        require(assessment.expectedJobCount == jobs.size) { "El numero de trabajos no coincide" }
        require(assessment.status == com.example.besharp.data.local.entity.ImageAssessmentStatus.PREPARING) {
            "La evaluacion debe crearse en preparacion"
        }
        val assessmentId = insertAssessment(assessment)
        val jobIds = jobs.map { job ->
            insertJob(job.copy(assessmentId = assessmentId))
        }
        check(activatePreparedAssessment(assessmentId) == 1) {
            "No se pudo activar la evaluacion preparada"
        }
        return CreatedAssessmentJobs(assessmentId, jobIds)
    }

    @Insert
    suspend fun insertResult(result: AuthenticityResultEntity)

    @Insert
    suspend fun insertSignals(signals: List<EvidenceSignalEntity>)

    @Query("SELECT * FROM analysis_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJob(jobId: Long): AnalysisJobEntity?

    @Query("SELECT * FROM image_assessments WHERE assessmentId = :assessmentId LIMIT 1")
    suspend fun getAssessment(assessmentId: Long): ImageAssessmentEntity?

    @Transaction
    @Query(
        "SELECT * FROM analysis_jobs WHERE assessmentId = :assessmentId ORDER BY createdAt, jobId"
    )
    suspend fun getJobsForAssessment(assessmentId: Long): List<AnalysisJobWithResult>

    @Query(
        """
        SELECT assessmentId FROM image_assessments
        WHERE status = 'ACTIVE'
          AND expectedJobCount = (
              SELECT COUNT(*) FROM analysis_jobs
              WHERE analysis_jobs.assessmentId = image_assessments.assessmentId
          )
          AND NOT EXISTS (
              SELECT 1 FROM analysis_jobs
              WHERE analysis_jobs.assessmentId = image_assessments.assessmentId
                AND analysis_jobs.status IN ('QUEUED', 'RUNNING')
          )
        """
    )
    suspend fun getReadyAssessmentIds(): List<Long>

    @Query(
        """
        UPDATE image_assessments
        SET status = 'COMPLETED', completedAt = :completedAt,
            finalResolution = :finalResolution, summary = :summary,
            resolutionReason = :reason, resolutionPolicyVersion = :policyVersion,
            decisiveSignalIdsJson = :decisiveSignalIdsJson,
            conflictingSignalIdsJson = :conflictingSignalIdsJson
        WHERE assessmentId = :assessmentId AND status = 'ACTIVE'
          AND expectedJobCount = (
              SELECT COUNT(*) FROM analysis_jobs WHERE assessmentId = :assessmentId
          )
          AND NOT EXISTS (
              SELECT 1 FROM analysis_jobs
              WHERE analysis_jobs.assessmentId = :assessmentId
                AND analysis_jobs.status IN ('QUEUED', 'RUNNING')
          )
        """
    )
    suspend fun completeAssessmentIfActive(
        assessmentId: Long,
        completedAt: Long,
        finalResolution: String,
        summary: String,
        reason: String,
        policyVersion: Int,
        decisiveSignalIdsJson: String,
        conflictingSignalIdsJson: String
    ): Int

    @Query(
        """
        SELECT * FROM analysis_jobs
        WHERE status IN ('QUEUED', 'RUNNING')
        ORDER BY createdAt, jobId
        """
    )
    suspend fun getActiveJobs(): List<AnalysisJobEntity>

    @Query(
        """
        SELECT * FROM analysis_jobs
        WHERE status IN ('QUEUED', 'RUNNING') AND providerId LIKE :providerPrefix || '%'
        ORDER BY createdAt, jobId
        """
    )
    suspend fun getActiveJobsForProviderPrefix(providerPrefix: String): List<AnalysisJobEntity>

    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'RUNNING', startedAt = :startedAt, completedAt = NULL,
            errorMessage = NULL, errorCategory = NULL, httpStatus = NULL
        WHERE jobId = :jobId AND status = 'QUEUED'
        """
    )
    suspend fun transitionQueuedToRunning(jobId: Long, startedAt: Long): Int

    /** Retry explicito de WorkManager; ninguna otra ruta puede reabrir un RUNNING. */
    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'QUEUED', startedAt = NULL, completedAt = NULL,
            errorMessage = NULL, errorCategory = NULL, httpStatus = NULL
        WHERE jobId = :jobId AND status = 'RUNNING'
        """
    )
    suspend fun transitionRunningToQueuedForRetry(jobId: Long): Int

    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'SUCCEEDED', completedAt = :completedAt,
            errorMessage = NULL, errorCategory = NULL, httpStatus = NULL
        WHERE jobId = :jobId AND status = 'RUNNING'
        """
    )
    suspend fun transitionRunningToSucceeded(jobId: Long, completedAt: Long): Int

    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'FAILED', completedAt = :completedAt, errorMessage = :message,
            errorCategory = :category, httpStatus = :httpStatus
        WHERE jobId = :jobId AND status = 'RUNNING'
        """
    )
    suspend fun transitionRunningToFailed(
        jobId: Long,
        completedAt: Long,
        message: String,
        category: String,
        httpStatus: Int?
    ): Int

    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'CANCELLED', completedAt = :completedAt, errorMessage = :message,
            errorCategory = NULL, httpStatus = NULL
        WHERE jobId = :jobId AND status IN ('QUEUED', 'RUNNING')
        """
    )
    suspend fun transitionActiveToCancelled(jobId: Long, completedAt: Long, message: String): Int

    @Query(
        """
        UPDATE analysis_jobs
        SET status = 'CANCELLED', completedAt = :completedAt, errorMessage = :message,
            errorCategory = NULL, httpStatus = NULL
        WHERE status IN ('QUEUED', 'RUNNING')
        """
    )
    suspend fun cancelAllActive(completedAt: Long, message: String): Int

    @Transaction
    suspend fun saveSuccessfulResult(
        jobId: Long,
        completedAt: Long,
        result: AuthenticityResultEntity,
        signals: List<EvidenceSignalEntity> = emptyList()
    ): Boolean {
        if (transitionRunningToSucceeded(jobId, completedAt) != 1) return false
        insertResult(result)
        if (signals.isNotEmpty()) insertSignals(signals)
        return true
    }

    @Transaction
    @Query("SELECT * FROM image_assessments ORDER BY createdAt DESC, assessmentId DESC LIMIT :limit")
    fun observeRecentAssessments(limit: Int): Flow<List<ImageAssessmentWithJobs>>

    @Transaction
    @Query("SELECT * FROM image_assessments WHERE assessmentId = :assessmentId LIMIT 1")
    fun observeAssessment(assessmentId: Long): Flow<ImageAssessmentWithJobs?>

    @Query(
        """
        SELECT DISTINCT imageUri
        FROM image_assessments
        WHERE status = 'COMPLETED' AND finalResolution IS NOT NULL
        """
    )
    fun observeAnalyzedImageUris(): Flow<List<String>>

    @Query("DELETE FROM image_assessments WHERE imageUri = :imageUri")
    suspend fun deleteForImage(imageUri: String)

    @Query(
        """
        DELETE FROM image_assessments
        WHERE imageUri = :imageUri AND status = 'COMPLETED'
          AND assessmentId != :keepAssessmentId
          AND EXISTS (
              SELECT 1 FROM image_assessments
              WHERE assessmentId = :keepAssessmentId
                AND imageUri = :imageUri AND status = 'COMPLETED'
          )
        """
    )
    suspend fun deleteOtherCompletedAssessments(imageUri: String, keepAssessmentId: Long)
}
