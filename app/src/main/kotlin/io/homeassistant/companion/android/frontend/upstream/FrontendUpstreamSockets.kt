package io.homeassistant.companion.android.frontend.upstream

import android.annotation.SuppressLint
import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private const val SOCKET_ID_BYTES = 4
private const val NORMAL_CLOSURE = 1000
private const val GOING_AWAY = 1001
private const val ABNORMAL_CLOSURE = 1006
private const val MAX_CLOSE_REASON_BYTES = 123
private val APPLICATION_CLOSE_CODES = 3000..4999

private val json = Json { encodeDefaults = true }

/**
 * WebSockets that the app opens on behalf of the page, for servers that reject the page's own
 * WebSockets because their `Origin` is the app-internal origin (Supervisor add-on panels behind ingress,
 * such as go2rtc's stream viewer).
 *
 * Text frames travel as JSON messages; binary frames as raw bytes prefixed with the socket id when the
 * WebView supports binary messages ([binary]), otherwise base64 in JSON. All sockets close when [scope]
 * ends.
 */
// Created by FrontendUpstreamChannel.attach only where WEB_MESSAGE_LISTENER exists; [binary] is
// WEB_MESSAGE_ARRAY_BUFFER
@SuppressLint("RequiresFeature")
internal class FrontendUpstreamSockets(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val binary: Boolean,
) {
    /** One socket of one page; androidx keeps one proxy per document, so equality is identity. */
    private data class SocketKey(val page: JavaScriptReplyProxy, val sid: Int)

    private val sockets = ConcurrentHashMap<SocketKey, WebSocket>()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { closeAll() }
    }

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

    /**
     * Opens the WebSocket returned by [connect] as socket [sid] of the page behind [replyProxy] and
     * forwards its events to the page.
     */
    @MainThread
    fun open(replyProxy: JavaScriptReplyProxy, sid: Int, connect: (WebSocketListener) -> WebSocket?) {
        val key = SocketKey(replyProxy, sid)
        val socket = connect(pageListener(replyProxy, sid, key))
        if (socket ==
            null
        ) {
            post(replyProxy, SocketEvent("wsClosed", sid, code = ABNORMAL_CLOSURE, reason = "Unknown server"))
        } else {
            sockets[key] =
                socket
        }
    }

    private fun post(replyProxy: JavaScriptReplyProxy, event: SocketEvent) {
        scope.launch(mainDispatcher) { replyProxy.postMessage(json.encodeToString(event)) }
    }

    private fun pageListener(replyProxy: JavaScriptReplyProxy, sid: Int, key: SocketKey) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post(
                replyProxy,
                SocketEvent("wsOpened", sid, protocol = response.header("Sec-WebSocket-Protocol").orEmpty()),
            )

            override fun onMessage(webSocket: WebSocket, text: String) =
                post(replyProxy, SocketEvent("wsText", sid, text = text))

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (binary) {
                    val framed = ByteBuffer.allocate(
                        SOCKET_ID_BYTES + bytes.size,
                    ).putInt(sid).put(bytes.asByteBuffer()).array()
                    scope.launch(mainDispatcher) { replyProxy.postMessage(framed) }
                } else {
                    post(replyProxy, SocketEvent("wsText", sid, b64 = bytes.base64()))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(sendableCode(code), sendableReason(reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(key)
                post(replyProxy, SocketEvent("wsClosed", sid, code = code, reason = reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(key)
                post(replyProxy, SocketEvent("wsClosed", sid, code = ABNORMAL_CLOSURE, reason = "Connection failed"))
            }
        }

    /** Sends a text frame (or a base64 binary frame) from the page. */
    fun send(replyProxy: JavaScriptReplyProxy, sid: Int, text: String?, base64: String?) {
        val socket = sockets[SocketKey(replyProxy, sid)] ?: return
        when {
            text != null -> socket.send(text)
            base64 != null -> base64.decodeBase64()?.let(socket::send)
        }
    }

    /** Sends a binary frame from the page: the socket id, then the payload. */
    fun sendBinary(replyProxy: JavaScriptReplyProxy, framed: ByteArray) {
        if (framed.size < SOCKET_ID_BYTES) return
        val sid = ByteBuffer.wrap(framed, 0, SOCKET_ID_BYTES).int
        sockets[SocketKey(replyProxy, sid)]?.send(framed.toByteString(SOCKET_ID_BYTES, framed.size - SOCKET_ID_BYTES))
    }

    /** Closes a socket at the page's request. */
    fun close(replyProxy: JavaScriptReplyProxy, sid: Int, code: Int, reason: String) {
        sockets.remove(SocketKey(replyProxy, sid))?.close(sendableCode(code), sendableReason(reason))
    }

    /** Closes every socket, when the pages that opened them are gone. */
    fun closeAll() {
        sockets.values.forEach { it.close(GOING_AWAY, null) }
        sockets.clear()
    }

    /** Close codes a client may send; others (such as 1005 or 1006) are only ever reported. */
    private fun sendableCode(code: Int) = if (code == NORMAL_CLOSURE ||
        code in APPLICATION_CLOSE_CODES
    ) {
        code
    } else {
        NORMAL_CLOSURE
    }

    /** OkHttp refuses close reasons above 123 bytes. */
    private fun sendableReason(reason: String) = reason.takeIf { it.encodeToByteArray().size <= MAX_CLOSE_REASON_BYTES }
}
