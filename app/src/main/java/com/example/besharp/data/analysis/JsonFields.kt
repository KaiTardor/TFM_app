package com.example.besharp.data.analysis

// Valida tipos y limites de los campos JSON recibidos de los proveedores.

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

internal fun JsonObject.stringOrNull(name: String, limit: Int = 500): String? {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw AnalysisProviderException("El campo $name debe ser texto")
    }
    val text = value.asString.trim()
    if (text.length > limit) throw AnalysisProviderException("El campo $name es demasiado largo")
    return text.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.objectOrNull(name: String): JsonObject? {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (!value.isJsonObject) throw AnalysisProviderException("El campo $name debe ser un objeto")
    return value.asJsonObject
}

internal fun JsonObject.arrayOrNull(name: String): JsonArray? {
    val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (!value.isJsonArray) throw AnalysisProviderException("El campo $name debe ser una lista")
    return value.asJsonArray
}

internal fun parseProvenanceTimestamp(value: String): Long = try {
    Instant.parse(value).toEpochMilli()
} catch (_: Exception) {
    throw AnalysisProviderException("La fecha de procedencia no tiene un formato ISO-8601 valido")
}
