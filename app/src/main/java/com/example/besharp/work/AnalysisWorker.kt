package com.example.besharp.work

// Ejecuta un trabajo en segundo plano y guarda su resultado o fallo.

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.besharp.BeSharpApplication
import com.example.besharp.data.analysis.AnalysisImagePreprocessor
import com.example.besharp.data.analysis.AnalysisProviderException
import com.example.besharp.data.analysis.AnalysisErrorCategory
import com.example.besharp.data.analysis.ImagePreparationException
import com.example.besharp.domain.model.AnalysisInputType
import com.example.besharp.data.analysis.NormalizedImageAnalysisProvider
import com.example.besharp.data.analysis.OriginalMediaAnalysisProvider
import com.example.besharp.data.analysis.OriginalMediaPreparer
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.repository.CompletedAssessment
import com.example.besharp.data.repository.AnalysisQueueRepository
import com.example.besharp.notifications.NotificationHelper
import com.example.besharp.data.repository.toConsentTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            execute()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "No se pudo completar el estado persistido del trabajo", exception)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    // Un RUNNING recuperado no se reenvia: puede haber llegado al proveedor antes de la interrupcion.
    private suspend fun execute(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId <= 0) return Result.failure()

        val container = (applicationContext as BeSharpApplication).container
        val repository = container.analysisQueueRepository
        for (completed in repository.finalizeReadyAssessments()) {
            notifyCompletion(completed)
        }
        val job = repository.getJob(jobId) ?: return Result.failure()
        if (job.status !in setOf(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING)) {
            return Result.success()
        }
        if (job.status == AnalysisJobStatus.RUNNING) {
            repository.markCancelled(jobId, "Ejecucion interrumpida; no se ha reenviado automaticamente")
            repository.finalizeAssessmentIfReady(job.assessmentId)?.let { notifyCompletion(it) }
            return Result.success()
        }

        val consentStore = container.remoteAnalysisConsentStore
        val consentTarget = job.toConsentTarget()
        if (!consentStore.hasConsentFor(consentTarget)) {
            cancelWithoutConsent(repository, jobId, job.assessmentId)
            return Result.success()
        }

        val result = try {
            if (!repository.markStarted(jobId)) return Result.success()
            val provider = container.providerResolver.create(job.entity)
            // El consentimiento puede haber cambiado desde la primera comprobacion.
            if (!consentStore.hasConsentFor(consentTarget)) {
                cancelWithoutConsent(repository, jobId, job.assessmentId)
                return Result.success()
            }
            val inputType = runCatching { AnalysisInputType.valueOf(job.inputType) }
                .getOrDefault(provider.descriptor.inputType)
            val result = AnalysisConcurrencyLimiter.run {
                if (!maySend(jobId)) return@run null
                when (inputType) {
                    AnalysisInputType.NORMALIZED_IMAGE -> {
                        val visualProvider = provider as? NormalizedImageAnalysisProvider
                            ?: error("El proveedor no acepta imagenes normalizadas")
                        val input = AnalysisImagePreprocessor.prepareCached(
                            applicationContext,
                            job.assessmentId,
                            Uri.parse(job.imageUri)
                        )
                        if (maySend(jobId)) visualProvider.analyze(input, job.executionId) else null
                    }
                    AnalysisInputType.ORIGINAL_FILE -> {
                        val originalProvider = provider as? OriginalMediaAnalysisProvider
                            ?: error("El proveedor no acepta archivos originales")
                        OriginalMediaPreparer.prepare(
                            applicationContext,
                            Uri.parse(job.imageUri),
                            job.imageDisplayName,
                            originalProvider.supportedMimeTypes,
                            originalProvider.maxInputBytes,
                            provider.descriptor.displayName
                        ).use { input ->
                            if (maySend(jobId)) originalProvider.analyze(input, job.executionId) else null
                        }
                    }
                }
            } ?: return Result.success()
            result
        } catch (exception: CancellationException) {
            // Conservamos RUNNING: tras una interrupcion no sabemos si el proveedor recibio el envio.
            throw exception
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "analysis failed jobId=$jobId assessmentId=${job.assessmentId} " +
                    "provider=${job.providerId} model=${job.modelId} " +
                    "stage=${(exception as? ImagePreparationException)?.stage ?: "provider"}",
                exception
            )
            return if (AnalysisExecutionPolicy.shouldRetryRequest(job.providerId, exception) && runAttemptCount < MAX_RETRIES) {
                (exception as? AnalysisProviderException)?.retryAfterMillis?.let { delay(it) }
                if (repository.markQueuedForRetry(jobId)) Result.retry() else Result.success()
            } else {
                val providerError = exception as? AnalysisProviderException
                val saved = repository.saveFailure(
                    jobId = jobId,
                    category = providerError?.category ?: errorCategory(exception),
                    httpStatus = providerError?.httpStatus
                )
                if (saved) {
                    repository.finalizeAssessmentIfReady(job.assessmentId)?.let { completed ->
                        notifyCompletion(completed)
                    }
                    Result.failure()
                } else {
                    Result.success()
                }
            }
        }
        // Un fallo de persistencia no es un fallo del proveedor ni cambia un resultado ya guardado.
        val completed = withContext(NonCancellable) {
            if (repository.saveSuccess(jobId, result)) {
                repository.finalizeAssessmentIfReady(job.assessmentId)
            } else {
                null
            }
        }
        completed?.let { notifyCompletion(it) }
        return Result.success()
    }

    // Revalida el estado y el consentimiento tras la espera y la preparacion, antes del envio remoto.
    private suspend fun maySend(jobId: Long): Boolean {
        currentCoroutineContext().ensureActive()
        val container = (applicationContext as BeSharpApplication).container
        val repository = container.analysisQueueRepository
        val current = repository.getJob(jobId) ?: return false
        currentCoroutineContext().ensureActive()
        if (current.status != AnalysisJobStatus.RUNNING) return false
        if (container.remoteAnalysisConsentStore.hasConsentFor(current.toConsentTarget())) return true
        cancelWithoutConsent(repository, jobId, current.assessmentId)
        return false
    }

    private suspend fun cancelWithoutConsent(
        repository: AnalysisQueueRepository,
        jobId: Long,
        assessmentId: Long
    ) {
        repository.markCancelled(jobId, AnalysisQueueRepository.MISSING_CONSENT_REASON)
        repository.finalizeAssessmentIfReady(assessmentId)?.let { notifyCompletion(it) }
    }

    private fun errorCategory(exception: Exception): AnalysisErrorCategory = when {
        exception is IOException || exception.cause is IOException ->
            AnalysisErrorCategory.PROVIDER_UNAVAILABLE
        exception is IllegalArgumentException -> AnalysisErrorCategory.INVALID_INPUT
        else -> AnalysisErrorCategory.INVALID_RESPONSE
    }

    private suspend fun notifyCompletion(completed: CompletedAssessment) {
        try {
            if (!NotificationHelper.isAppInForeground) {
                NotificationHelper.showAssessmentNotification(
                    context = applicationContext,
                    assessmentId = completed.assessmentId,
                    imageName = completed.imageDisplayName,
                    resolution = completed.resolution,
                    hasExecutionErrors = completed.hasExecutionErrors
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // El resultado ya esta guardado; un aviso fallido no cambia su estado.
            Log.e(TAG, "No se pudo publicar el aviso de la evaluacion", exception)
        }
    }

    companion object {
        const val KEY_JOB_ID = "analysis_job_id"
        private const val MAX_RETRIES = 2
        private const val TAG = "BeSharpWorker"
    }
}
