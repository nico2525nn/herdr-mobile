package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.SemanticEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The daemon's `/v1/events` socket as a cold [Flow].
 *
 * The first signal on every (re)connect is always [Signal.Ready]; the daemon sends
 * `stream.ready` carrying its current sequence cursor and never replays history. Everything
 * after that is [Signal.Event] in strict sequence order until [Signal.Closed].
 */
class EventSocket(
    private val http: OkHttpClient,
    private val endpoint: () -> DaemonEndpoint,
) {
    sealed interface Signal {
        data class Ready(val seq: Long) : Signal
        data class Event(val event: SemanticEvent) : Signal
        data class Closed(val code: Int, val reason: String, val failed: Throwable? = null) : Signal
    }

    fun stream(): Flow<Signal> = callbackFlow {
        val ep = endpoint()
        // Token travels in the Authorization header only, never the query.
        val url = ep.wsUrl("/v1/events")
        val request = Request.Builder()
            .url(url)
            .apply { ep.token?.let { header("Authorization", "Bearer $it") } }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The daemon announces itself with stream.ready; nothing to do here.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val element = try {
                    HerdrJson.parseToJsonElement(text)
                } catch (_: Exception) {
                    return
                }
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
                val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: return
                if (type == SemanticEvent.TYPE_STREAM_READY) {
                    trySend(Signal.Ready(seq))
                } else {
                    val event = try {
                        HerdrJson.decodeFromJsonElement(SemanticEvent.serializer(), obj)
                    } catch (_: Exception) {
                        return
                    }
                    trySend(Signal.Event(event))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Signal.Closed(code, reason))
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Signal.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(Signal.Closed(-1, t.message ?: response?.message.orEmpty(), t))
                close(t)
            }
        }

        val socket = http.newWebSocket(request, listener)

        awaitClose {
            try {
                socket.close(1000, "client done")
            } catch (_: Exception) {
                // Already gone; closing the flow is what matters.
            }
        }
    }
}