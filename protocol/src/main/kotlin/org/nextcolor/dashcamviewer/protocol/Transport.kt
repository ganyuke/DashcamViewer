package org.nextcolor.dashcamviewer.protocol

import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl

data class HttpReply(val status: Int, val headers: Map<String, String>, val body: ByteArray)
interface CameraTransport {
    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): HttpReply
    suspend fun stream(path: String, sink: TransferSink, onBytes: (Long, Long?) -> Unit)
    fun openSocket(): CameraSocket
}
interface CameraSocket {
    suspend fun connect()
    suspend fun write(bytes: ByteArray)
    suspend fun readSome(timeoutMillis: Int): ByteArray
    suspend fun close()
}
interface LiveVideoSource {
    suspend fun collect(emit: suspend (VideoEvent) -> Unit)
    suspend fun stop()
}
fun interface Clock { fun nowMillis(): Long }
interface A13Client {
    suspend fun readSnapshot(): CameraSnapshot
    suspend fun listFiles(folder: MediaFolder, start: Int, limit: Int = 50): MediaPage
    fun download(file: RemoteMedia, sink: TransferSink): Flow<TransferEvent>
    fun liveVideo(): Flow<VideoEvent>
    suspend fun prepare(command: CameraCommand): PreparedAction
    suspend fun execute(action: PreparedAction, confirmation: ActionConfirmation): CommandOutcome
}
