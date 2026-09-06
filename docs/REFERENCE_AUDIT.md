# Kylaq Reference Implementation Comparative Audit

**Date:** 2026-09-06  
**References:** OBDForge, ELMterm, openRS_, python-OBD, ELM327-emulator

---

## Summary

| Component | Kylaq | Best Reference | Action |
|-----------|-------|----------------|--------|
| VIN ECU Authority | ✅ Good | OBDForge | No changes needed |
| ISO-TP Parser | 🟡 Partial | ELMterm | Add FC generation |
| Multi-ECU Routing | 🔴 Missing | openRS_ | Add ECU mapping |
| PID Catalog | 🟡 150+ PIDs | python-OBD | Add missing formulas |
| DTC Decoder | 🟡 Basic | ELMterm | Add UDS support |
| Tests | 🟡 50 unit | ELM327-emulator | Add integration tests |

---

## 1. VIN ECU Authority ✅

Kylaq matches OBDForge's architecture:
- Explicit authority model (7E8 primary, 7E1 secondary)
- Fail-closed on conflicting VINs
- 45 unit tests covering edge cases

**Verdict:** Production-grade. No changes needed.

---

## 2. ISO-TP Parser 🟡

**Current Capabilities:**
- ✅ Single Frame, First Frame, Consecutive Frame
- ✅ CAN ID grouping, padding stripping
- ✅ Malformed message reporting
- ❌ Flow Control generation
- ❌ Extended addressing

**ELMterm Reference:**
```swift
// OBD2Analyzer.swift - Explicit sequence tracking
struct ISOTPReassembly {
    var totalLength: Int
    var buffer: [UInt8]
    var nextSequence: UInt8
}
```

**Recommended:**
```kotlin
enum class FlowStatus { CONTINUE_TO_SEND, WAIT, OVERFLOW }

fun generateFlowControl(canId: String, fs: FlowStatus = CONTINUE_TO_SEND, 
                       blockSize: Int = 8, stMinMs: Int = 0): List<Byte>
```

---

## 3. Multi-ECU Routing 🔴

**Kylaq Gap:** Assumes 7DF→7E8 always. No routing table.

**openRS_ Reference:**
```python
ECU_MAPPINGS = {
    "PCM": {"request": 0x7E0, "response": 0x7E8},
    "BCM": {"request": 0x726, "response": 0x72E},
}
```

**Recommended:**
```kotlin
data class EcuMapping(
    val name: String, val requestCanId: String, 
    val responseCanId: String, val priority: Int,
    val supportedPids: Set<String>
)
```

---

## 4. PID Catalog 🟡

**Missing PIDs (per python-OBD reference):**
| PID | Name | Formula |
|-----|------|---------|
| 0x14-0x1B | O2 Sensors | Voltage/trim |
| 0x11 | Throttle B | A*100/255 |
| 0x2F | Fuel Level | A*100/255 |
| 0x33 | Barometric | A kPa |
| 0x42 | Module Voltage | (A*256+B)/1000 |
| 0x46 | Ambient Temp | A-40 |

---

## 5. DTC Decoder 🟡

**ELMterm Reference (DTCDecoder.swift):**
- OBD-II: 2-byte DTC → "P0133" ✅ (Kylaq has)
- UDS: 3-byte DTC → "P0133-00" ❌ (Missing)
- Status flags → 8 boolean flags ❌ (Missing)
- 40+ code descriptions ❌ (Limited)

**Recommended:**
```kotlin
fun decodeUdsDtc(a: Int, b: Int, c: Int): String {
    return "${decodeDtc(a, b)}-${c.toString(16).uppercase().padStart(2,'0')}"
}

fun statusFlags(status: Int): List<String> {
    // Return list of active status flags per J2012
}
```

---

## 6. Test Infrastructure 🟡

**Current:** 50 unit tests  
**Needed:** ELM327-emulator integration tests

**Recommended Test:**
```kotlin
@Test fun `7E9 first 7E8 second returns 7E8 vin`()
@Test fun `conflicting VINs returns ambiguous`()
@Test fun `only unauthorized ECU returns unavailable`()
```

---

## Prioritized Actions

### Phase 1 (Critical)
1. Multi-ECU routing architecture
2. Extended addressing for ISO-TP

### Phase 2 (Important)
1. Complete Mode 01 PID formulas (O2 sensors, etc.)
2. UDS 3-byte DTC support
3. ELM327-emulator integration tests

### Phase 3 (Nice to Have)
1. Flow Control generation
2. ECU discovery protocol
3. Manufacturer-specific PIDs

---

## Reference URLs

| Project | URL |
|---------|-----|
| OBDForge | github.com/edwardlthompson/OBDForge |
| ELMterm | github.com/Automotive-Swift/ELMterm |
| openRS_ | github.com/klexical/openRS_ |
| python-OBD | github.com/brendan-w/python-OBD |
| ELM327-emulator | github.com/ircama/ELM327-emulator |
