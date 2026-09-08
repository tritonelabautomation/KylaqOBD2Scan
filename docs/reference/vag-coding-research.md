# VAG Hidden-Feature Coding — Research Validation (Kylaq MID red theme / sport menu / infotainment colour theme)

**Date:** 2026-09-09 · **Car:** Skoda Kylaq 1.0 TSI Signature+ AT (2026, MQB-A0-IN) · **Author:** agent (Batch 23)

## 1. What was asked

1. "I want my MID to change to red theme" — the MaxiDot/instrument-cluster colour theme.
2. [codemyvag.in — Enable Display Sport Menu on Infotainment](https://codemyvag.in/unlock-hidden-feature/enable-display-sport-menu-on-infotainment-in-skoda-volkswagen-cars)
3. [codemyvag.in — Enable change colour theme of Infotainment system](https://codemyvag.in/unlock-hidden-feature/enable-change-color-theme-of-infotainment-system-in-skoda-volkswagen-cars)
4. Review of [tritonelabautomation/VW_REGIO.SW0876_20251109-1413](https://github.com/tritonelabautomation/VW_REGIO.SW0876_20251109-1413) "if any useful info".

## 2. How these features actually work (evidence)

### 2.1 They are UDS adaptation writes, not simple OBD commands
On MQB-era VAG cars (Virtus/Taigun/Slavia/Kushaq/Kylaq family), "hidden features" are
**adaptation channels and long-coding bits** inside specific control modules:

| Module | Address | What it holds |
|---|---|---|
| Infotainment | 5F | sport-menu display, colour-theme selection, startup screens, EQ presets |
| Instruments (MID/MaxiDot) | 17 | dial themes, colour profiles, display layouts |
| Central electronics | 09 | ambient/LED colours on trims that have them |

Changing any of them over UDS requires, in order:

1. `0x10` — enter **extended diagnostic session** (session control),
2. `0x27` — **security access** (request seed → compute key → send key),
3. `0x2E` / `0x31` — **write** the adaptation or run the coding routine.

The seed→key algorithms for 5F/17 are **not public**. They are licensed to tooling
vendors (VCDS/HEX-V2, OBDeleven, ODIS). Newer units (2020+, which includes a 2026
Kylaq) additionally enforce **SFD (Schutz Fahrzeugdiagnose)** — a Volkswagen online
authorisation the head unit checks with VW servers before accepting coding writes.
Even a genuine VCDS cannot write SFD-locked channels without an online token.
*This is why the feature is sold as a service (see 2.3) rather than published.*

### 2.2 What the two codemyvag.in links contain
Both pages are **marketing pages for a paid coding service** (DirectShift / Pramod
Sahoo, Greater Noida West, WhatsApp +91 784 000 2005; demo video
[youtube.com/watch?v=i5FWNvbiCgk](https://www.youtube.com/watch?v=i5FWNvbiCgk)).
They list benefits ("personalise your cabin", "drive data like a GTI") and per-model
feature menus — **they publish no adaptation channel numbers, DIDs, coding values or
procedure**. So the links validate *that* the features exist and are commercially
unlocked by professionals with licensed tools; they provide zero technical payload we
could implement against.

### 2.3 What the VW_REGIO.SW0876 GitHub repo contains
`VW_REGIO.SW0876_20251109-1413` is a **dump of a REGIO head-unit firmware update
package** (SW train 0876, build 2025-11-09): `boot.img`, `lk.img`, `bl33.img`
(bootloader stages), `system.new.dat.br` + `system.patch.dat` + `system.transfer.list`
(Android brotli OTA payload), `META-INF/com`, `compatibility.zip`.

Useful conclusions for us:

- REGIO is an **Android-based** head unit; themes/sport menus live as **feature flags
  and resources inside its system image**, activated by coding — confirming §2.1.
- Firmware update ≠ coding. There is nothing in that package that an OBD dongle can
  apply; flashing a head unit from a GitHub dump requires vendor tooling and would
  risk bricking the unit and voiding warranty. **We must not, and will not, attempt it.**
- It does show the theme/sport-menu code paths ship to every REGIO unit and are merely
  gated — which is exactly why pro coders can unlock them.

## 3. Feasibility from *this app* (ELM327 + our UDS stack) — honest verdict

| Capability | Verdict | Why |
|---|---|---|
| **Read** DIDs/adaptation-ish identifiers (UDS `0x22`) | ✅ Possible & shipped | SafetyValidator classifies `22` as `READ_ONLY_UDS` (`SafetyValidator.kt:42`), allowed |
| Session control `0x10`, security access `0x27`, write `0x2E`, routines `0x31`, flash `0x34-37` | ❌ Blocked by design | `WRITE_SERVICES = 2E`, `SECURITY_SERVICES = 27`, `SESSION_CONTROL_SERVICES = 10/28/31/85` (`SafetyValidator.kt:50-53`); every category returns `Rejected` in `validateCommand` |
| Compute 5F/17 seed→key | ❌ Impossible for us | Algorithms licensed (VCDS/OBDeleven/ODIS); not derivable from logs |
| SFD-protected writes on a 2026 car | ❌ Even licensed tools need VW online token | §2.1 |
| Flashing REGIO firmware from a repo dump | ❌ Never | Brick + warranty risk |

Even if we removed our own safety blocks, an ELM327-class adapter is the wrong tool:
multi-frame ISO-TP writes with strict P2 timings through a Bluetooth dongle are how
half-bricked clusters happen. **No responsible app ships that. We won't fake it.**

## 4. What we shipped in Batch 23 instead

1. **UDS Coding Lab (read-only)** — new screen in the drawer (`Screen.CodingLab`):
   pick an ECU header (7E0 engine, 7E1 gearbox, custom), pick/enter a DID (F190 VIN,
   F180, F191, 0100, or free hex), issue `0x22` through the existing
   `SafetyValidator`-gated transport, and see raw response, payload hex, ASCII decode,
   and named negative-response codes (e.g. `33 securityAccessDenied`,
   `31 requestOutOfRange`). Implementation: `protocol/CodingLabCodec.kt`,
   `ui/screens/CodingLabScreen.kt`, `MainViewModel.codingLabRead()`; tests in
   `CodingLabCodecTest`. This is the same "scout" workflow pro coders start with —
   you can *inspect* what your modules answer, and the NRC names teach you exactly
   where the security wall is.
2. **RED SPORT accent theme in-app** — the owner's "red theme" wish, delivered where
   we legitimately can: Settings → cyber / **red sport** / amber accent chips recolor
   the whole app live (`theme/Color.kt` `setAccentColor`, persisted as `accent_theme`).
3. **This research document** with the realistic owner routes below.

## 5. Realistic routes to actually get the red MID theme + sport menu on the car

1. **Professional coder with OBDeleven/VCDS + SFD token** (what codemyvag.in sells,
   ~₹2-5k per feature). Verify they hold current SFD access for 2026 MQB-A0-IN cars
   before paying; ask for a rollback note (original coding screenshot).
2. **Skoda dealer / ODIS** — dealers can code themes when the region's feature list
   permits; ask specifically for "theme activation 5F/17".
3. **Wait for official enablement** — REGIO firmware trains (like SW0876) ship theme
   code to all units; Skoda India has enabled feature batches via OTA/service campaigns.
4. **Warranty/legal note:** coding writes by third parties can affect warranty claims
   on the affected module. Reads (`0x22`) are passive and carry no such risk — that's
   what our Coding Lab does.

## 6. References

- codemyvag.in sport-menu page & colour-theme page (fetched 2026-09-09; marketing only)
- github.com/tritonelabautomation/VW_REGIO.SW0876_20251109-1413 (fetched 2026-09-09; firmware dump listing)
- `app/src/main/java/com/example/protocol/SafetyValidator.kt` (service categories, lines 16-53, 103-136)
- ISO 14229-1 (UDS): services 0x10, 0x22, 0x27, 0x2E, 0x31; NRC 0x31/0x33
- VW SFD: Schutz Fahrzeugdiagnose, online authorisation for coding on 2020+ vehicles
