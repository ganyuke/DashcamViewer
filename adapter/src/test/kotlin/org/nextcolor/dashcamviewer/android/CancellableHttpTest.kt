package org.nextcolor.dashcamviewer.android

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    @Test
    fun cancellingBeforeHeadersCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("late"))
            val client = OkHttpClient()
            val call =
                client.newCall(Request.Builder().url(server.url("/app/getdeviceattr")).build())
            val task = launch { call.consumeCancellable { it.body!!.string() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1_000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun cancellingDuringBodyReadAlsoCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("camera-status").setBodyDelay(5, TimeUnit.SECONDS)
            )
            val client = OkHttpClient()
            val call =
                client.newCall(Request.Builder().url(server.url("/app/getproductinfo")).build())
            val reading = CompletableDeferred<Unit>()
            val task = launch {
                call.consumeCancellable { response ->
                    reading.complete(Unit)
                    response.body!!.string()
                }
            }
            withTimeout(2_000) { reading.await() }
            withTimeout(1_000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
