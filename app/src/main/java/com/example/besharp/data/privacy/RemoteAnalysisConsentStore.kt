package com.example.besharp.data.privacy

// Guarda y comprueba el permiso de envio para cada destino remoto.

import android.content.Context
import com.example.besharp.domain.model.AnalysisCapability
import com.example.besharp.domain.model.AnalysisInputType

data class RemoteConsentTarget(
    val providerId: String,
    val destinationFingerprint: String,
    val capability: AnalysisCapability,
    val inputType: AnalysisInputType
) {
    // Cambiar el destino, la capacidad o el archivo enviado requiere otro permiso.
    val key: String = "$providerId|$destinationFingerprint|${capability.name}|${inputType.name}"
}

data class RemoteConsentRecord(
    val policyVersion: Int,
    val acceptedAt: Long,
    val destinationKeys: Set<String>
) {
    fun matches(targets: Set<RemoteConsentTarget>): Boolean =
        policyVersion == RemoteAnalysisConsentStore.CURRENT_POLICY_VERSION &&
            targets.all { it.key in destinationKeys }
}

interface RemoteConsentStore {
    fun getCurrentConsent(): RemoteConsentRecord?
    fun hasConsentFor(targets: Set<RemoteConsentTarget>): Boolean
    fun hasConsentFor(target: RemoteConsentTarget): Boolean
    fun accept(targets: Set<RemoteConsentTarget>): RemoteConsentRecord
    fun revoke()
}

class RemoteAnalysisConsentStore(context: Context) : RemoteConsentStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun getCurrentConsent(): RemoteConsentRecord? {
        val version = preferences.getInt(KEY_POLICY_VERSION, 0)
        val acceptedAt = preferences.getLong(KEY_ACCEPTED_AT, 0L)
        val destinations = preferences.getStringSet(KEY_DESTINATIONS, null)?.toSet().orEmpty()
        return if (version == CURRENT_POLICY_VERSION && acceptedAt > 0L && destinations.isNotEmpty()) {
            RemoteConsentRecord(version, acceptedAt, destinations)
        } else {
            null
        }
    }

    override fun hasConsentFor(targets: Set<RemoteConsentTarget>): Boolean =
        targets.isNotEmpty() && getCurrentConsent()?.matches(targets) == true

    override fun hasConsentFor(target: RemoteConsentTarget): Boolean =
        hasConsentFor(setOf(target))

    // Confirma la escritura de destinos y version antes de dar el consentimiento por guardado.
    override fun accept(targets: Set<RemoteConsentTarget>): RemoteConsentRecord {
        require(targets.isNotEmpty()) { "El consentimiento debe incluir al menos un destino" }
        val record = RemoteConsentRecord(
            CURRENT_POLICY_VERSION,
            System.currentTimeMillis(),
            targets.mapTo(mutableSetOf()) { it.key }
        )
        check(preferences.edit()
            .putInt(KEY_POLICY_VERSION, record.policyVersion)
            .putLong(KEY_ACCEPTED_AT, record.acceptedAt)
            .putStringSet(KEY_DESTINATIONS, record.destinationKeys)
            .commit()) { "No se pudo guardar el consentimiento remoto" }
        return record
    }

    override fun revoke() {
        check(preferences.edit().clear().commit()) {
            "No se pudo revocar el consentimiento remoto"
        }
    }

    companion object {
        const val CURRENT_POLICY_VERSION = 3
        private const val PREFERENCES_NAME = "besharp_remote_consent"
        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_ACCEPTED_AT = "accepted_at"
        private const val KEY_DESTINATIONS = "destination_keys"
    }
}
