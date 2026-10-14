package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelCatalogServiceTest {
    private val service = ProviderModelCatalogService()

    @Test
    fun filtersGeminiModelsWithoutGenerateContent() {
        val models = service.parseGeminiModels(
            """
            {
              "models": [
                {"name":"models/gemini-3-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3-pro","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3-flash-lite","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3-flash-preview-2026-01","supportedGenerationMethods":["generateContent"]},
                {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
                {"name":"models/gemini-old","supportedGenerationMethods":["countTokens"]}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("gemini-3-pro", "gemini-3-flash", "gemini-3-flash-lite"),
            models
        )
    }
}
