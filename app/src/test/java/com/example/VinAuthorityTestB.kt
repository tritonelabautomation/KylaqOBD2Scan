package com.example

import com.example.protocol.IsoTpMessage
import com.example.protocol.VinAuthority
import org.junit.Assert.*
import org.junit.Test

/** VIN ECU Authority tests - Part B (payload validation). */
class VinAuthorityTestB {

    private fun v(canId: String?, vin: String) = IsoTpMessage(canId, emptyList(), "",
        listOf(0x49, 0x02, 0x01) + vin.map { it.code }, true, 20, false)

    private fun mk(canId: String?, bytes: List<Int>) = IsoTpMessage(canId, emptyList(), "", bytes, true, bytes.size, false)

    // Wrong size tests
    @Test fun wrongSize19_rejected() {
        val m = mk("7E8", listOf(0x49,0x02,0x01)+"1234567890123456".map{it.code})
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun wrongSize21_rejected() {
        val m = mk("7E8", listOf(0x49,0x02,0x01)+"123456789012345678".map{it.code})
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    // Wrong header tests
    @Test fun wrongService_rejected() {
        val m = mk("7E8", listOf(0x41,0x02,0x01)+"12345678901234567".map{it.code})
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun wrongPid_rejected() {
        val m = mk("7E8", listOf(0x49,0x04,0x01)+"12345678901234567".map{it.code})
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun wrongRecord_rejected() {
        val m = mk("7E8", listOf(0x49,0x02,0x02)+"12345678901234567".map{it.code})
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    // Invalid VIN character tests
    @Test fun vinWithI_rejected() {
        val m = v("7E8", "12345678901I56789")
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun vinWithO_rejected() {
        val m = v("7E8", "12345678901O56789")
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun vinWithQ_rejected() {
        val m = v("7E8", "12345678901Q56789")
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun lowercase_rejected() {
        val m = v("7E8", "mexkpepc2tg028855")
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun space_rejected() {
        val m = v("7E8", "1234567890 56789")
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    @Test fun controlChar_rejected() {
        val bytes = listOf(0x49,0x02,0x01)+"1234567890\x00156789".map{it.code}
        val m = mk("7E8", bytes)
        assertTrue(VinAuthority.collectVinCandidates(listOf(m)).isEmpty())
    }

    // Valid VIN tests
    @Test fun validVin_accepted() {
        val m = v("7E8", "MEXKPEPC2TG028855")
        val cands = VinAuthority.collectVinCandidates(listOf(m))
        assertEquals(1, cands.size)
        assertEquals("MEXKPEPC2TG028855", cands[0].vin)
        assertTrue(cands[0].isAuthoritative)
    }

    @Test fun allValidChars_accepted() {
        val m = v("7E8", "ABCDEFGHJKLMNPRSTUVWXYZ0123456789")
        val cands = VinAuthority.collectVinCandidates(listOf(m))
        assertEquals(1, cands.size)
    }

    // Mixed CAN ID tests
    @Test fun mixedCanIds_separated() {
        val msgs = listOf(v("7E8", "VIN7E8ABCDEFGHIJ"), v("7E9", "VIN7E9KLMNOPQR"))
        val cands = VinAuthority.collectVinCandidates(msgs)
        assertEquals(2, cands.size)
        assertEquals(1, cands.count { it.canId == "7E8" })
        assertEquals(1, cands.count { it.canId == "7E9" })
    }

    @Test fun canIdCaseInsensitive() {
        assertTrue(VinAuthority.isAuthoritativeVinEcu("7e8"))
        assertTrue(VinAuthority.isAuthoritativeVinEcu("7E8"))
        assertTrue(VinAuthority.isAuthoritativeVinEcu("7E8"))
    }

    // Unauthenticated ECU tests
    @Test fun unauthorized7E9_rejected() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(v("7E9", "VIN123456789012"))))
        assertTrue(r is com.example.protocol.VinSelectionResult.Unavailable)
    }

    @Test fun unauthorized7EA_rejected() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(v("7EA", "VIN123456789012"))))
        assertTrue(r is com.example.protocol.VinSelectionResult.Unavailable)
    }

    @Test fun authorized7E1_accepted() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(v("7E1", "VIN123456789012"))))
        assertTrue(r is com.example.protocol.VinSelectionResult.Success)
    }
}