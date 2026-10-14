package com.example.besharp.application.analysis

// Coordina la programacion, cancelacion y recuperacion de lotes de analisis.

import com.example.besharp.data.repository.AnalysisJobRequest
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.data.repository.QueuedAnalysisJob
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.privacy.RemoteConsentStore
import com.example.besharp.data.repository.toConsentTarget
import com.example.besharp.work.AnalysisScheduler
import com.example.besharp.work.AnalysisWorkState
import com.example.besharp.data.analysis.AnalysisErrorCategory
import kotlinx.coroutines.CancellationException

data class AnalysisBatchImage(
    val uri: String,
    val displayName: String
)

class QueueAnalysisBatchUseCase(
    private val repository: AnalysisQueueRepository,
    private val scheduler: AnalysisScheduler
) {
    suspend fun execute(
        images: List<AnalysisBatchImage>,
        requests: List<AnalysisJobRequest>,
        ensureActive: () -> Unit,
        onPrepared: (List<QueuedAnalysisJob>) -> Unit
    ) {
        require(images.isNotEmpty())
        require(requests.isNotEmpty())
        val prepared = buildList {
            images.forEach { image ->
                ensureActive()
                val assessment = repository.enqueueAssessment(
                    imageUri = image.uri,
                    imageDisplayName = image.displayName,
                    requests = requests
                )
                addAll(assessment.jobs)
                onPrepared(assessment.jobs)
            }
        }
        prepared.forEach { job ->
            ensureActive()
            scheduler.enqueue(job.jobId)
            ensureActive()
        }
    }
}

class CancelAnalysisJobsUseCase(
    private val repository: AnalysisQueueRepository,
    private val scheduler: AnalysisScheduler
) {
    suspend fun execute(jobIds: Collection<Long>, reason: String) {
        var failure: Exception? = null
        jobIds.forEach { jobId ->
            try {
                repository.markCancelled(jobId, reason)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (failure == null) failure = exception
            }
            try {
                scheduler.cancel(jobId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (failure == null) failure = exception
            }
        }
        try {
            repository.finalizeReadyAssessments()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (failure == null) failure = exception
        }
        failure?.let { throw it }
    }
}

class ReconcileAnalysisJobsUseCase(
    private val repository: AnalysisQueueRepository,
    private val scheduler: AnalysisScheduler,
    private val consentStore: RemoteConsentStore
) {
    // Solo devolvemos si termino sin errores; el historial se observa desde Room.
    suspend fun execute(): Boolean {
        repository.finalizeReadyAssessments()
        var completedWithoutErrors = true
        repository.getActiveJobs().forEach { job ->
            try {
                val target = job.toConsentTarget()
                val invalidCustomSnapshot =
                    job.providerId.startsWith(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX) &&
                        job.customEndpointUrl == null
                if (invalidCustomSnapshot || !consentStore.hasConsentFor(target)) {
                    repository.markCancelled(job.jobId, AnalysisQueueRepository.MISSING_CONSENT_REASON)
                    scheduler.cancel(job.jobId)
                    return@forEach
                }
                val state = scheduler.workState(job.jobId)
                if (state == AnalysisWorkState.FINISHED) {
                    if (job.status == AnalysisJobStatus.QUEUED) repository.markStarted(job.jobId)
                    repository.saveFailure(job.jobId, AnalysisErrorCategory.LOCAL_STORAGE)
                    return@forEach
                }
                if (state == AnalysisWorkState.ACTIVE) return@forEach
                // La ausencia no distingue un trabajo sin programar de un historial podado.
                // No reabrimos una ejecucion cuyo numero de intentos ya no conocemos.
                repository.markCancelled(
                    job.jobId,
                    "No se conserva el historial del trabajo; no se ha reenviado automaticamente"
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                completedWithoutErrors = false
            }
        }
        repository.finalizeReadyAssessments()
        return completedWithoutErrors
    }
}
