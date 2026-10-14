package com.example.besharp.data.analysis

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OkHttpSuspendTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun readsResponseAndClosesBody() = runBlocking {
        val bodyClosed = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    bodyClosed.set(true)
                }
            })
            .build()
        server.enqueue(MockResponse().setBody("ok"))

        val response = client.newCall(Request.Builder().url(server.url("/")).build())
            .awaitBoundedResponse()

        assertEquals("ok", response.body.toString(Charsets.UTF_8))
        assertTrue(bodyClosed.get())
    }

    @Test
    fun rejectsOversizedChunkedResponse() {
        server.enqueue(
            MockResponse().setChunkedBody("x".repeat(MAX_HTTP_RESPONSE_BYTES + 1), 1024)
        )

        assertThrows(ResponseTooLargeException::class.java) {
            runBlocking {
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
                    .awaitBoundedResponse()
            }
        }
    }

    @Test
    fun coroutineCancellationCancelsCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitBoundedResponse() }
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(job.isCancelled)
    }
}
