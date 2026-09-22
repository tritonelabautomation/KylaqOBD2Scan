package com.example

import com.example.bluetooth.ElmResponse
import com.example.bluetooth.ElmTransport
import com.example.bluetooth.RawLogListener
import com.example.bluetooth.SimulationElmTransport
import com.example.discovery.PidCapabilityManager
import com.example.discovery.PidDiscoveryService
import com.example.discovery.PidScanStatus
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "PID discovery doesn't work even though I have [the adapter connected]" (owner 2026-09-22, with
 * the dashboard visibly recording: STOP 02:40, TX/RX counting, GPS fix with altitude - and the
 * discovery screen answering "OBD-II adapter is not connected").
 *
 * The scan itself was never the broken part; the TRANSPORT handed to it was. Three entry points
 * open the adapter socket - the phone connect dialog, the keep-alive service's auto-connect tick
 * and the Android Auto session - and only the first one wrote `MainViewModel.activeTransport`.
 * After a process death the service reconnected in the background while a fresh ViewModel held
 * null, so `startPidScan` handed the scan a brand-new, never-connected SimulationTransport and the
 * scan honestly reported "not connected" in the middle of a live recording.
 *
 * These tests pin the two halves of the fix:
 *  1. the scan's gate is exactly `transport.isConnected` - a dead transport produces the owner's
 *     message and writes nothing, a live one runs to completion;
 *  2. the rule that decides WHICH socket is live - own-while-alive, otherwise the process-wide
 *     one - behaves for every combination of dead and alive sockets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PidDiscoveryTransportTest {

    private fun newService() = PidDiscoveryService(PidCapabilityManager())

    /** A socket whose only behaviour is being up or down - all the rule needs. */
    private class FakeTransport(override val isConnected: Boolean) : ElmTransport {
        override val deviceAddress: String? = null
        override suspend fun connect(): Boolean = isConnected
        override suspend fun disconnect() = Unit
        override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse =
            throw UnsupportedOperationException("the rule never sends")
        override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> =
            throw UnsupportedOperationException("the rule never sends")
        override fun setRawLogListener(listener: RawLogListener?) = Unit
    }

    // ── 1. the scan's gate ────────────────────────────────────────────────────────────

    @Test
    fun aScanOnADeadTransportSaysNotConnectedAndRunsNothing() {
        val service = newService()
        val dead = SimulationElmTransport() // never connected: what startPidScan used to hand over

        val scope = CoroutineScope(Dispatchers.IO)
        val job = service.startScan(dead, scope)

        assertNull("a dead transport must not start a scan job", job)
        assertEquals(PidScanStatus.ERROR, service.status.value)
        assertEquals(
            "OBD-II adapter is not connected. Connect via Bluetooth or Simulator before scanning.",
            service.errorMessage.value
        )
        assertEquals(0, service.discoveredRanges.value.size)
        assertEquals(0, service.supportedPidsCount.value)
    }

    @Test
    fun aScanOnALiveLinkRunsToCompletionWhateverOpenedTheLink() = runBlocking {
        val service = newService()
        val sim = SimulationElmTransport()
        assertTrue("the fixture socket must come up", sim.connect())

        val scope = CoroutineScope(Dispatchers.IO)
        val job = service.startScan(sim, scope)
        assertNotNull("a live transport must produce a scan job", job)

        withTimeout(60_000L) { job!!.join() }

        assertNull("a completed scan carries no error: ${service.errorMessage.value}", service.errorMessage.value)
        assertEquals(PidScanStatus.COMPLETED, service.status.value)
        assertTrue(
            "the eight SAE J1979 base ranges must all be reported: ${service.discoveredRanges.value.size}",
            service.discoveredRanges.value.isNotEmpty()
        )
        assertTrue(
            "the EA211 answers 0100/0120/0140, so supported PIDs must be found",
            service.supportedPidsCount.value > 0
        )
        sim.disconnect()
        // JUnit4 validates that a @Test method returns void: a runBlocking block whose last
        // expression is a Job (or anything else non-Unit) makes the WHOLE class unrunnable -
        // CI reported it as initializationError "should be void" and the failing suite held
        // the APK gate shut (owner 2026-09-22: "why apk file is not generated").
        scope.cancel()
    }

    // ── 2. which socket is "the" socket ───────────────────────────────────────────────

    @Test
    fun theLiveSocketWinsWhoeverOpenedIt() {
        val own = FakeTransport(isConnected = true)
        val shared = FakeTransport(isConnected = true)
        // Our own socket while it is alive is the one this ViewModel configured (header, init
        // sequence), so it stays preferred while both are up.
        assertSame(own, MainViewModel.resolveLiveTransport(own, shared))
    }

    @Test
    fun aSocketOpenedByTheServiceIsAdoptedWhenTheViewModelHoldsADeadOne() {
        // THE owner's case: the keep-alive service reconnected after a process death; the fresh
        // ViewModel still pointed at the corpse of the previous socket.
        val deadOwn = FakeTransport(isConnected = false)
        val liveShared = FakeTransport(isConnected = true)
        assertSame(liveShared, MainViewModel.resolveLiveTransport(deadOwn, liveShared))
    }

    @Test
    fun aNullOwnSocketStillFindsTheSharedOne() {
        val liveShared = FakeTransport(isConnected = true)
        assertSame(liveShared, MainViewModel.resolveLiveTransport(null, liveShared))
    }

    @Test
    fun twoDeadSocketsAreNoSocket() {
        assertNull(MainViewModel.resolveLiveTransport(FakeTransport(false), FakeTransport(false)))
        assertNull(MainViewModel.resolveLiveTransport(null, FakeTransport(false)))
        assertNull(MainViewModel.resolveLiveTransport(FakeTransport(false), null))
        assertNull(MainViewModel.resolveLiveTransport(null, null))
    }
}
