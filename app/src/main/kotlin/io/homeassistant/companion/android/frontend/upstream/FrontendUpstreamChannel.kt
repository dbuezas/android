package io.homeassistant.companion.android.frontend.upstream

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val LISTENER = "androidInternalHaUpstream"
private const val SCRIPT_ASSET = "frontend_upstream.js"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true // the page reads "type" on every message
}

/**
 * Adds the page side of [FrontendUpstream] (`assets/frontend_upstream.js`) to WebViews and answers its
 * messages.
 *
 * The script and its message channel are limited to the virtual origins by WebView origin rules, so no
 * other page loaded in the WebView can reach them.
 */
@Singleton
class FrontendUpstreamChannel @VisibleForTesting constructor(
    private val upstream: FrontendUpstream,
    private val http: FrontendUpstreamHttp,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(upstream: FrontendUpstream, http: FrontendUpstreamHttp) :
        this(upstream, http, Dispatchers.IO, Dispatchers.Main)

    /** What [attach] added to a WebView. */
    private class Attachment(val script: ScriptHandler, val sockets: FrontendUpstreamSockets)

    private val attached = WeakHashMap<WebView, Attachment>()

    @Volatile
    private var script: String? = null

    /** A message from the page script. */
    @Serializable
    internal data class PageMessage(
        val type: String,
        val id: Int = 0,
        val method: String = "GET",
        val path: String = "/",
        val headers: List<List<String>> = emptyList(),
        val body: String? = null,
        val uploadId: String? = null,
        val sid: Int = 0,
        val text: String? = null,
        val b64: String? = null,
        val protocols: List<String> = emptyList(),
        val code: Int = 0,
        val reason: String = "",
    )

    /** The app's answer to a [PageMessage] with the same [id]. */
    @Serializable
    internal data class AppMessage(
        val type: String = "response",
        val id: Int,
        val status: Int = 0,
        val statusText: String = "",
        val headers: List<List<String>> = emptyList(),
        val body: String? = null,
        val error: String? = null,
        val token: String? = null,
    )

    /** Everything messages from one WebView are answered with. */
    private class WebViewChannel(val scope: CoroutineScope, val sockets: FrontendUpstreamSockets, val binary: Boolean)

    /** Where a page message came from. */
    private class Sender(val host: String, val replyProxy: JavaScriptReplyProxy) {
        @SuppressLint("RequiresFeature") // the listener that created this proxy exists, see attach
        fun reply(message: AppMessage) = replyProxy.postMessage(json.encodeToString(message))
    }

    /**
     * Adds the script and its message channel to [webView], once. Allows mixed content when one of the
     * server's URLs is plain http ([allowPlainHttp]): the page origin is https and opens ws:// to it.
     */
    @SuppressLint("RequiresFeature")
    suspend fun attach(webView: WebView, scope: CoroutineScope, allowPlainHttp: Boolean) {
        if (!upstream.isSupported) return
        // Runs before every load, so the mode follows the server shown
        webView.settings.mixedContentMode = if (allowPlainHttp) {
            WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        } else {
            // Blocks plain-http scripts, frames and requests. NEVER_ALLOW makes WebView 151 crash natively
            // on the first load of the app-internal origin (seen on a Pixel 7, Android 16)
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        withContext(ioDispatcher) { http.prepare() }
        if (attached.containsKey(webView)) return
        val script = script ?: withContext(ioDispatcher) {
            webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        }.also { script = it }
        val rules = setOf(VirtualOrigin.ORIGIN_RULE)
        http.installServiceWorkerClient()
        val binary = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        val channel = WebViewChannel(scope, FrontendUpstreamSockets(scope, mainDispatcher, binary), binary)
        WebViewCompat.addWebMessageListener(webView, LISTENER, rules) { _, message, sourceOrigin, _, replyProxy ->
            val host = sourceOrigin.host
            // The origin rules already limit this to the virtual origins; frames on them need it too
            if (host == null || VirtualOrigin.serverIdOf(host) == null) return@addWebMessageListener
            if (binary && message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                channel.sockets.sendBinary(replyProxy, message.arrayBuffer)
            } else {
                decode(host, message.data)?.let { handle(it, channel, Sender(host, replyProxy)) }
            }
        }
        attached[webView] =
            Attachment(WebViewCompat.addDocumentStartJavaScript(webView, script, rules), channel.sockets)
    }

    private fun decode(host: String, data: String?): PageMessage? = try {
        json.decodeFromString<PageMessage>(data.orEmpty())
    } catch (e: IllegalArgumentException) {
        // No payload in the log: it can hold request bodies
        Timber.w("Ignoring a page message of server ${VirtualOrigin.serverIdOf(host)}: ${e::class.simpleName}")
        null
    }

    private fun handle(message: PageMessage, channel: WebViewChannel, sender: Sender) {
        val host = sender.host
        when (message.type) {
            "hello" -> channel.scope.launch { upstream.onPageHello(host, sender.replyProxy, channel.binary) }
            "moved" -> upstream.onPageMoved(host)
            "token" -> channel.scope.launch {
                sender.reply(AppMessage(id = message.id, token = upstream.accessToken(host)))
            }
            // The page waits for each chunk's answer before it sends the next one or the request
            "chunk" -> channel.scope.launch(mainDispatcher) {
                val uploadId = message.uploadId
                val body = message.body
                val error = if (uploadId == null || body == null) {
                    "Invalid chunk"
                } else {
                    withContext(ioDispatcher) { http.appendUpload(uploadId, body) }
                }
                sender.reply(AppMessage(id = message.id, error = error))
            }
            "request" -> channel.scope.launch(mainDispatcher) {
                val response = withContext(ioDispatcher) { http.execute(host, message) }
                sender.reply(response)
            }
            "wsOpen" -> channel.sockets.open(sender.replyProxy, message.sid) { listener ->
                http.connectSocket(host = host, path = message.path, protocols = message.protocols, listener = listener)
            }
            "wsSend" -> channel.sockets.send(
                sender.replyProxy,
                sid = message.sid,
                text = message.text,
                base64 = message.b64,
            )
            "wsClose" -> channel.sockets.close(
                sender.replyProxy,
                sid = message.sid,
                code = message.code,
                reason = message.reason,
            )
            else -> Timber.w("Unknown upstream message ${message.type}")
        }
    }

    /** Removes what [attach] added, and closes the sockets the app opened for the WebView's pages. */
    @SuppressLint("RequiresFeature")
    fun detach(webView: WebView) {
        val attachment = attached.remove(webView) ?: return
        attachment.script.remove()
        WebViewCompat.removeWebMessageListener(webView, LISTENER)
        attachment.sockets.closeAll()
    }

    internal companion object {
        @Serializable
        private data class BaseMessage(
            val type: String,
            val ws: String,
            val publicOrigin: String? = null,
            val binary: Boolean = false,
        )

        /**
         * The first message to a page: where its WebSockets go, the address used for links leaving the app,
         * and whether binary messages can be used on the channel.
         */
        fun baseMessage(webSocketBase: String, publicOrigin: String, binary: Boolean): String = json.encodeToString(
            BaseMessage(type = "base", ws = webSocketBase, publicOrigin = publicOrigin, binary = binary),
        )

        /** Tells a page to move its WebSockets to [webSocketBase]. */
        fun switchMessage(webSocketBase: String): String =
            json.encodeToString(BaseMessage(type = "switch", ws = webSocketBase))
    }
}
