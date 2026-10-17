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
import java.lang.ref.WeakReference
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

    private val scripts = WeakHashMap<WebView, ScriptHandler>()

    /** Chunks of large uploads are written in the order the page sent them, before the request that uses them. */
    private val uploadWriter = ioDispatcher.limitedParallelism(1)

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
    private class Sender(val host: String, val replyProxy: JavaScriptReplyProxy, val isAlive: () -> Boolean) {
        fun reply(message: AppMessage) {
            if (isAlive()) replyProxy.postMessage(json.encodeToString(message))
        }
    }

    /** Adds the script and its message channel to [webView], once. */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView, scope: CoroutineScope) {
        if (!upstream.isSupported || scripts.containsKey(webView)) return
        val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        val rules = setOf(VirtualOrigin.ORIGIN_RULE)
        val webViewRef = WeakReference(webView)
        // The page origin is https; the internal URL of a server is often plain http
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                runCatching { json.decodeFromString<PageMessage>(message.data.orEmpty()) }.getOrNull()?.let {
                    handle(it, channel, Sender(host, replyProxy) { webViewRef.get() != null })
                }
            }
        }
        scripts[webView] = WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
    }

    private fun handle(message: PageMessage, channel: WebViewChannel, sender: Sender) {
        val host = sender.host
        when (message.type) {
            "hello" -> channel.scope.launch { upstream.onPageHello(host, sender.replyProxy, channel.binary) }
            "moved" -> upstream.onPageMoved(host)
            "token" -> channel.scope.launch {
                sender.reply(AppMessage(id = message.id, token = upstream.accessToken(host)))
            }
            "chunk" -> channel.scope.launch(uploadWriter) {
                message.uploadId?.let { id -> message.body?.let { http.appendUpload(id, it) } }
            }
            "request" -> channel.scope.launch(uploadWriter) {
                val response = withContext(ioDispatcher) { http.execute(host, message) }
                withContext(mainDispatcher) { sender.reply(response) }
            }
            "wsOpen" -> channel.sockets.open(sender.replyProxy, message.sid) { listener ->
                http.connectSocket(host, message.path, message.protocols, listener)
            }
            "wsSend" -> channel.sockets.send(sender.replyProxy, message.sid, message.text, message.b64)
            "wsClose" -> channel.sockets.close(sender.replyProxy, message.sid, message.code, message.reason)
            else -> Timber.w("Unknown upstream message ${message.type}")
        }
    }

    /** Removes what [attach] added. */
    @SuppressLint("RequiresFeature")
    fun detach(webView: WebView) {
        val script = scripts.remove(webView) ?: return
        script.remove()
        WebViewCompat.removeWebMessageListener(webView, LISTENER)
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
        fun baseMessage(webSocketBase: String, publicOrigin: String, binary: Boolean): String =
            json.encodeToString(BaseMessage("base", webSocketBase, publicOrigin, binary))

        /** Tells a page to move its WebSockets to [webSocketBase]. */
        fun switchMessage(webSocketBase: String): String = json.encodeToString(BaseMessage("switch", webSocketBase))
    }
}
