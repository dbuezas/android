package io.homeassistant.companion.android.frontend.upstream

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class FrontendUpstreamHttpTest {

    private val host = "server-1.homeassistant.internal"
    private val requests = mutableListOf<Request>()
    private var answer: (Request) -> Response = { ok(it, "fine") }
    private val upstream: FrontendUpstream = mockk(relaxed = true) {
        every { baseFor(host) } returns "http://192.168.1.2:8123/".toHttpUrl()
    }
    private lateinit var http: FrontendUpstreamHttp

    private class FakeRequest(
        private val url: String,
        private val mainFrame: Boolean = false,
        private val headers: Map<String, String> = emptyMap(),
    ) : WebResourceRequest {
        override fun getUrl(): Uri = url.toUri()
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    @Before
    fun setUp() {
        val canned = Interceptor { chain -> chain.request().also(requests::add).let(answer) }
        http = FrontendUpstreamHttp(
            ApplicationProvider.getApplicationContext(),
            SuspendProvider { OkHttpClient.Builder().addInterceptor(canned).build() },
            upstream,
        )
        runBlocking { http.prepare() }
    }

    private fun ok(request: Request, body: String, code: Int = 200, vararg headers: Pair<String, String>) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .body(body.toResponseBody("text/plain".toMediaType()))
        .build()

    private fun redirect(request: Request, location: String, code: Int = 302, vararg headers: Pair<String, String>) = ok(request, "", code, "Location" to location, *headers)

    @Test
    fun `Given a same-origin redirect of a subresource when fetching then it is followed with the cookie it set`() {
        answer = { request ->
            if (request.url.encodedPath == "/a") {
                redirect(request, "/b", 302, "Set-Cookie" to "hop=1; Path=/")
            } else {
                ok(request, "done")
            }
        }

        val response = http.intercept(FakeRequest("https://$host/a"))

        assertEquals(200, response?.statusCode)
        assertEquals("http://192.168.1.2:8123/b", requests.last().url.toString())
        assertTrue(requests.last().header("Cookie").orEmpty().contains("hop=1"))
    }

    @Test
    fun `Given a subresource redirected to another site when fetching then it is refused and never requested`() {
        answer = { request -> if (request.url.host == "192.168.1.2") redirect(request, "https://evil.example/x") else ok(request, "stolen") }

        val response = http.intercept(FakeRequest("https://$host/api/x"))

        assertEquals(502, response?.statusCode)
        assertTrue(requests.none { it.url.host == "evil.example" })
    }

    @Test
    fun `Given an add-on document redirected on the same server when fetching then the WebView navigates to its virtual URL`() {
        answer = { request -> if (request.url.encodedPath == "/old") redirect(request, "/new?x=1") else ok(request, "page") }

        val response = http.intercept(FakeRequest("https://$host/old", headers = mapOf("Accept" to "text/html")))

        assertEquals(200, response?.statusCode)
        val html = response!!.data.readBytes().decodeToString()
        assertTrue(html, html.contains("url=https://$host/new?x=1"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `Given the main frame redirected to another site when fetching then the server loads its real URL`() {
        answer = { request -> redirect(request, "https://sso.example/login") }

        val response = http.intercept(FakeRequest("https://$host/", mainFrame = true))

        assertEquals(200, response?.statusCode)
        verify { upstream.useRealUrl(1) }
    }

    @Test
    fun `Given a proxy asking for HTTP authentication when the main frame loads then the server loads its real URL`() {
        answer = { request -> ok(request, "", 401, "WWW-Authenticate" to "Basic realm=\"x\"") }

        val response = http.intercept(FakeRequest("https://$host/", mainFrame = true))

        assertEquals(200, response?.statusCode)
        verify { upstream.useRealUrl(1) }
    }

    @Test
    fun `Given a 401 of Home Assistant itself when fetching then it is passed through`() {
        answer = { request -> ok(request, "", 401) }

        val response = http.intercept(FakeRequest("https://$host/api/x"))

        assertEquals(401, response?.statusCode)
        verify(exactly = 0) { upstream.useRealUrl(any()) }
    }

    @Test
    fun `Given a status the WebView refuses when fetching then it does not throw`() {
        answer = { request -> ok(request, "", 999) }

        val response = http.intercept(FakeRequest("https://$host/x"))

        assertEquals(502, response?.statusCode)
    }

    @Test
    fun `Given a timeout of the main frame when fetching then the error code travels in a header`() {
        answer = { throw SocketTimeoutException("slow") }

        val response = http.intercept(FakeRequest("https://$host/", mainFrame = true))

        assertEquals(502, response?.statusCode)
        assertEquals(WebViewClient.ERROR_TIMEOUT.toString(), response?.responseHeaders?.get(UPSTREAM_ERROR_HEADER))
    }

    @Test
    fun `Given a header value with non-ASCII characters when fetching then the request still goes out`() {
        val response = http.intercept(FakeRequest("https://$host/x", headers = mapOf("X-Name" to "Küche")))

        assertEquals(200, response?.statusCode)
        assertEquals("Küche", requests.last().header("X-Name"))
    }

    @Test
    fun `Given the frontend base header when fetching then it carries the server URL`() {
        http.intercept(FakeRequest("https://$host/x", headers = mapOf("HA-Frontend-Base" to "https://$host")))

        assertEquals("http://192.168.1.2:8123", requests.last().header("HA-Frontend-Base"))
    }

    @Test
    fun `Given a camera stream and a static file when fetching then only the static file may be cached`() {
        http.intercept(FakeRequest("https://$host/api/camera_proxy_stream/camera.x"))
        http.intercept(FakeRequest("https://$host/static/icons/x.png"))

        assertTrue(requests[0].cacheControl.noStore)
        assertFalse(requests[1].cacheControl.noStore)
    }

    @Test
    fun `Given a partial response when the WebView applies the range then the stream already starting there is not skipped again`() {
        answer = { request -> ok(request, "x".repeat(100), 206, "Content-Range" to "bytes 100-199/1000") }

        val stream = http.intercept(FakeRequest("https://$host/media/v.mp4", headers = mapOf("Range" to "bytes=100-")))!!.data

        assertEquals(200, stream.available())
        assertEquals(100L, stream.skip(100))
        assertEquals(100, stream.available())
        assertEquals(100, stream.readBytes().size)
        assertTrue(requests.last().cacheControl.noCache)
    }

    @Test
    fun `Given a page request redirected with 303 when sending then it follows as a GET without body`() {
        answer = { request -> if (request.method == "POST") redirect(request, "/done", 303) else ok(request, "done") }

        val reply = http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 4, method = "POST", path = "/api/x", body = "aGk="))

        assertEquals(200, reply.status)
        assertEquals("GET", requests.last().method)
        assertNull(requests.last().body)
    }

    @Test
    fun `Given a page request redirected to another site when sending then it fails`() {
        answer = { request -> redirect(request, "https://evil.example/") }

        val reply = http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 5, method = "POST", path = "/api/x"))

        assertNotNull(reply.error)
        assertTrue(requests.none { it.url.host == "evil.example" })
    }

    @Test
    fun `Given a cookie set by a page request when it is answered then the page never sees it`() {
        answer = { request -> ok(request, "fine", 200, "Set-Cookie" to "secret=1; HttpOnly; Path=/") }

        val reply = http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 6, method = "POST", path = "/api/x"))

        assertTrue(reply.headers.none { it[0].equals("set-cookie", ignoreCase = true) })
        assertTrue(CookieManager.getInstance().getCookie("https://$host/api/x").orEmpty().contains("secret=1"))
    }

    @Test
    fun `Given a path that would change the host when sending then no request is made`() {
        val reply = http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 7, method = "POST", path = "@evil.example/x"))

        assertNotNull(reply.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Given two chunks then a request when uploading then the server gets the joined bytes`() {
        assertNull(http.appendUpload("u1", "aGVs"))
        assertNull(http.appendUpload("u1", "bG8="))
        val sent = okio.Buffer()
        answer = { request -> request.body!!.writeTo(sent).let { ok(request, "stored") } }

        http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 8, method = "POST", path = "/api/backup", uploadId = "u1"))

        assertEquals("hello".encodeUtf8(), sent.readByteString())
    }

    @Test
    fun `Given an invalid chunk when uploading then the request fails instead of sending a truncated body`() {
        assertNull(http.appendUpload("u2", "aGVs"))
        assertNotNull(http.appendUpload("u2", "not base64!"))

        val reply = http.execute(host, FrontendUpstreamChannel.PageMessage(type = "request", id = 9, method = "POST", path = "/api/backup", uploadId = "u2"))

        assertEquals("Upload failed", reply.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Given upload files left by an earlier process when the first chunk arrives then they are removed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val leftover = File(File(context.cacheDir, "frontend_upstream_uploads").apply { mkdirs() }, "old").apply { writeText("x") }
        val fresh = FrontendUpstreamHttp(context, SuspendProvider { OkHttpClient() }, upstream)

        fresh.appendUpload("u3", "aGk=")

        assertFalse(leftover.exists())
    }
}
