package io.homeassistant.companion.android.frontend.upstream

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.homeassistant.companion.android.util.sensitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/** Reserved top-level domain (ICANN, 2024): it never resolves on the internet. */
private const val VIRTUAL_DOMAIN = "homeassistant.internal"
private const val VIRTUAL_HOST_PREFIX = "server-"
private const val CACHE_DIR = "frontend_upstream"
private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val READ_TIMEOUT_SECONDS = 60L
private const val STATUS_BAD_GATEWAY = 502
private const val STATUS_METHOD_NOT_ALLOWED = 405

/** Request headers the WebView sends that must not reach the server as they are. */
private val DROPPED_REQUEST_HEADERS = setOf("host", "accept-encoding", "if-none-match", "if-modified-since", "origin", "referer")

/** Response headers that describe the transfer, which OkHttp has already handled. */
private val DROPPED_RESPONSE_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "connection")

/**
 * Serves the Home Assistant frontend from a fixed origin that exists only inside the app, and forwards
 * every request to the server URL that is currently reachable (internal or external).
 *
 * Because the page origin never changes, switching between the internal and the external URL does not
 * reload the frontend: plain requests simply go to the new server, and the page's WebSockets are moved
 * by the script from [FrontendUpstreamScript], which reconnects them like after any short network break.
 */
@Singleton
class FrontendUpstream @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClientProvider: SuspendProvider<OkHttpClient>,
) {
    private val bases = ConcurrentHashMap<Int, HttpUrl>()
    /** Pages (main frame and frames) currently open on each virtual origin, to tell them about a switch. */
    private val replyProxies = ConcurrentHashMap<Int, MutableSet<JavaScriptReplyProxy>>()
    private val clientMutex = Mutex()

    @Volatile
    private var client: OkHttpClient? = null

    /** Whether the WebView supports what the virtual origin needs; without it the real URL is loaded. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /**
     * Makes [realUrl]'s origin the current server of [serverId] and returns the same URL on the virtual
     * origin. When the server already had another origin, the open page is told to reconnect its
     * WebSockets to the new one.
     */
    @MainThread
    fun useServerUrl(serverId: Int, realUrl: HttpUrl): HttpUrl {
        val base = realUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val previous = bases.put(serverId, base)
        if (previous != null && previous != base) {
            Timber.i("Server $serverId switched to ${sensitive(base.host)}")
            val message = FrontendUpstreamScript.switchMessage(webSocketBase(base))
            val pages = replyProxies[serverId]?.let { synchronized(it) { it.toList() } }.orEmpty()
            pages.forEach { it.postMessage(message) }
        }
        return realUrl.newBuilder().scheme("http").host(virtualHost(serverId)).port(HttpUrl.defaultPort("http")).build()
    }

    /** The real URL for [url] when it is on a virtual origin, otherwise [url] itself. */
    fun toRealUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val base = serverIdOf(parsed.host)?.let(bases::get) ?: return url
        return parsed.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build().toString()
    }

    /** Whether [host] is one of the virtual hosts served by this class. */
    fun isVirtualHost(host: String?): Boolean = host != null && serverIdOf(host) != null

    /** Pattern of the origins served by this class, for WebView origin rules. */
    val originRule: String = "http://*.$VIRTUAL_DOMAIN"

    @MainThread
    internal fun onPageHello(host: String, replyProxy: JavaScriptReplyProxy) {
        val serverId = serverIdOf(host) ?: return
        replyProxies.getOrPut(serverId) { Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap())) }
            .add(replyProxy)
        bases[serverId]?.let { replyProxy.postMessage(FrontendUpstreamScript.baseMessage(webSocketBase(it))) }
    }

    /**
     * Answers a WebView request for a virtual origin by fetching it from the current server.
     *
     * @return null for requests to other origins, so the WebView loads them itself. A failed main-frame
     * load also returns null: the WebView then fails on the virtual host and reports its usual error.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val serverId = serverIdOf(request.url.host) ?: return null
        val base = bases[serverId] ?: return null
        if (request.method != "GET" && request.method != "HEAD") {
            // The WebView does not expose request bodies; the page script sends these through the bridge.
            return plainResponse(STATUS_METHOD_NOT_ALLOWED, "Method Not Allowed")
        }
        val builder = Request.Builder().url(base.resolveVirtual(request.url)).method(request.method, null)
        request.requestHeaders.forEach { (name, value) ->
            if (name.lowercase() !in DROPPED_REQUEST_HEADERS) builder.header(name, value)
        }
        val virtualUrl = request.url.toString()
        builder.addVirtualCookies(virtualUrl)
        return try {
            httpClient().newCall(builder.build()).execute().also { it.storeCookies(virtualUrl) }.toWebResourceResponse()
        } catch (e: IOException) {
            Timber.w(e, "Upstream request failed")
            if (request.isForMainFrame) null else plainResponse(STATUS_BAD_GATEWAY, "Bad Gateway")
        }
    }

    /** Runs a request that has a body (sent by the page script) against the current server. */
    internal suspend fun execute(host: String, call: FrontendUpstreamScript.BridgeRequest): FrontendUpstreamScript.BridgeResponse {
        val base = serverIdOf(host)?.let(bases::get)
            ?: return FrontendUpstreamScript.BridgeResponse(id = call.id, error = "Unknown server")
        val body = call.body?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val builder = Request.Builder().url(base.resolveVirtual(Uri.parse("http://$host${call.path}")))
        call.headers.forEach { (name, value) ->
            if (name.lowercase() !in DROPPED_REQUEST_HEADERS) builder.header(name, value)
        }
        builder.method(call.method, body?.toRequestBody() ?: if (call.method == "GET") null else ByteArray(0).toRequestBody())
        val virtualUrl = "http://$host${call.path}"
        builder.addVirtualCookies(virtualUrl)
        return try {
            httpClient().newCall(builder.build()).execute().also { it.storeCookies(virtualUrl) }.use { response ->
                FrontendUpstreamScript.BridgeResponse(
                    id = call.id,
                    status = response.code,
                    statusText = response.message,
                    headers = response.headers.filter { it.first.lowercase() !in DROPPED_RESPONSE_HEADERS }.map { listOf(it.first, it.second) },
                    body = android.util.Base64.encodeToString(response.body.bytes(), android.util.Base64.NO_WRAP),
                )
            }
        } catch (e: IOException) {
            Timber.w(e, "Upstream bridged request failed")
            FrontendUpstreamScript.BridgeResponse(id = call.id, error = e.message ?: "Network error")
        }
    }

    private fun virtualHost(serverId: Int) = "$VIRTUAL_HOST_PREFIX$serverId.$VIRTUAL_DOMAIN"

    private fun serverIdOf(host: String?): Int? {
        if (host == null || !host.endsWith(".$VIRTUAL_DOMAIN")) return null
        return host.removeSuffix(".$VIRTUAL_DOMAIN").removePrefix(VIRTUAL_HOST_PREFIX).toIntOrNull()
    }

    private fun webSocketBase(base: HttpUrl): String =
        base.toString().removeSuffix("/").replaceFirst("http", "ws")

    private fun HttpUrl.resolveVirtual(virtual: Uri): HttpUrl = newBuilder()
        .encodedPath(virtual.encodedPath ?: "/")
        .encodedQuery(virtual.encodedQuery)
        .build()

    private fun httpClient(): OkHttpClient = client ?: runBlocking {
        clientMutex.withLock {
            client ?: okHttpClientProvider().newBuilder()
                .apply { interceptors().removeAll { it.javaClass.simpleName == "HttpLoggingInterceptor" } } // debug only, buffers whole streams
                .callTimeout(0, TimeUnit.SECONDS) // camera streams never end
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .cookieJar(CookieJar.NO_COOKIES) // cookies belong to the virtual origin, see addVirtualCookies
                .cache(Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES))
                .build()
                .also { client = it }
        }
    }

    private fun Response.toWebResourceResponse(): WebResourceResponse {
        val type = body.contentType()
        val headers = headers.names()
            .filter { it.lowercase() !in DROPPED_RESPONSE_HEADERS }
            .associateWith { headers(it).joinToString(", ") }
        return WebResourceResponse(
            type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
            type?.charset()?.name(),
            code,
            message.ifEmpty { "OK" },
            headers,
            body.byteStream(),
        )
    }

    /** Sends the cookies the page has for its (virtual) origin, as the WebView would for a real one. */
    private fun Request.Builder.addVirtualCookies(virtualUrl: String) {
        CookieManager.getInstance().getCookie(virtualUrl)?.takeIf { it.isNotEmpty() }?.let { header("Cookie", it) }
    }

    /** Keeps cookies the server sets for the page's (virtual) origin. */
    private fun Response.storeCookies(virtualUrl: String) {
        val cookieManager = CookieManager.getInstance()
        headers("Set-Cookie").forEach { cookieManager.setCookie(virtualUrl, it) }
    }

    private fun plainResponse(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(reason.toByteArray()))
}
