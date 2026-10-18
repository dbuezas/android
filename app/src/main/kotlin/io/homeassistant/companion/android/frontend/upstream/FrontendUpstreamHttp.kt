package io.homeassistant.companion.android.frontend.upstream

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.homeassistant.companion.android.util.hasSameOrigin
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.decodeBase64
import timber.log.Timber

private const val CACHE_DIR = "frontend_upstream"
private const val UPLOAD_DIR = "frontend_upstream_uploads"
private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024
private const val READ_TIMEOUT_SECONDS = 60L
private const val MAX_REDIRECTS = 20
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

/** Cache-busted static files, the only responses kept in the disk cache (as the WebView cache did). */
private val CACHED_PATHS = listOf("/frontend_latest/", "/frontend_es5/", "/static/", "/hacsfiles/", "/local/")

/**
 * Answers the WebView's requests for the virtual origins ([VirtualOrigin]) from the server URL in use
 * ([FrontendUpstream]), with the app's HTTP client (and so its TLS setup: client certificates, user CAs).
 *
 * Cookies stay with the virtual origin in the WebView's [CookieManager], as the page sees them there.
 * Redirects are followed only within the server's own origin; a document redirected to another site is
 * loaded by the WebView itself.
 */
@Singleton
class FrontendUpstreamHttp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClientProvider: SuspendProvider<OkHttpClient>,
    private val upstream: FrontendUpstream,
) {
    private val uploads = ConcurrentHashMap<String, File>()
    private val clientMutex = Mutex()
    private val cache by lazy { Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES) }

    /** Upload files only live as long as the process: leftovers of an earlier one are removed on first use. */
    private val uploadDir by lazy { File(context.cacheDir, UPLOAD_DIR).apply { deleteRecursively() } }

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var serviceWorkerClientSet = false

    /** Builds the HTTP client, before the first request of a page. Reads the disk: call it off the main thread. */
    @WorkerThread
    internal suspend fun prepare() {
        if (client != null) return
        clientMutex.withLock {
            if (client != null) return
            client = okHttpClientProvider().newBuilder()
                // Debug builds log full bodies, which would buffer endless camera streams
                .apply { interceptors().removeAll { it.javaClass.simpleName == "HttpLoggingInterceptor" } }
                .apply { interceptors().add(0, LiveStreamInterceptor) }
                .callTimeout(0, TimeUnit.SECONDS) // camera streams never end
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false) // see fetch and send
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES) // cookies belong to the virtual origin, see addVirtualCookies
                .cache(cache)
                .build()
        }
    }

    /** Removes the frontend's cached responses. */
    @WorkerThread
    fun clearCache() {
        try {
            cache.evictAll()
        } catch (e: IOException) {
            Timber.w(e, "Failed to clear the frontend cache")
        }
    }

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
     * load is answered with a 502 carrying [UPSTREAM_ERROR_HEADER], never left to the WebView: it would
     * look up the virtual host in the network's DNS.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val serverId = VirtualOrigin.serverIdOf(request.url.host) ?: return null
        val base = upstream.baseFor(request.url.host)
        return when {
            base == null -> failedLoad(serverId, request.isForMainFrame, null)
            // The WebView does not expose request bodies; the page script sends these through the channel
            request.method != "GET" && request.method != "HEAD" -> plainResponse(
                STATUS_METHOD_NOT_ALLOWED,
                "Method Not Allowed",
            )
            else -> fetch(serverId, base, request)
        }
    }

    private fun fetch(serverId: Int, base: HttpUrl, request: WebResourceRequest): WebResourceResponse = try {
        val forwarded = forwardedHeaders(request.requestHeaders.map { (name, value) -> listOf(name, value) }, base)
        var target = base.resolveVirtual(request.url)
        var answer: WebResourceResponse? = null
        var hops = 0
        while (answer == null) {
            val virtualUrl = VirtualOrigin.of(target, serverId).toString()
            val call = Request.Builder()
                .url(target)
                .headers(forwarded.withVirtualCookies(virtualUrl))
                .method(request.method, null)
                .cacheControl(cacheControlFor(target.encodedPath, hasRange = forwarded["Range"] != null))
                .build()
            val response = httpClient().newCall(call).execute()
            response.storeCookies(virtualUrl)
            if (request.isForMainFrame && response.needsWebViewAuthentication()) {
                // The app's HTTP auth dialog and stored credentials only work for the WebView's own loads
                response.close()
                upstream.useRealUrl(serverId)
                answer = htmlResponse("")
            } else if (response.code !in REDIRECT_STATUSES) {
                answer = response.toWebResourceResponse()
            } else {
                val location = response.redirectTarget()
                response.close()
                answer = redirectAnswer(serverId, base, location, request, ++hops)
                if (answer == null && location != null) target = location
            }
        }
        answer
    } catch (e: IOException) {
        failedLoad(serverId, request.isForMainFrame, e)
    } catch (e: IllegalArgumentException) {
        // A header value or URL from the page that OkHttp refuses
        failedLoad(serverId, request.isForMainFrame, e)
    }

    /** The answer to a redirect of a WebView request, or null to follow it within the server. */
    private fun redirectAnswer(
        serverId: Int,
        base: HttpUrl,
        location: HttpUrl?,
        request: WebResourceRequest,
        hops: Int,
    ): WebResourceResponse? {
        val sameOrigin = location?.hasSameOrigin(base) == true
        // An add-on page in an iframe is a document too
        val isDocument = request.isForMainFrame ||
            request.requestHeaders.any { (name, value) ->
                name.equals("Accept", ignoreCase = true) &&
                    "text/html" in value
            }
        return when {
            location == null -> badGateway()
            // The WebView follows it on the real URL, with its own cookies for the other site
            request.isForMainFrame && !sameOrigin -> htmlResponse("").also { upstream.useRealUrl(serverId) }
            // Documents render under their own URL, so the WebView must navigate to it
            isDocument -> refreshTo(if (sameOrigin) VirtualOrigin.of(location, serverId) else location)
            !sameOrigin || hops > MAX_REDIRECTS -> badGateway()
            else -> null
        }
    }

    /** Runs a request with a body, sent by the page script, against the server URL in use. */
    internal fun execute(host: String, call: FrontendUpstreamChannel.PageMessage): FrontendUpstreamChannel.AppMessage {
        val upload = call.uploadId?.let(uploads::remove)
        val serverId = VirtualOrigin.serverIdOf(host)
        val base = upstream.baseFor(host)
        return try {
            when {
                serverId == null || base == null -> FrontendUpstreamChannel.AppMessage(
                    id = call.id,
                    error = "Unknown server",
                )
                call.uploadId != null && upload == null -> FrontendUpstreamChannel.AppMessage(
                    id = call.id,
                    error = "Upload failed",
                )
                !call.path.startsWith(
                    "/",
                ) -> FrontendUpstreamChannel.AppMessage(id = call.id, error = "Invalid request")
                else -> send(serverId, base, call, upload)
            }
        } catch (e: IOException) {
            Timber.w("Failed to run a page request for server $serverId: ${e.javaClass.simpleName}")
            FrontendUpstreamChannel.AppMessage(id = call.id, error = "Network error")
        } catch (e: IllegalArgumentException) {
            Timber.w("Refused a page request for server $serverId: ${e.javaClass.simpleName}")
            FrontendUpstreamChannel.AppMessage(id = call.id, error = "Invalid request")
        } finally {
            upload?.delete()
        }
    }

    private fun send(
        serverId: Int,
        base: HttpUrl,
        call: FrontendUpstreamChannel.PageMessage,
        upload: File?,
    ): FrontendUpstreamChannel.AppMessage {
        val forwarded = forwardedHeaders(call.headers, base)
        var target: HttpUrl? = base.resolveVirtual(
            "${VirtualOrigin.SCHEME}://${VirtualOrigin.hostFor(serverId)}${call.path}".toUri(),
        )
        var method = call.method
        var body = requestBody(call, upload, forwarded["Content-Type"])
        var answer: FrontendUpstreamChannel.AppMessage? = null
        var hops = 0
        while (answer == null && target != null) {
            val virtualUrl = VirtualOrigin.of(target, serverId).toString()
            val request = Request.Builder()
                .url(target)
                .headers(forwarded.withVirtualCookies(virtualUrl))
                .method(method, body)
                .cacheControl(CacheControl.Builder().noStore().build())
                .build()
            val response = httpClient().newCall(request).execute()
            response.storeCookies(virtualUrl)
            if (response.code !in REDIRECT_STATUSES) {
                answer = response.use { it.toAppMessage(call.id) }
            } else {
                response.close()
                target = response.redirectTarget()?.takeIf { it.hasSameOrigin(base) && ++hops <= MAX_REDIRECTS }
                if (response.turnsIntoGet(method)) {
                    method = "GET"
                    body = null
                }
            }
        }
        return answer ?: FrontendUpstreamChannel.AppMessage(id = call.id, error = "Redirect to another site")
    }

    /**
     * Appends a base64 chunk of a large request body sent by the page script.
     *
     * @return an error text when the chunk could not be stored; the upload is then dropped
     */
    internal fun appendUpload(uploadId: String, base64: String): String? {
        val bytes = base64.decodeBase64()
        return try {
            if (bytes == null) throw IOException("Invalid chunk")
            val file = uploads.getOrPut(uploadId) {
                uploadDir.mkdirs()
                File.createTempFile("upload", null, uploadDir)
            }
            file.appendBytes(bytes.toByteArray())
            null
        } catch (e: IOException) {
            Timber.w("Failed to store an upload chunk: ${e.javaClass.simpleName}")
            uploads.remove(uploadId)?.delete()
            "Upload failed"
        }
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
        val base = upstream.baseFor(host)
        if (base == null || !path.startsWith("/")) return null
        val virtualUrl = "${VirtualOrigin.SCHEME}://$host$path"
        return try {
            val builder = Request.Builder()
                .url(base.resolveVirtual(virtualUrl.toUri()))
                .headers(Headers.Builder().build().withVirtualCookies(virtualUrl))
                .header("Origin", base.toString().removeSuffix("/"))
            if (protocols.isNotEmpty()) builder.header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
            httpClient().newWebSocket(builder.build(), listener)
        } catch (e: IOException) {
            Timber.w("Failed to open a page WebSocket: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            Timber.w("Refused a page WebSocket: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun httpClient(): OkHttpClient = client ?: throw IOException("Frontend upstream HTTP client not prepared")
}

/**
 * Live streams have no idle timeout: a still MJPEG camera sends nothing while its image is unchanged, and
 * a Supervisor log follow is silent on a quiet system.
 */
private object LiveStreamInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        val live = path.startsWith("/api/camera_proxy_stream/") || path.endsWith("/logs/follow")
        return if (live) {
            chain.withReadTimeout(
                0,
                TimeUnit.SECONDS,
            ).proceed(chain.request())
        } else {
            chain.proceed(chain.request())
        }
    }
}

private fun cacheControlFor(path: String, hasRange: Boolean): CacheControl = CacheControl.Builder()
    .apply { if (CACHED_PATHS.none(path::startsWith)) noStore() }
    .apply { if (hasRange) noCache() } // a cached 200 must not answer a range
    .build()

private fun requestBody(call: FrontendUpstreamChannel.PageMessage, upload: File?, contentType: String?): RequestBody? {
    val mediaType = contentType?.toMediaTypeOrNull()
    return when {
        upload != null -> upload.asRequestBody(mediaType)
        call.body != null -> requireNotNull(call.body.decodeBase64()) { "Invalid body" }.toRequestBody(mediaType)
        call.method == "GET" || call.method == "HEAD" -> null
        else -> ByteArray(0).toRequestBody(mediaType)
    }
}

private fun HttpUrl.resolveVirtual(virtual: Uri): HttpUrl = newBuilder()
    .encodedPath(virtual.encodedPath ?: "/")
    .encodedQuery(virtual.encodedQuery)
    .build()

/** The page's request headers as they go to the server; values keep the bytes the page or WebView wrote. */
private fun forwardedHeaders(pairs: List<List<String>>, base: HttpUrl): Headers = Headers.Builder().apply {
    pairs.forEach { pair ->
        val name = pair.getOrNull(0) ?: return@forEach
        val value = pair.getOrNull(1) ?: return@forEach
        when (name.lowercase()) {
            in DROPPED_REQUEST_HEADERS -> Unit
            // The page origin exists only in the app; Home Assistant builds OAuth redirect URIs from this
            // header when my is not loaded
            "ha-frontend-base" -> add(name, base.toString().removeSuffix("/"))
            else -> addUnsafeNonAscii(name, value)
        }
    }
}.build()

/** Adds the cookies the page has for its (virtual) origin, as the WebView would for a real one. */
private fun Headers.withVirtualCookies(virtualUrl: String): Headers {
    val cookies = CookieManager.getInstance().getCookie(virtualUrl)?.takeIf { it.isNotEmpty() } ?: return this
    // The WebView stores cookies as UTF-8
    return newBuilder().addUnsafeNonAscii("Cookie", cookies).build()
}

/** Keeps cookies the server sets for the page's (virtual) origin. */
private fun Response.storeCookies(virtualUrl: String) {
    val cookieManager = CookieManager.getInstance()
    headers("Set-Cookie").forEach { cookieManager.setCookie(virtualUrl, it) }
}
