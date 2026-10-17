package io.homeassistant.companion.android.frontend.upstream

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap
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
 * Registers the page side of [FrontendUpstream] (`assets/frontend_upstream.js`) on WebViews and answers
 * its messages.
 *
 * The script and its message channel are limited to the virtual origins by WebView origin rules, so no
 * other page loaded in the WebView can reach them.
 */
internal object FrontendUpstreamScript {
    private val scripts = WeakHashMap<WebView, ScriptHandler>()

    /** Chunks of large uploads are written in the order the page sent them. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val uploadWriter = Dispatchers.IO.limitedParallelism(1)

    /** A message from the page script. */
    @Serializable
    internal data class BridgeRequest(
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

    /** The app's answer to a [BridgeRequest] with the same [id]. */
    @Serializable
    internal data class BridgeResponse(
        val type: String = "response",
        val id: Int,
        val status: Int = 0,
        val statusText: String = "",
        val headers: List<List<String>> = emptyList(),
        val body: String? = null,
        val error: String? = null,
        val token: String? = null,
    )

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

    /** Adds the script and its message channel to [webView], once. */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView, upstream: FrontendUpstream, scope: CoroutineScope) {
        if (!upstream.isSupported || scripts.containsKey(webView)) return
        val script = webView.context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        val rules = setOf(upstream.originRule)
        val webViewRef = WeakReference(webView)
        // The page origin is https; the internal URL of a server is often plain http
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        upstream.installServiceWorkerClient()
        val binary = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        val sockets = FrontendUpstreamSockets(scope, binary)
        WebViewCompat.addWebMessageListener(webView, LISTENER, rules) { _, message, sourceOrigin, _, replyProxy ->
            val host = sourceOrigin.host
            // The origin rules already limit this to the virtual origins; frames on them need it too
            if (host == null || !upstream.isVirtualHost(host)) return@addWebMessageListener
            if (binary && message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                sockets.sendBinary(replyProxy, message.arrayBuffer)
                return@addWebMessageListener
            }
            val call = runCatching { json.decodeFromString<BridgeRequest>(message.data.orEmpty()) }.getOrNull()
                ?: return@addWebMessageListener
            val context = Context(host, upstream, scope, replyProxy, sockets, binary) { webViewRef.get() != null }
            handle(call, context)
        }
        scripts[webView] = WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
    }

    /** Where a page message came from and what answers it. */
    internal class Context(
        val host: String,
        val upstream: FrontendUpstream,
        val scope: CoroutineScope,
        val replyProxy: JavaScriptReplyProxy,
        val sockets: FrontendUpstreamSockets,
        val binary: Boolean,
        val isAlive: () -> Boolean,
    ) {
        fun reply(response: BridgeResponse) {
            if (isAlive()) replyProxy.postMessage(json.encodeToString(response))
        }
    }

    @VisibleForTesting
    internal fun handle(call: BridgeRequest, context: Context) {
        val upstream = context.upstream
        val host = context.host
        when (call.type) {
            "hello" -> context.scope.launch { upstream.onPageHello(host, context.replyProxy, context.binary) }
            "moved" -> upstream.onPageMoved(host)
            "chunk" -> context.scope.launch(uploadWriter) {
                call.uploadId?.let { id -> call.body?.let { upstream.appendUpload(id, it) } }
            }
            // Runs after the chunks sent before it are written
            "request" -> context.scope.launch(uploadWriter) {
                val response = withContext(Dispatchers.IO) { upstream.execute(host, call) }
                withContext(Dispatchers.Main) { context.reply(response) }
            }
            "token" -> context.scope.launch { context.reply(BridgeResponse(id = call.id, token = upstream.accessToken(host))) }
            "wsOpen" -> context.sockets.open(context.replyProxy, call.sid) { listener ->
                upstream.connectSocket(host, call.path, call.protocols, listener)
            }
            "wsSend" -> context.sockets.send(context.replyProxy, call.sid, call.text, call.b64)
            "wsClose" -> context.sockets.close(context.replyProxy, call.sid, call.code, call.reason)
            else -> Timber.w("Unknown upstream message ${call.type}")
        }
    }

    /** Removes what [attach] added. */
    @SuppressLint("RequiresFeature")
    fun detach(webView: WebView, upstream: FrontendUpstream) {
        val script = scripts.remove(webView) ?: return
        script.remove()
        if (upstream.isSupported) WebViewCompat.removeWebMessageListener(webView, LISTENER)
    }
}
