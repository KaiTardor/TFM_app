package com.example.besharp.data.credentials

enum class CredentialProvider(
    val storageKey: String,
    val displayName: String
) {
    OPENAI("openai", "OpenAI"),
    CUSTOM_API("custom_api", "API personalizada")
}

interface ProviderCredentialStore {
    fun hasCredential(provider: CredentialProvider): Boolean
    fun getCredential(provider: CredentialProvider): String?
    fun saveCredential(provider: CredentialProvider, credential: String)
    fun deleteCredential(provider: CredentialProvider)
}

class CredentialStorageException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
