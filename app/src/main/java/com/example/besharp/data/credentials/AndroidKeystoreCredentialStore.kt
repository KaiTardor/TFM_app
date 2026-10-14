package com.example.besharp.data.credentials

// Guarda credenciales cifradas con una clave no exportable de Android Keystore.

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreCredentialStore(context: Context) : ProviderCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun hasCredential(provider: CredentialProvider): Boolean =
        preferences.contains(ciphertextKey(provider)) && preferences.contains(ivKey(provider))

    @Synchronized
    override fun getCredential(provider: CredentialProvider): String? {
        val encryptedValue = preferences.getString(ciphertextKey(provider), null) ?: return null
        val ivValue = preferences.getString(ivKey(provider), null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivValue, Base64.NO_WRAP))
                )
                updateAAD(provider.storageKey.toByteArray(StandardCharsets.UTF_8))
            }
            String(
                cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            )
        } catch (exception: Exception) {
            throw CredentialStorageException(
                "No se pudo leer la credencial de ${provider.displayName}",
                exception
            )
        }
    }

    @Synchronized
    override fun saveCredential(provider: CredentialProvider, credential: String) {
        val normalizedCredential = credential.trim()
        require(normalizedCredential.isNotEmpty()) { "La credencial no puede estar vacia" }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
                updateAAD(provider.storageKey.toByteArray(StandardCharsets.UTF_8))
            }
            val encryptedValue = cipher.doFinal(
                normalizedCredential.toByteArray(StandardCharsets.UTF_8)
            )
            preferences.edit()
                .putString(
                    ciphertextKey(provider),
                    Base64.encodeToString(encryptedValue, Base64.NO_WRAP)
                )
                .putString(ivKey(provider), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
                .also { committed -> check(committed) { "SharedPreferences rechazo la credencial" } }
        } catch (exception: Exception) {
            throw CredentialStorageException(
                "No se pudo guardar la credencial de ${provider.displayName}",
                exception
            )
        }
    }

    @Synchronized
    override fun deleteCredential(provider: CredentialProvider) {
        preferences.edit()
            .remove(ciphertextKey(provider))
            .remove(ivKey(provider))
            .commit()
            .also { committed -> check(committed) { "SharedPreferences rechazo el borrado" } }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun ciphertextKey(provider: CredentialProvider) = "${provider.storageKey}_ciphertext"
    private fun ivKey(provider: CredentialProvider) = "${provider.storageKey}_iv"

    companion object {
        const val PREFERENCES_NAME = "besharp_secure_credentials"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "besharp_provider_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
