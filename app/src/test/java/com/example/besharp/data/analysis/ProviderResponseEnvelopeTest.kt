package com.example.besharp.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderResponseEnvelopeTest {
    @Test
    fun geminiSkipsNonTextPartsBeforeResult() {
        val provider = GeminiAnalysisProvider("key")
        val text = provider.extractModelText(
            """
            {
              "candidates": [{
                "content": {"parts": [
                  {"thought": true, "text": "razonamiento interno"},
                  {"text": "{\"summary\":\"Sin herramientas disponibles.\"}"}
                ]}
              }]
            }
            """.trimIndent()
        )

        assertEquals("{\"summary\":\"Sin herramientas disponibles.\"}", text)
    }
}
