package io.homeassistant.companion.android.frontend.upstream

import androidx.webkit.JavaScriptReplyProxy
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okhttp3.WebSocket
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrontendUpstreamSocketsTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val page: JavaScriptReplyProxy = mockk(relaxed = true)

    private fun sockets(binary: Boolean = true) = FrontendUpstreamSockets(scope, dispatcher, binary)

    @Test
    fun `Given two open sockets when all are closed then both close as going away`() {
        val first: WebSocket = mockk(relaxed = true)
        val second: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 1) { first }
        sockets.open(page, 2) { second }

        sockets.closeAll()

        verify { first.close(1001, null) }
        verify { second.close(1001, null) }
    }

    @Test
    fun `Given an open socket when the scope ends then it is closed`() {
        val socket: WebSocket = mockk(relaxed = true)
        val pageScope = CoroutineScope(Job() + dispatcher)
        FrontendUpstreamSockets(pageScope, dispatcher, binary = true).open(page, 1) { socket }

        pageScope.cancel()

        verify { socket.close(1001, null) }
    }

    @Test
    fun `Given close codes from the page when closing then only codes a client may send are used`() {
        val reported: WebSocket = mockk(relaxed = true)
        val application: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 1) { reported }
        sockets.open(page, 2) { application }

        sockets.close(page, sid = 1, code = 1005, reason = "")
        sockets.close(page, sid = 2, code = 4000, reason = "bye")

        verify { reported.close(1000, "") }
        verify { application.close(4000, "bye") }
    }

    @Test
    fun `Given a close reason above 123 bytes when closing then no reason is sent`() {
        val socket: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 1) { socket }

        sockets.close(page, sid = 1, code = 1000, reason = "x".repeat(200))

        verify { socket.close(1000, null) }
    }

    @Test
    fun `Given two pages with the same socket id when sending then each frame reaches its own socket`() {
        val otherPage: JavaScriptReplyProxy = mockk(relaxed = true)
        val mine: WebSocket = mockk(relaxed = true)
        val theirs: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 1) { mine }
        sockets.open(otherPage, 1) { theirs }

        sockets.send(page, sid = 1, text = "hello", base64 = null)
        sockets.send(otherPage, sid = 1, text = null, base64 = "aGk=")

        verify { mine.send("hello") }
        verify { theirs.send("hi".encodeUtf8()) }
        verify(exactly = 0) { mine.send(any<okio.ByteString>()) }
    }

    @Test
    fun `Given invalid base64 from the page when sending then nothing is sent`() {
        val socket: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 1) { socket }

        sockets.send(page, sid = 1, text = null, base64 = "not base64!")

        verify(exactly = 0) { socket.send(any<okio.ByteString>()) }
    }

    @Test
    fun `Given a binary frame from the page when sending then the payload after the socket id goes out`() {
        val socket: WebSocket = mockk(relaxed = true)
        val sockets = sockets()
        sockets.open(page, 7) { socket }

        sockets.sendBinary(page, byteArrayOf(0, 0, 0, 7) + "hi".encodeToByteArray())

        verify { socket.send("hi".encodeUtf8()) }
    }

    @Test
    fun `Given no connection when opening then the page is told the socket closed`() {
        sockets().open(page, 3) { null }
        scope.advanceUntilIdle()

        verify { page.postMessage(match<String> { it.contains("\"wsClosed\"") && it.contains("\"sid\":3") && it.contains("1006") }) }
    }
}
