package io.homeassistant.companion.android.frontend.upstream

import android.util.Base64
import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

private const val SOCKET_ID_BYTES = 4
private const val NORMAL_CLOSURE = 1000

private val json = Json { encodeDefaults = true }

/**
 * WebSockets that the app opens on behalf of the page, for servers that reject the page's own
 * WebSockets because their `Origin` is the app-internal origin (Supervisor add-on panels behind ingress,
 * such as go2rtc's stream viewer).
 *
 * Text frames travel as JSON messages; binary frames as raw bytes prefixed with the socket id when the
 * WebView supports binary messages ([binary]), otherwise base64 in JSON.
 */
internal class FrontendUpstreamSockets(private val scope: CoroutineScope, private val binary: Boolean) {
    private val sockets = ConcurrentHashMap<String, WebSocket>()

    @Serializable
    private data class SocketEvent(
        val type: String,
        val sid: Int,
        val text: String? = null,
        val b64: String? = null,
        val code: Int = 0,
        val reason: String = "",
        val protocol: String = "",
    )

    /** Opens [socket] for the page behind [replyProxy] and forwards its frames to the page. */
    @MainThread
    fun open(replyProxy: JavaScriptReplyProxy, sid: Int, connect: (WebSocketListener) -> WebSocket?) {
        val key = key(replyProxy, sid)
        val post: (SocketEvent) -> Unit = { event ->
            scope.launch(Dispatchers.Main) { replyProxy.postMessage(json.encodeToString(event)) }
        }
        val socket = connect(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) =
                    post(SocketEvent("wsOpened", sid, protocol = response.header("Sec-WebSocket-Protocol").orEmpty()))

                override fun onMessage(webSocket: WebSocket, text: String) = post(SocketEvent("wsText", sid, text = text))

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (binary) {
                        val framed = ByteBuffer.allocate(SOCKET_ID_BYTES + bytes.size).putInt(sid).put(bytes.asByteBuffer()).array()
                        scope.launch(Dispatchers.Main) { replyProxy.postMessage(framed) }
                    } else {
                        post(SocketEvent("wsText", sid, b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(sendableCode(code), reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(key)
                    post(SocketEvent("wsClosed", sid, code = code, reason = reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(key)
                    post(SocketEvent("wsClosed", sid, code = ABNORMAL_CLOSURE, reason = t.message.orEmpty()))
                }
            },
        )
        if (socket == null) post(SocketEvent("wsClosed", sid, code = ABNORMAL_CLOSURE, reason = "Unknown server")) else sockets[key] = socket
    }

    /** Sends a text frame (or a base64 binary frame) from the page. */
    fun send(replyProxy: JavaScriptReplyProxy, sid: Int, text: String?, base64: String?) {
        val socket = sockets[key(replyProxy, sid)] ?: return
        if (text != null) socket.send(text) else if (base64 != null) socket.send(Base64.decode(base64, Base64.NO_WRAP).toByteString())
    }

    /** Sends a binary frame from the page: the socket id, then the payload. */
    fun sendBinary(replyProxy: JavaScriptReplyProxy, framed: ByteArray) {
        if (framed.size < SOCKET_ID_BYTES) return
        val sid = ByteBuffer.wrap(framed, 0, SOCKET_ID_BYTES).int
        sockets[key(replyProxy, sid)]?.send(framed.toByteString(SOCKET_ID_BYTES, framed.size - SOCKET_ID_BYTES))
    }

    /** Closes a socket at the page's request. */
    fun close(replyProxy: JavaScriptReplyProxy, sid: Int, code: Int, reason: String) {
        sockets.remove(key(replyProxy, sid))?.close(sendableCode(code), reason)
    }

    /** Close codes a client may send; others (such as 1005 or 1006) are only ever reported. */
    private fun sendableCode(code: Int) = if (code == NORMAL_CLOSURE || code in APPLICATION_CODES) code else NORMAL_CLOSURE

    private fun key(replyProxy: JavaScriptReplyProxy, sid: Int) = "${System.identityHashCode(replyProxy)}:$sid"

    private companion object {
        const val ABNORMAL_CLOSURE = 1006
        val APPLICATION_CODES = 3000..4999
    }
}
