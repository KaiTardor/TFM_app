package com.example.besharp.data.repository

// Guarda trabajos y resultados, y cierra la evaluacion conjunta de cada imagen.

import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.analysis.AnalysisErrorCategory
import com.example.besharp.domain.model.*
import com.example.besharp.data.local.dao.AnalysisQueueDao
import com.example.besharp.data.local.entity.AnalysisJobEntity
import com.example.besharp.data.local.entity.AnalysisJobStatus
import com.example.besharp.data.local.entity.AuthenticityResultEntity
import com.example.besharp.data.local.entity.EvidenceSignalEntity
import com.example.besharp.data.local.entity.ImageAssessmentEntity
import com.example.besharp.data.util.enumValueOrDefault
import com.google.gson.Gson
import com.example.besharp.domain.resolution.ResolutionEngine
import com.example.besharp.domain.resolution.ResolutionInput
import com.example.besharp.domain.resolution.VisualEstimate
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class QueuedAnalysisJob(
    val jobId: Long,
    val assessmentId: Long
)

data class AnalysisJobRequest(
    val provider: ProviderDescriptor,
    val customConfiguration: CustomApiConfiguration? = null
)

data class AnalysisExecutionJob(
    val entity: AnalysisJobEntity,
    val imageUri: String,
    val imageDisplayName: String
) {
    val jobId get() = entity.jobId
    val assessmentId get() = entity.assessmentId
    val providerId get() = entity.providerId
    val modelId get() = entity.modelId
    val capability get() = entity.capability
    val inputType get() = entity.inputType
    val executionId get() = entity.executionId
    val destinationFingerprint get() = entity.destinationFingerprint
    val customEndpointUrl get() = entity.customEndpointUrl
    val status get() = entity.status
}

data class CompletedAssessment(
    val assessmentId: Long,
    val imageDisplayName: String,
    val resolution: FinalResolution,
    val hasExecutionErrors: Boolean
)

class AnalysisQueueRepository(
    private val dao: AnalysisQueueDao,
    private val gson: Gson = Gson(),
    private val resolutionEngine: ResolutionEngine = ResolutionEngine(),
    private val onAssessmentCompleted: suspend (Long) -> Unit = {}
) {
    suspend fun getJob(jobId: Long): AnalysisExecutionJob? {
        val entity = dao.getJob(jobId) ?: return null
        return toExecutionJob(entity)
    }

    suspend fun getActiveJobs(): List<AnalysisExecutionJob> =
        dao.getActiveJobs().mapNotNull { entity -> toExecutionJob(entity) }

    suspend fun getActiveJobsForProviderPrefix(providerPrefix: String): List<AnalysisExecutionJob> =
        dao.getActiveJobsForProviderPrefix(providerPrefix)
            .mapNotNull { entity -> toExecutionJob(entity) }

    suspend fun enqueueAssessment(
        imageUri: String,
        imageDisplayName: String,
        requests: List<AnalysisJobRequest>
    ): List<QueuedAnalysisJob> {
        require(requests.isNotEmpty()) { "Selecciona al menos un proveedor" }
        requests.forEach { request ->
            val isCustom = request.provider.providerId.startsWith(
                CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX
            )
            require(!isCustom || request.customConfiguration?.providerId() == request.provider.providerId) {
                "La configuracion custom no coincide con el proveedor"
            }
        }
        val createdAt = System.currentTimeMillis()
        val created = dao.createAssessmentWithJobs(
            assessment = ImageAssessmentEntity(
                imageUri = imageUri,
                imageDisplayName = imageDisplayName,
                expectedJobCount = requests.size,
                createdAt = createdAt
            ),
            jobs = requests.map { request ->
                val provider = request.provider
                val customConfiguration = request.customConfiguration
                AnalysisJobEntity(
                    providerId = provider.providerId,
                    providerName = provider.displayName,
                    modelId = provider.modelId,
                    capability = provider.capability.name,
                    inputType = provider.inputType.name,
                    executionId = UUID.randomUUID().toString(),
                    destinationFingerprint = customConfiguration?.destinationFingerprint()
                        ?: "${provider.providerId}:${provider.modelId}",
                    customEndpointUrl = customConfiguration?.endpointUrl?.trim(),
                    customParametersJson = customConfiguration?.let { gson.toJson(it.parameters) },
                    status = AnalysisJobStatus.QUEUED,
                    createdAt = createdAt
                )
            }
        )
        return created.jobIds.map { jobId -> QueuedAnalysisJob(jobId, created.assessmentId) }
    }

    suspend fun markStarted(jobId: Long): Boolean =
        dao.transitionQueuedToRunning(jobId, System.currentTimeMillis()) == 1

    suspend fun markQueuedForRetry(jobId: Long): Boolean =
        dao.transitionRunningToQueuedForRetry(jobId) == 1

    suspend fun saveSuccess(jobId: Long, result: AuthenticityResult): Boolean {
        val completedAt = System.currentTimeMillis()
        val saved = dao.saveSuccessfulResult(
            jobId = jobId,
            completedAt = completedAt,
            result = AuthenticityResultEntity(
                jobId = jobId,
                verdict = result.verdict.name,
                syntheticProbability = result.syntheticProbability,
                evidenceType = result.provider.evidenceType.name,
                verificationLevel = result.verificationLevel.name,
                summary = result.summary,
                indicatorsJson = gson.toJson(result.indicators),
                analyzedAt = result.analyzedAt,
                durationMillis = result.durationMillis,
                origin = result.origin.name
            ),
            signals = result.evidenceSignals.map { signal -> signal.toEntity(jobId) }
        )
        return saved
    }

    suspend fun saveFailure(
        jobId: Long,
        category: AnalysisErrorCategory = AnalysisErrorCategory.INVALID_RESPONSE,
        httpStatus: Int? = null
    ): Boolean {
        val job = dao.getJob(jobId) ?: return false
        val normalizedStatus = httpStatus?.takeIf { it in 100..599 }
        return dao.transitionRunningToFailed(
            jobId = jobId,
            completedAt = System.currentTimeMillis(),
            message = controlledFailureMessage(job.providerId, category, normalizedStatus),
            category = category.name,
            httpStatus = normalizedStatus
        ) == 1
    }

    suspend fun markCancelled(jobId: Long, reason: String = "Cancelado por el usuario"): Boolean {
        if (dao.getJob(jobId) == null) return false
        return dao.transitionActiveToCancelled(
            jobId = jobId,
            completedAt = System.currentTimeMillis(),
            message = reason
        ) == 1
    }

    suspend fun cancelAllActive() {
        dao.cancelAllActive(
            completedAt = System.currentTimeMillis(),
            message = "Consentimiento remoto revocado"
        )
        finalizeReadyAssessments()
    }

    suspend fun finalizeAssessmentIfReady(assessmentId: Long): CompletedAssessment? {
        val assessment = dao.getAssessment(assessmentId) ?: return null
        if (assessment.status != com.example.besharp.data.local.entity.ImageAssessmentStatus.ACTIVE) return null
        val rows = dao.getJobsForAssessment(assessmentId)
        if (rows.size != assessment.expectedJobCount || rows.any { it.job.status in ACTIVE_JOB_STATUSES }) {
            return null
        }

        val final = resolutionEngine.resolve(
            ResolutionInput(
                visualEstimates = rows.mapNotNull { row ->
                    val result = row.result ?: return@mapNotNull null
                    val capability = enumValueOrDefault(
                        row.job.capability,
                        AnalysisCapability.VISUAL_ANALYSIS
                    )
                    if (capability != AnalysisCapability.VISUAL_ANALYSIS &&
                        capability != AnalysisCapability.LOCAL_CLASSIFIER
                    ) return@mapNotNull null
                    val verdict = enumValueOrDefault(result.verdict, AnalysisVerdict.INCONCLUSIVE)
                    VisualEstimate(
                        syntheticProbability = result.syntheticProbability,
                        isLikelySynthetic = verdict == AnalysisVerdict.LIKELY_SYNTHETIC,
                        isInconclusive = verdict == AnalysisVerdict.INCONCLUSIVE
                    )
                },
                evidenceSignals = rows.flatMap { row -> row.signals.map(EvidenceSignalEntity::toDomain) },
                completedJobCount = rows.count { it.job.status == AnalysisJobStatus.SUCCEEDED },
                failedJobCount = rows.count { it.job.status == AnalysisJobStatus.FAILED },
                cancelledJobCount = rows.count { it.job.status == AnalysisJobStatus.CANCELLED }
            )
        )
        return withContext(NonCancellable) {
            val completed = dao.completeAssessmentIfActive(
                assessmentId = assessmentId,
                completedAt = System.currentTimeMillis(),
                finalResolution = final.resolution.name,
                summary = final.summary,
                reason = final.reason,
                policyVersion = final.policyVersion,
                decisiveSignalIdsJson = gson.toJson(final.decisiveSignalIds),
                conflictingSignalIdsJson = gson.toJson(final.conflictingSignalIds)
            )
            if (completed == 1) {
                onAssessmentCompleted(assessmentId)
                CompletedAssessment(
                    assessmentId = assessmentId,
                    imageDisplayName = assessment.imageDisplayName,
                    resolution = final.resolution,
                    hasExecutionErrors = rows.any {
                        it.job.status == AnalysisJobStatus.FAILED || it.job.status == AnalysisJobStatus.CANCELLED
                    }
                )
            } else null
        }
    }

    suspend fun finalizeReadyAssessments(): List<CompletedAssessment> =
        dao.getReadyAssessmentIds().mapNotNull { assessmentId ->
            finalizeAssessmentIfReady(assessmentId)
        }

    suspend fun deleteForImage(imageUri: String) = dao.deleteForImage(imageUri)
    suspend fun deleteOtherCompletedAssessments(imageUri: String, keepAssessmentId: Long) =
        dao.deleteOtherCompletedAssessments(imageUri, keepAssessmentId)

    private suspend fun toExecutionJob(entity: AnalysisJobEntity): AnalysisExecutionJob? {
        val assessment = dao.getAssessment(entity.assessmentId) ?: return null
        return AnalysisExecutionJob(entity, assessment.imageUri, assessment.imageDisplayName)
    }

    private fun controlledFailureMessage(
        providerId: String,
        category: AnalysisErrorCategory,
        httpStatus: Int?
    ): String {
        val provider = when {
            providerId == "openai" || providerId.startsWith("openai:") -> "OpenAI"
            providerId.startsWith(CustomApiConfiguration.CUSTOM_PROVIDER_PREFIX) -> "La API personalizada"
            else -> "El proveedor de analisis"
        }
        val status = httpStatus?.takeIf { it in 100..599 }?.let { " (HTTP $it)" }.orEmpty()
        return when (category) {
            AnalysisErrorCategory.AUTHENTICATION -> "$provider rechazo las credenciales o permisos$status"
            AnalysisErrorCategory.RATE_LIMIT -> "$provider alcanzo el limite de peticiones o cuota$status"
            AnalysisErrorCategory.QUOTA -> "$provider no tiene cuota API disponible; revisa facturacion y limites$status"
            AnalysisErrorCategory.PROVIDER_UNAVAILABLE -> "$provider no esta disponible temporalmente$status"
            AnalysisErrorCategory.ACCESS_UNAVAILABLE -> "$provider no tiene acceso a esta funcion$status"
            AnalysisErrorCategory.INVALID_INPUT -> "$provider no admite este archivo$status"
            AnalysisErrorCategory.SOURCE_UNAVAILABLE -> "No se puede acceder a la imagen. Revisa sus permisos y que exista"
            AnalysisErrorCategory.LOCAL_STORAGE -> "No se pudo completar el almacenamiento local. Revisa el dispositivo e intentalo de nuevo"
            AnalysisErrorCategory.FILE_TOO_LARGE -> "El archivo supera el tamaño admitido por $provider$status"
            AnalysisErrorCategory.HTTP -> if (status.isEmpty()) {
                "$provider devolvio un error HTTP"
            } else {
                "$provider devolvio un error$status"
            }
            AnalysisErrorCategory.RESPONSE_TOO_LARGE -> "$provider devolvio una respuesta demasiado grande"
            AnalysisErrorCategory.INVALID_RESPONSE -> "$provider devolvio una respuesta no valida"
            AnalysisErrorCategory.CONFIGURATION -> "No se pudo configurar $provider"
        }.take(MAX_ERROR_LENGTH)
    }

    companion object {
        const val MISSING_CONSENT_REASON = "El destino ya no tiene consentimiento remoto"
        private const val MAX_ERROR_LENGTH = 500
        private val ACTIVE_JOB_STATUSES = setOf(AnalysisJobStatus.QUEUED, AnalysisJobStatus.RUNNING)
    }
}
