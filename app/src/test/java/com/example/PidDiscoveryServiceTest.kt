package com.example

import com.example.bluetooth.SimulationTransport
import com.example.data.SettingsRepository
import com.example.discovery.PidCapabilityManager
import com.example.discovery.PidDiscoveryService
import com.example.discovery.PidScanStatus
import com.example.model.CapabilityStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PidDiscoveryServiceTest {

    private lateinit var capabilityManager: PidCapabilityManager
    private lateinit var service: PidDiscoveryService
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        capabilityManager = PidCapabilityManager()
        val context = RuntimeEnvironment.getApplication()
        settingsRepository = SettingsRepository(context)
    }

    @Test
    fun testStartScanWithSimulationTransport() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        service = PidDiscoveryService(capabilityManager, testDispatcher)
        val transport = SimulationTransport(testDispatcher)
        val initSuccess = transport.connect()
        assertTrue("SimulationTransport must connect", initSuccess)

        val job = service.startScan(transport, this)
        job?.join()

        assertEquals(PidScanStatus.COMPLETED, service.status.value)
        assertFalse(service.isScanning.value)
        assertEquals(1f, service.progress.value)

        // SimulationTransport supports ranges 0100, 0120, 0140
        val ranges = service.discoveredRanges.value
        assertTrue("Should have discovered at least 3 blocks (0100, 0120, 0140)", ranges.size >= 3)
        assertEquals(0x00, ranges[0].basePid)
        assertEquals(0x20, ranges[1].basePid)
        assertEquals(0x40, ranges[2].basePid)

        // Range 0140 bit 0 is 0 in SimulationTransport, so discovery halted as per SAE J1979
        assertFalse("Block 0140 hasNextRange should be false in simulation", ranges[2].hasNextRange)

        val discovered = service.discoveredPids.value
        assertTrue("Discovered PIDs list should not be empty", discovered.isNotEmpty())

        // Check key standard PIDs
        val rpmPid = discovered.find { it.hexPid == "0C" }
        assertNotNull("Engine RPM (0C) must be in discovered list", rpmPid)
        assertTrue("Engine RPM must be supported", rpmPid!!.supported)

        val speedPid = discovered.find { it.hexPid == "0D" }
        assertNotNull("Vehicle Speed (0D) must be in discovered list", speedPid)
        assertTrue("Vehicle Speed must be supported", speedPid!!.supported)

        // Verify capabilityManager state
        assertEquals(CapabilityStatus.SUPPORTED, capabilityManager.getStatus("010C"))
        assertEquals(CapabilityStatus.SUPPORTED, capabilityManager.getStatus("010D"))

        // Verify raw logs were generated
        val logs = service.rawLogEntries.value
        assertTrue("Raw logs must contain TX and RX entries", logs.any { it.contains("TX: 0100") })
        assertTrue("Raw logs must contain decoded bitmap", logs.any { it.contains("Decoded 0100 Bitmap") })
    }

    @Test
    fun testScanFailsWhenNotConnected() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        service = PidDiscoveryService(capabilityManager, testDispatcher)
        val transport = SimulationTransport(testDispatcher)
        // Do not connect transport
        assertFalse(transport.isConnected)

        service.startScan(transport, this)

        assertEquals(PidScanStatus.ERROR, service.status.value)
        assertNotNull(service.errorMessage.value)
        assertTrue(service.errorMessage.value!!.contains("not connected", ignoreCase = true))
    }

    @Test
    fun testApplySupportedPidsToLiveData() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        service = PidDiscoveryService(capabilityManager, testDispatcher)
        val transport = SimulationTransport(testDispatcher)
        transport.connect()

        val job = service.startScan(transport, this)
        job?.join()

        val appliedCount = service.applySupportedPidsToLiveData(settingsRepository)
        assertTrue("Should have applied supported PIDs", appliedCount > 0)

        val savedPids = settingsRepository.pidDefinitions.value
        val enabledPids = savedPids.filter { it.enabled }
        assertTrue("Saved enabled PIDs should not be empty", enabledPids.isNotEmpty())
        assertTrue("RPM should be enabled", enabledPids.any { it.pid.endsWith("0C") || it.hexPid == "0C" })
    }

    @Test
    fun testStopScanCancelsCleanly() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        service = PidDiscoveryService(capabilityManager, testDispatcher)
        val transport = SimulationTransport(testDispatcher)
        transport.connect()

        service.startScan(transport, this)
        service.stopScan()

        assertEquals(PidScanStatus.CANCELLED, service.status.value)
        assertFalse(service.isScanning.value)
    }

    @Test
    fun testClearResultsResetsState() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        service = PidDiscoveryService(capabilityManager, testDispatcher)
        val transport = SimulationTransport(testDispatcher)
        transport.connect()

        val job = service.startScan(transport, this)
        job?.join()

        service.clearResults()

        assertEquals(PidScanStatus.IDLE, service.status.value)
        assertTrue(service.discoveredPids.value.isEmpty())
        assertTrue(service.discoveredRanges.value.isEmpty())
        assertEquals(0, service.supportedPidsCount.value)
        assertEquals(0f, service.progress.value)
    }
}
