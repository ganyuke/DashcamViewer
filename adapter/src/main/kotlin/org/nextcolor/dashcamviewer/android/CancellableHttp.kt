package org.nextcolor.dashcamviewer.android

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Keeps cancellation attached until the response body has been consumed, not just the headers. */
internal suspend fun <T> Call.consumeCancellable(consume: suspend (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val reader =
                        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                            try {
                                val result = response.use { consume(it) }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    reader.invokeOnCompletion { response.close() }
                }
            }
        )
    }
