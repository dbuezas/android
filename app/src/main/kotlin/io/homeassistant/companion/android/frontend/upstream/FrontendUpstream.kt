package io.homeassistant.companion.android.frontend.upstream

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/** Reserved top-level domain (ICANN, 2024): it never resolves on the internet. */
private const val VIRTUAL_DOMAIN = "homeassistant.internal"
private const val VIRTUAL_HOST_PREFIX = "server-"
private const val VIRTUAL_SCHEME = "https"
private const val CACHE_DIR = "frontend_upstream"
private const val UPLOAD_DIR = "frontend_upstream_uploads"
private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val READ_TIMEOUT_SECONDS = 60L
private const val STATUS_BAD_GATEWAY = 502
private const val STATUS_METHOD_NOT_ALLOWED = 405

/** Request headers the WebView sends that must not reach the server as they are. */
private val DROPPED_REQUEST_HEADERS = setOf("host", "accept-encoding", "if-none-match", "if-modified-since", "origin", "referer", "cookie")

/** Response headers that describe the transfer, which OkHttp has already handled. */
private val DROPPED_RESPONSE_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "connection")

/**
 * Serves the Home Assistant frontend from a fixed origin that exists only inside the app, and forwards
 * every request to the server URL that is currently reachable (internal or external).
 *
 * Because the page origin never changes, switching between the internal and the external URL does not
 * reload the frontend: requests simply go to the new server, and the script from [FrontendUpstreamScript]
 * moves the page's Home Assistant WebSocket to the new server before closing the old one.
 *
 * The origin is `https` so the page is a secure context and the frontend's service worker works; the
 * WebView must allow mixed content for internal `http` URLs (see [FrontendUpstreamScript.attach]).
 */
@Singleton
class FrontendUpstream @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClientProvider: SuspendProvider<OkHttpClient>,
    private val serverManager: ServerManager,
) {
    private val bases = ConcurrentHashMap<Int, HttpUrl>()

    /** Pages (main frame and frames) currently open on each virtual origin, to tell them about a switch. */
    private val replyProxies = ConcurrentHashMap<Int, MutableSet<JavaScriptReplyProxy>>()
    private val uploads = ConcurrentHashMap<String, File>()
    private val connections = ConcurrentHashMap<Int, ServerConnectionInfo>()
    private val clientMutex = Mutex()

    @Volatile
    private var client: OkHttpClient? = null

    private val _moves = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Ids of servers whose open page just moved its WebSocket to a new server URL. */
    val moves: SharedFlow<Int> = _moves.asSharedFlow()

    @Volatile
    private var serviceWorkerClientSet = false

    /** Whether the WebView supports what the virtual origin needs; without it the real URL is loaded. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /** Pattern of the origins served by this class, for WebView origin rules. */
    val originRule: String = "$VIRTUAL_SCHEME://*.$VIRTUAL_DOMAIN"

    /**
     * Makes [realUrl]'s origin the current server of [serverId] and returns the same URL on the virtual
     * origin. An open page keeps using the previous server until [announceSwitch] is called.
     */
    @MainThread
    fun useServerUrl(serverId: Int, realUrl: HttpUrl): HttpUrl {
        val base = realUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        if (bases.put(serverId, base) != base) Timber.i("Server $serverId now uses ${sensitive(base.host)}")
        return realUrl.newBuilder().scheme(VIRTUAL_SCHEME).host(virtualHost(serverId)).port(HttpUrl.defaultPort(VIRTUAL_SCHEME)).build()
    }

    /** Tells the open pages of [serverId] to move their WebSockets to the server set by [useServerUrl]. */
    @MainThread
    fun announceSwitch(serverId: Int) {
        val base = bases[serverId] ?: return
        val message = FrontendUpstreamScript.switchMessage(webSocketBase(base))
        val pages = replyProxies[serverId]?.let { synchronized(it) { it.toList() } }.orEmpty()
        pages.forEach { it.postMessage(message) }
    }

    /** The real URL for [url] when it is on a virtual origin, otherwise [url] itself. */
    fun toRealUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val base = serverIdOf(parsed.host)?.let(bases::get) ?: return url
        return parsed.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build().toString()
    }

    /** Remembers the URLs of [serverId], to recognize links to the server's own addresses. */
    fun rememberConnection(serverId: Int, connection: ServerConnectionInfo) {
        connections[serverId] = connection
    }

    /**
     * The URL on the virtual origin of [serverId] for [url] when [url] points to one of the server's own
     * URLs (internal, external or cloud), otherwise null.
     */
    fun toVirtualUrl(serverId: Int, url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (connections[serverId]?.isKnownUrl(url) != true) return null
        return parsed.newBuilder().scheme(VIRTUAL_SCHEME).host(virtualHost(serverId)).port(HttpUrl.defaultPort(VIRTUAL_SCHEME)).build().toString()
    }

    /** Whether [host] is one of the virtual hosts served by this class. */
    fun isVirtualHost(host: String?): Boolean = serverIdOf(host) != null

    /**
     * Makes the WebView's service worker fetches go through [intercept] as well, so the frontend's
     * service worker can cache the app shell of the virtual origin.
     */
    fun installServiceWorkerClient() {
        if (serviceWorkerClientSet || !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        serviceWorkerClientSet = true
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = intercept(request)
            },
        )
    }

    @MainThread
    internal suspend fun onPageHello(host: String, replyProxy: JavaScriptReplyProxy, binary: Boolean) {
        val serverId = serverIdOf(host) ?: return
        replyProxies.getOrPut(serverId) { Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap())) }
            .add(replyProxy)
        val base = bases[serverId] ?: return
        replyProxy.postMessage(FrontendUpstreamScript.baseMessage(webSocketBase(base), publicOrigin(serverId, base), binary))
    }

    /** Called when a page finished moving its WebSocket after [announceSwitch]. */
    internal fun onPageMoved(host: String) {
        serverIdOf(host)?.let { _moves.tryEmit(it) }
    }

    /** A fresh access token for the page script, to log in its replacement WebSocket. */
    internal suspend fun accessToken(host: String): String? {
        val serverId = serverIdOf(host) ?: return null
        return try {
            serverManager.authenticationRepository(serverId).retrieveAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Unable to get an access token for the page")
            null
        }
    }

    /**
     * Opens a WebSocket to [path] on the current server for the page on [host], with the server's own
     * origin and the page's cookies, see [FrontendUpstreamSockets].
     */
    internal fun connectSocket(host: String, path: String, protocols: List<String>, listener: WebSocketListener): WebSocket? {
        val base = serverIdOf(host)?.let(bases::get) ?: return null
        val virtualUrl = "$VIRTUAL_SCHEME://$host$path"
        val builder = Request.Builder()
            .url(base.resolveVirtual(virtualUrl.toUri()))
            .header("Origin", base.toString().removeSuffix("/"))
        if (protocols.isNotEmpty()) builder.header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
        builder.addVirtualCookies(virtualUrl)
        return httpClient().newWebSocket(builder.build(), listener)
    }

    /** Appends a base64 chunk of a large request body sent by the page script. */
    internal fun appendUpload(uploadId: String, base64: String) {
        val file = uploads.getOrPut(uploadId) {
            File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }.let { File.createTempFile("upload", null, it) }
        }
        file.appendBytes(Base64.decode(base64, Base64.NO_WRAP))
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
        val virtualUrl = request.url.toString()
        val builder = Request.Builder().url(base.resolveVirtual(request.url)).method(request.method, null)
        request.requestHeaders.forEach { (name, value) -> builder.addForwardedHeader(name, value) }
        builder.addVirtualCookies(virtualUrl)
        return try {
            httpClient().newCall(builder.build()).execute().also { it.storeCookies(virtualUrl) }.toWebResourceResponse()
        } catch (e: IOException) {
            Timber.w(e, "Upstream request failed")
            if (request.isForMainFrame) null else plainResponse(STATUS_BAD_GATEWAY, "Bad Gateway")
        }
    }

    /** Runs a request that has a body (sent by the page script) against the current server. */
    internal fun execute(host: String, call: FrontendUpstreamScript.BridgeRequest): FrontendUpstreamScript.BridgeResponse {
        val upload = call.uploadId?.let(uploads::remove)
        try {
            val base = serverIdOf(host)?.let(bases::get)
                ?: return FrontendUpstreamScript.BridgeResponse(id = call.id, error = "Unknown server")
            val virtualUrl = "$VIRTUAL_SCHEME://$host${call.path}"
            val contentType = call.headers.firstOrNull { it.first().equals("content-type", ignoreCase = true) }?.getOrNull(1)
            val builder = Request.Builder().url(base.resolveVirtual(virtualUrl.toUri()))
            call.headers.forEach { builder.addForwardedHeader(it[0], it[1]) }
            builder.method(call.method, requestBody(call, upload, contentType))
            builder.addVirtualCookies(virtualUrl)
            return httpClient().newCall(builder.build()).execute().also { it.storeCookies(virtualUrl) }.use { response ->
                FrontendUpstreamScript.BridgeResponse(
                    id = call.id,
                    status = response.code,
                    statusText = response.message,
                    headers = response.headers
                        .filter { it.first.lowercase() !in DROPPED_RESPONSE_HEADERS }
                        .map { listOf(it.first, it.second) },
                    body = Base64.encodeToString(response.body.bytes(), Base64.NO_WRAP),
                )
            }
        } catch (e: IOException) {
            Timber.w(e, "Upstream bridged request failed")
            return FrontendUpstreamScript.BridgeResponse(id = call.id, error = e.message ?: "Network error")
        } finally {
            upload?.delete()
        }
    }

    private fun requestBody(call: FrontendUpstreamScript.BridgeRequest, upload: File?, contentType: String?): RequestBody? {
        val mediaType = contentType?.toMediaTypeOrNull()
        return when {
            upload != null -> upload.asRequestBody(mediaType)
            call.body != null -> Base64.decode(call.body, Base64.NO_WRAP).toRequestBody(mediaType)
            call.method == "GET" || call.method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(mediaType)
        }
    }

    private suspend fun publicOrigin(serverId: Int, current: HttpUrl): String {
        val connection = serverManager.getServer(serverId)?.connection
        val public = listOfNotNull(connection?.externalUrl, connection?.cloudUrl, current.toString())
            .firstNotNullOfOrNull { it.takeIf(String::isNotBlank)?.toHttpUrlOrNull() } ?: current
        return public.toString().removeSuffix("/")
    }

    private fun virtualHost(serverId: Int) = "$VIRTUAL_HOST_PREFIX$serverId.$VIRTUAL_DOMAIN"

    private fun serverIdOf(host: String?): Int? {
        if (host == null || !host.endsWith(".$VIRTUAL_DOMAIN")) return null
        return host.removeSuffix(".$VIRTUAL_DOMAIN").removePrefix(VIRTUAL_HOST_PREFIX).toIntOrNull()
    }

    private fun webSocketBase(base: HttpUrl): String = base.toString().removeSuffix("/").replaceFirst("http", "ws")

    private fun HttpUrl.resolveVirtual(virtual: Uri): HttpUrl = newBuilder()
        .encodedPath(virtual.encodedPath ?: "/")
        .encodedQuery(virtual.encodedQuery)
        .build()

    private fun Request.Builder.addForwardedHeader(name: String, value: String) {
        if (name.lowercase() !in DROPPED_REQUEST_HEADERS) header(name, value)
    }

    private fun httpClient(): OkHttpClient = client ?: runBlocking {
        clientMutex.withLock {
            client ?: okHttpClientProvider().newBuilder()
                // Debug builds log full bodies, which would buffer endless camera streams
                .apply { interceptors().removeAll { it.javaClass.simpleName == "HttpLoggingInterceptor" } }
                .callTimeout(0, TimeUnit.SECONDS) // camera streams never end
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .cookieJar(CookieJar.NO_COOKIES) // cookies belong to the virtual origin, see addVirtualCookies
                .cache(Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES))
                .build()
                .also { client = it }
        }
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

    private fun Response.toWebResourceResponse(): WebResourceResponse {
        val type = body.contentType()
        val headers = headers.names()
            .filter { it.lowercase() !in DROPPED_RESPONSE_HEADERS && !it.equals("set-cookie", ignoreCase = true) }
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

    private fun plainResponse(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(reason.toByteArray()))
}
