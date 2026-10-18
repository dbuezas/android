package io.homeassistant.companion.android.frontend.upstream

import androidx.webkit.JavaScriptReplyProxy
import app.cash.turbine.test
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrontendUpstreamTest {

    private val serverManager: ServerManager = mockk()
    private val upstream = FrontendUpstream(mockk(relaxed = true), serverManager)

    @Test
    fun `Given a server URL when using it then the same URL on the virtual origin is returned`() {
        val virtual = upstream.useServerUrl(1, "http://192.168.1.2:8123/lovelace/home?external_auth=1".toHttpUrl())

        assertEquals("https://server-1.homeassistant.internal/lovelace/home?external_auth=1", virtual.toString())
    }

    @Test
    fun `Given a server URL in use when mapping a virtual URL back then the real URL keeps path and query`() {
        upstream.useServerUrl(1, "https://example.ui.nabu.casa/".toHttpUrl())

        assertEquals(
            "https://example.ui.nabu.casa/api/camera_proxy/camera.garden?token=abc",
            upstream.toRealUrl("https://server-1.homeassistant.internal/api/camera_proxy/camera.garden?token=abc"),
        )
    }

    @Test
    fun `Given a URL on another origin when mapping it back then it is unchanged`() {
        upstream.useServerUrl(1, "http://192.168.1.2:8123/".toHttpUrl())

        assertEquals("https://www.home-assistant.io/docs", upstream.toRealUrl("https://www.home-assistant.io/docs"))
        assertEquals("not a url", upstream.toRealUrl("not a url"))
    }

    @Test
    fun `Given a switch to the external URL when mapping back then the new server URL is used`() {
        upstream.useServerUrl(1, "http://192.168.1.2:8123/".toHttpUrl())
        upstream.useServerUrl(1, "https://example.ui.nabu.casa/".toHttpUrl())

        assertEquals("https://example.ui.nabu.casa/history", upstream.toRealUrl("https://server-1.homeassistant.internal/history"))
    }

    @Test
    fun `Given a link to one of the server's own URLs when mapping it then it moves to the virtual origin`() {
        val connection: ServerConnectionInfo = mockk {
            every { isKnownUrl("http://192.168.1.2:8123/lovelace/lights#top") } returns true
            every { isKnownUrl("https://example.com/") } returns false
        }
        upstream.rememberConnection(1, connection)

        assertEquals(
            "https://server-1.homeassistant.internal/lovelace/lights#top",
            upstream.toVirtualUrl(1, "http://192.168.1.2:8123/lovelace/lights#top"),
        )
        assertNull(upstream.toVirtualUrl(1, "https://example.com/"))
    }

    @Test
    fun `Given an open page when the server URL switches then the page is told the new WebSocket origin`() = runTest {
        val page: JavaScriptReplyProxy = mockk(relaxed = true)
        coEvery { serverManager.getServer(1) } returns null
        upstream.useServerUrl(1, "http://192.168.1.2:8123/".toHttpUrl())
        upstream.onPageHello("server-1.homeassistant.internal", page, binary = true)

        upstream.useServerUrl(1, "https://example.ui.nabu.casa/".toHttpUrl())
        upstream.announceSwitch(1)

        verify { page.postMessage(FrontendUpstreamChannel.switchMessage("wss://example.ui.nabu.casa")) }
    }

    @Test
    fun `Given a page that moved when it reports it then the server id is emitted`() = runTest {
        upstream.moves.test {
            upstream.onPageMoved("server-3.homeassistant.internal")

            assertEquals(3, awaitItem())
        }
    }

    @Test
    fun `Given a cloud connection when a page says hello then links use the cloud origin`() = runTest {
        val page: JavaScriptReplyProxy = mockk(relaxed = true)
        coEvery { serverManager.getServer(1) } returns mockk {
            every { connection } returns ServerConnectionInfo(
                externalUrl = "http://192.168.1.2:8123",
                cloudUrl = "https://example.ui.nabu.casa",
                useCloud = true,
            )
        }
        upstream.useServerUrl(1, "http://192.168.1.2:8123/".toHttpUrl())

        upstream.onPageHello("server-1.homeassistant.internal", page, binary = false)

        verify {
            page.postMessage(
                FrontendUpstreamChannel.baseMessage("ws://192.168.1.2:8123", "https://example.ui.nabu.casa", false),
            )
        }
    }

    @Test
    fun `Given a hello from another host when the server URL switches then nothing is posted`() = runTest {
        val page: JavaScriptReplyProxy = mockk(relaxed = true)
        upstream.useServerUrl(1, "http://192.168.1.2:8123/".toHttpUrl())

        upstream.onPageHello("homeassistant.local", page, binary = false)
        upstream.announceSwitch(1)

        verify(exactly = 0) { page.postMessage(any<String>()) }
    }

    @Test
    fun `Given hosts when reading the server id then only virtual hosts have one`() {
        assertEquals(12, VirtualOrigin.serverIdOf("server-12.homeassistant.internal"))
        assertNull(VirtualOrigin.serverIdOf("homeassistant.local"))
        assertNull(VirtualOrigin.serverIdOf("server-x.homeassistant.internal"))
        assertNull(VirtualOrigin.serverIdOf(null))
    }
}
