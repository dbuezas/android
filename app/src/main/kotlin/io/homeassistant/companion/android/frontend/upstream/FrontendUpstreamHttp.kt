package io.homeassistant.companion.android.frontend.upstream

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
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

private const val CACHE_DIR = "frontend_upstream"
private const val UPLOAD_DIR = "frontend_upstream_uploads"
private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val READ_TIMEOUT_SECONDS = 60L
private const val STATUS_BAD_GATEWAY = 502
private const val STATUS_METHOD_NOT_ALLOWED = 405

/** Request headers the WebView sends that must not reach the server as they are. */
private val DROPPED_REQUEST_HEADERS = setOf(
    "host",
    "accept-encoding",
    "if-none-match",
    "if-modified-since",
    "origin",
    "referer",
    "cookie",
)

/** Response headers that describe the transfer, which OkHttp has already handled. */
private val DROPPED_RESPONSE_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "connection")

/**
 * Answers the WebView's requests for the virtual origins ([VirtualOrigin]) from the server URL in use
 * ([FrontendUpstream]), with the app's HTTP client (and so its TLS setup: client certificates, user CAs).
 *
 * Cookies stay with the virtual origin in the WebView's [CookieManager], as the page sees them there.
 */
@Singleton
class FrontendUpstreamHttp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClientProvider: SuspendProvider<OkHttpClient>,
    private val upstream: FrontendUpstream,
) {
    private val uploads = ConcurrentHashMap<String, File>()
    private val clientMutex = Mutex()

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var serviceWorkerClientSet = false

    /**
     * Makes the WebView's service worker fetches go through [intercept] as well, so the frontend's
     * service worker can cache the app shell of the virtual origin.
     */
    fun installServiceWorkerClient() {
        if (serviceWorkerClientSet ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
        ) {
            return
        }
        serviceWorkerClientSet = true
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    intercept(request)
            },
        )
    }

    /**
     * Answers a WebView request for a virtual origin by fetching it from the server URL in use.
     *
     * @return null for requests to other origins, so the WebView loads them itself. A failed main-frame
     * load also returns null: the WebView then fails on the virtual host and reports its usual error.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val base = upstream.baseFor(request.url.host)
        return when {
            base == null -> null
            // The WebView does not expose request bodies; the page script sends these through the channel
            request.method != "GET" && request.method != "HEAD" -> plainResponse(
                STATUS_METHOD_NOT_ALLOWED,
                "Method Not Allowed",
            )
            else -> fetch(base, request)
        }
    }

    private fun fetch(base: HttpUrl, request: WebResourceRequest): WebResourceResponse? {
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

    /** Runs a request with a body, sent by the page script, against the server URL in use. */
    internal fun execute(host: String, call: FrontendUpstreamChannel.PageMessage): FrontendUpstreamChannel.AppMessage {
        val upload = call.uploadId?.let(uploads::remove)
        val base = upstream.baseFor(host)
        return try {
            if (base == null) {
                FrontendUpstreamChannel.AppMessage(id = call.id, error = "Unknown server")
            } else {
                send(base, host, call, upload)
            }
        } catch (e: IOException) {
            Timber.w(e, "Upstream request from the page failed")
            FrontendUpstreamChannel.AppMessage(id = call.id, error = e.message ?: "Network error")
        } finally {
            upload?.delete()
        }
    }

    private fun send(
        base: HttpUrl,
        host: String,
        call: FrontendUpstreamChannel.PageMessage,
        upload: File?,
    ): FrontendUpstreamChannel.AppMessage {
        val virtualUrl = "${VirtualOrigin.SCHEME}://$host${call.path}"
        val contentType = call.headers.firstOrNull {
            it.first().equals("content-type", ignoreCase = true)
        }?.getOrNull(1)
        val builder = Request.Builder().url(base.resolveVirtual(virtualUrl.toUri()))
        call.headers.forEach { builder.addForwardedHeader(it[0], it[1]) }
        builder.method(call.method, requestBody(call, upload, contentType))
        builder.addVirtualCookies(virtualUrl)
        return httpClient().newCall(builder.build()).execute().also { it.storeCookies(virtualUrl) }.use { response ->
            FrontendUpstreamChannel.AppMessage(
                id = call.id,
                status = response.code,
                statusText = response.message,
                headers = response.headers
                    .filter { it.first.lowercase() !in DROPPED_RESPONSE_HEADERS }
                    .map { listOf(it.first, it.second) },
                body = Base64.encodeToString(response.body.bytes(), Base64.NO_WRAP),
            )
        }
    }

    /** Appends a base64 chunk of a large request body sent by the page script. */
    internal fun appendUpload(uploadId: String, base64: String) {
        val file = uploads.getOrPut(uploadId) {
            File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }.let { File.createTempFile("upload", null, it) }
        }
        file.appendBytes(Base64.decode(base64, Base64.NO_WRAP))
    }

    /**
     * Opens a WebSocket to [path] on the server URL in use for the page on [host], with the server's own
     * origin and the page's cookies, see [FrontendUpstreamSockets].
     */
    internal fun connectSocket(
        host: String,
        path: String,
        protocols: List<String>,
        listener: WebSocketListener,
    ): WebSocket? {
        val base = upstream.baseFor(host) ?: return null
        val virtualUrl = "${VirtualOrigin.SCHEME}://$host$path"
        val builder = Request.Builder()
            .url(base.resolveVirtual(virtualUrl.toUri()))
            .header("Origin", base.toString().removeSuffix("/"))
        if (protocols.isNotEmpty()) builder.header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
        builder.addVirtualCookies(virtualUrl)
        return httpClient().newWebSocket(builder.build(), listener)
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
}

private fun requestBody(call: FrontendUpstreamChannel.PageMessage, upload: File?, contentType: String?): RequestBody? {
    val mediaType = contentType?.toMediaTypeOrNull()
    return when {
        upload != null -> upload.asRequestBody(mediaType)
        call.body != null -> Base64.decode(call.body, Base64.NO_WRAP).toRequestBody(mediaType)
        call.method == "GET" || call.method == "HEAD" -> null
        else -> ByteArray(0).toRequestBody(mediaType)
    }
}

private fun HttpUrl.resolveVirtual(virtual: Uri): HttpUrl = newBuilder()
    .encodedPath(virtual.encodedPath ?: "/")
    .encodedQuery(virtual.encodedQuery)
    .build()

private fun Request.Builder.addForwardedHeader(name: String, value: String) {
    if (name.lowercase() !in DROPPED_REQUEST_HEADERS) header(name, value)
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
