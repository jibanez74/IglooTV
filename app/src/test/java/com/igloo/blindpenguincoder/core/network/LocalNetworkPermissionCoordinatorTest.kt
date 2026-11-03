package com.igloo.blindpenguincoder.core.network

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkPermissionCoordinatorTest {
    @Test
    fun `request delivery survives UI recreation and a fresh host can retry a lost result`() {
        var requested = false
        var requests = 0
        val coordinator = LocalNetworkPermissionCoordinator(
            { false }, { requested }, { requested = true; requests++ }, { true },
        )
        assertThrows(LocalNetworkPermissionException::class.java) { coordinator.checkAccess("http://igloo.local") }
        val request = coordinator.state.value.requestId
        assertTrue(coordinator.claimPermissionRequest(request))
        assertFalse(coordinator.claimPermissionRequest(request))
        coordinator.onNewHost()
        assertFalse(coordinator.state.value.requestPending)
        assertEquals("igloo.local", coordinator.state.value.blockedHost)
        assertEquals(1, requests)
        coordinator.request()
        assertTrue(coordinator.claimPermissionRequest(coordinator.state.value.requestId))
        assertEquals(2, requests)
    }

    @Test
    fun `denial waits without repeating prompt then settings recovery resumes each operation once`() = runTest {
        var granted = false
        var requested = false
        var requests = 0
        var completed = 0
        val coordinator = LocalNetworkPermissionCoordinator(
            permissionGranted = { granted },
            wasRequested = { requested },
            markRequested = { requested = true; requests++ },
            requiresLocalAccess = { true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val first = async { coordinator.awaitAccess("http://igloo.local"); completed++ }
        val second = async { coordinator.awaitAccess("https://192.168.1.5/art"); completed++ }
        runCurrent()
        assertEquals(1, requests)
        assertTrue(coordinator.state.value.requestPending)
        coordinator.onPermissionResult()
        coordinator.refresh()
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, requests)
        // Even permanent denial leaves explicit Retry and Settings recovery available.
        coordinator.request()
        assertEquals(2, requests)
        coordinator.onPermissionResult()
        granted = true
        coordinator.refresh()
        runCurrent()
        first.await(); second.await()
        assertEquals(2, completed)
        assertNull(coordinator.state.value.blockedHost)
        assertEquals(1, coordinator.state.value.recovery)
        coordinator.refresh()
        assertEquals(1, coordinator.state.value.recovery)
    }

    @Test
    fun `public requests work while LAN permission remains denied`() = runTest {
        val coordinator = LocalNetworkPermissionCoordinator(
            permissionGranted = { false }, wasRequested = { true }, markRequested = { fail() },
            requiresLocalAccess = { false }, dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        coordinator.awaitAccess("https://public.example/api")
        coordinator.checkAccess("http://public.example/media")
        assertNull(coordinator.state.value.blockedHost)
    }

    @Test
    fun `old Android and pregranted permissions bypass classification and prompt`() = runTest {
        val coordinator = LocalNetworkPermissionCoordinator(
            permissionGranted = { true }, wasRequested = { false }, markRequested = { fail() },
            requiresLocalAccess = { error("DNS must not be needed") },
        )
        coordinator.awaitAccess("http://igloo.local")
        coordinator.checkAccess("https://192.168.1.5")
    }

    @Test
    fun `loader checks fail immediately and revocation reuses explicit recovery`() = runTest {
        var granted = true
        var requests = 0
        val coordinator = LocalNetworkPermissionCoordinator(
            permissionGranted = { granted }, wasRequested = { true }, markRequested = { requests++ },
            requiresLocalAccess = { true },
        )
        coordinator.checkAccess("http://igloo.local/stream.m3u8")
        granted = false
        coordinator.refresh()
        repeat(3) {
            assertThrows(LocalNetworkPermissionException::class.java) {
                coordinator.checkAccess("http://igloo.local/segment.ts")
            }
        }
        assertEquals(0, requests)
        assertEquals("igloo.local", coordinator.state.value.blockedHost)
        granted = true
        coordinator.refresh()
        coordinator.checkAccess("http://igloo.local/music.mp3")
        assertEquals(1, coordinator.state.value.recovery)
    }

    @Test
    fun `changing server cancels suspended restoration and clears explanation`() = runTest {
        val coordinator = LocalNetworkPermissionCoordinator(
            permissionGranted = { false }, wasRequested = { true }, markRequested = { fail() },
            requiresLocalAccess = { true }, dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val pending = async { coordinator.awaitAccess("http://igloo.local/api") }
        runCurrent()
        coordinator.cancelPending()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertNull(coordinator.state.value.blockedHost)
    }

    @Test
    fun `LAN literals hostnames IPv6 and both schemes are classified`() {
        for (ip in listOf("10.0.2.2", "172.16.0.1", "192.168.1.5", "169.254.1.1", "127.0.0.1", "fc00::1", "fd12::4", "fe80::1", "::1")) {
            val address = InetAddress.getByName(ip)
            assertTrue(ip, isLocalAddress(address))
            for (scheme in listOf("http", "https")) {
                val host = if (':' in ip) "[$ip]" else ip
                assertTrue(ip, isLocalNetworkUrl("$scheme://$host:8080/api") { listOf(address) })
            }
        }
        for (host in listOf("igloo.local", "IGLOO.LOCAL.", "igloo")) {
            assertTrue(isLocalNetworkUrl("http://$host") { error("mDNS must be gated before resolution") })
        }
        assertTrue(isLocalNetworkUrl("https://media.example.com") { listOf(InetAddress.getByName("10.1.2.3")) })
        assertTrue(isLocalNetworkUrl("https://mixed.example.com") {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.5"))
        })
    }

    @Test
    fun `public and failed DNS do not require LAN access`() {
        for (ip in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "2001:4860:4860::8888")) {
            assertFalse(ip, isLocalAddress(InetAddress.getByName(ip)))
        }
        assertFalse(isLocalNetworkUrl("https://public.example") { listOf(InetAddress.getByName("8.8.8.8")) })
        assertFalse(isLocalNetworkUrl("https://missing.example") { throw UnknownHostException() })
        assertFalse(isLocalNetworkUrl("file:///poster.jpg") { error("not network") })
    }
}
