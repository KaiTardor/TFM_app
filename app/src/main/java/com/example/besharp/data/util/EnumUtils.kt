package com.example.besharp.data.util

// Convierte valores guardados a enums con un resultado seguro si no se reconocen.

inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    runCatching { enumValueOf<T>(value) }.getOrNull()
