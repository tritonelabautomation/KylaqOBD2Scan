package com.example

import com.example.protocol.IsoTpMessage
import com.example.protocol.VinAuthority
import com.example.protocol.VinCandidate
import com.example.protocol.VinSelectionResult
import org.junit.Assert.*
import org.junit.Test

/** VIN ECU Authority tests - Part A. */
class VinAuthorityTestA {

    private fun v(canId: String?, vin: String) = IsoTpMessage(canId, emptyList(), "",
        listOf(0x49, 0x02, 0x01) + vin.map { it.code }, true, 20, false)

    private fun mal(canId: String?) = IsoTpMessage(canId, emptyList(), "",
        listOf(0x49, 0x02, 0x01), false, 20, true)

    // Authority checks
    @Test fun auth_7E8() = assertTrue(VinAuthority.isAuthoritativeVinEcu("7E8"))
    @Test fun auth_7E1() = assertTrue(VinAuthority.isAuthoritativeVinEcu("7E1"))
    @Test fun notAuth_7E9() = assertFalse(VinAuthority.isAuthoritativeVinEcu("7E9"))
    @Test fun notAuth_null() = assertFalse(VinAuthority.isAuthoritativeVinEcu(null))
    @Test fun pri_7E8() = assertEquals(0, VinAuthority.getAuthorityPriority("7E8"))
    @Test fun pri_7E1() = assertEquals(1, VinAuthority.getAuthorityPriority("7E1"))
    @Test fun pri_7E9() = assertNull(VinAuthority.getAuthorityPriority("7E9"))

    @Test fun t1_authorized_accepted() {
        val r = VinAuthority.selectVinByAuthority(listOf(VinCandidate("7E8", "VIN123456789", true)))
        assertTrue(r is VinSelectionResult.Success)
    }

    @Test fun t2_unauthorized_rejected() {
        val r = VinAuthority.selectVinByAuthority(listOf(VinCandidate("7E9", "VIN123456789", false)))
        assertTrue(r is VinSelectionResult.Unavailable)
    }

    // Policy (see VinAuthority KDoc): ANY conflicting VIN, authoritative or not, fails closed.
    @Test fun t3_unauthFirst_conflicting_failsClosed() {
        val c = listOf(VinCandidate("7E9", "UNAUTH", false), VinCandidate("7E8", "AUTH", true))
        assertTrue(VinAuthority.selectVinByAuthority(c) is VinSelectionResult.Ambiguous)
    }

    @Test fun t4_authFirst_conflicting_failsClosed() {
        val c = listOf(VinCandidate("7E8", "AUTH", true), VinCandidate("7E9", "UNAUTH", false))
        assertTrue(VinAuthority.selectVinByAuthority(c) is VinSelectionResult.Ambiguous)
    }

    @Test fun t4b_unauthAgrees_authSucceeds() {
        val c = listOf(VinCandidate("7E8", "SAMEVIN", true), VinCandidate("7E9", "SAMEVIN", false))
        val r = VinAuthority.selectVinByAuthority(c)
        assertTrue(r is VinSelectionResult.Success)
        assertEquals("SAMEVIN", (r as VinSelectionResult.Success).vin)
    }

    @Test fun t5_sameVin_accepted() {
        val c = listOf(VinCandidate("7E1", "SAME", true), VinCandidate("7E8", "SAME", true))
        assertTrue(VinAuthority.selectVinByAuthority(c) is VinSelectionResult.Success)
    }

    @Test fun t6_twoUnauthorized_rejected() {
        val c = listOf(VinCandidate("7E9", "A", false), VinCandidate("7EA", "B", false))
        assertTrue(VinAuthority.selectVinByAuthority(c) is VinSelectionResult.Unavailable)
    }

    @Test fun t7_differentVins_ambiguous() {
        val c = listOf(VinCandidate("7E8", "VINA", true), VinCandidate("7E1", "VINB", true))
        assertTrue(VinAuthority.selectVinByAuthority(c) is VinSelectionResult.Ambiguous)
    }

    @Test fun t8_validAuth_malformedUnauth() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(mal("7E9"), v("7E8", "VALD1234567890ABC"))))
        assertTrue(r is VinSelectionResult.Success)
    }

    @Test fun t9_validUnauth_malformedAuth() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(v("7E9", "UNAUTH1234567890A"), mal("7E8"))))
        assertTrue(r is VinSelectionResult.Unavailable)
    }

    @Test fun t10_malformed_noCandidate() = assertTrue(VinAuthority.collectVinCandidates(listOf(mal("7E8"))).isEmpty())

    @Test fun kylaqRealTrace() {
        val lines = listOf("7E8 10 14 49 02 01 4D 45 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msgs = com.example.protocol.IsoTpParser.reassembleLines(lines)
        assertEquals(1, msgs.size)
        assertEquals("7E8", msgs[0].canId)
        assertTrue(msgs[0].isComplete)
        assertFalse(msgs[0].isMalformed)
        assertEquals(20, msgs[0].reconstructedBytes.size)
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(msgs))
        assertTrue(r is VinSelectionResult.Success)
        assertEquals("MEXKPEPC2TG028855", (r as VinSelectionResult.Success).vin)
    }

    @Test fun nullCanId_notAuthoritative() {
        val r = VinAuthority.selectVinByAuthority(VinAuthority.collectVinCandidates(listOf(v(null, "VIN123456789012"))))
        assertTrue(r is VinSelectionResult.Unavailable)
    }
}