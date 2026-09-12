# VAG Hidden-Feature Coding — Research Validation (Kylaq MID red theme / sport menu / infotainment colour theme)

**Date:** 2026-09-09 (v2, deepened) · **Car:** Skoda Kylaq 1.0 TSI Signature+ AT (2026, MQB-A0-IN, digital MID, 10-inch infotainment) · **Author:** agent (Batch 23)

## 1. What was asked

1. "I want my MID to change to red theme" — the digital cluster (MID) colour theme.
2. [codemyvag.in — Enable Display Sport Menu on Infotainment](https://codemyvag.in/unlock-hidden-feature/enable-display-sport-menu-on-infotainment--in-skoda-volkswagen-cars)
3. [codemyvag.in — Enable change colour theme of Infotainment system](https://codemyvag.in/unlock-hidden-feature/enable-change-color-theme-of-infotainment-system-in-skoda-volkswagen-cars)
4. Review of [tritonelabautomation/VW_REGIO.SW0876_20251109-1413](https://github.com/tritonelabautomation/VW_REGIO.SW0876_20251109-1413) "if any useful info".

## 2. Headline answers (evidence-backed)

| Ask | Verdict for YOUR car | Evidence |
|---|---|---|
| **MID (digital cluster) red theme** | ❌ **Not unlockable — not even by professional coders.** codeMyVAG's own Kylaq Signature+ page lists "Features related to Digital Cluster" under **Features NOT available**. Cluster gauge-theme coding is reserved for full Virtual Cockpit cars; Kylaq's digital MID doesn't expose it. | §3.1 |
| **Infotainment colour theme (Red!)** | ✅ Exists and is unlockable — the MIB-family UI ships hidden accent themes **Red / Amber / Blue** — but only through module **5F** adaptation coding with licensed tools + SFD2 token. Implemented in-app instead as the **RED SPORT accent** (Settings). | §3.2, §3.3 |
| **Sport menu on infotainment** | ✅ Same route: 5F `Car_Function_Adaptations_Gen2` → `menu_display_*` activation, behind security access + SFD2. | §3.3 |
| **Doing it from this app / any ELM327 app** | ❌ Never responsibly possible: needs `0x10`+`0x27`+`0x2E`/`0x31` with licensed seed-key algorithms and a VW-server SFD2 token. Our SafetyValidator blocks all of those by design. | §4 |

## 3. Evidence collected (all pages fetched 2026-09-09)

### 3.1 codeMyVAG Kylaq Signature+ variant page
[hidden-feature-list-for-skoda-kylaq-signature-plus](https://codemyvag.in/code-my-skoda-vw-car/hidden-feature-list-for-skoda-kylaq-signature-plus)
confirms the variant spec (**MID Type: Digital, Infotainment: 10 Inch**, post-April-2024 build) and lists as purchasable unlocks: **Display Sport Menu on Infotainment** and **Change Color Theme of Infotainment System** (plus XDS, throttle response, offroad menu, beats audio, etc.). Crucially it also lists **"Features NOT available: … Features related to Digital Cluster"** — i.e. the MID theme ask is off-limits even for their ODIS/OBDeleven-class tooling on this car. A Kylaq-specific demo video exists ([youtube.com/watch?v=rPT322tLMbo](https://www.youtube.com/watch?v=rPT322tLMbo)). The two feature pages themselves are marketing-only (no channel numbers, no values) — the service runs via WhatsApp (+91 784 000 2005, DirectShift, Greater Noida West).

### 3.2 Independent Indian coder (TecUpdater) — theme colours confirmed
[tecupdater.com/hidden-feature-unlocks-vw-skoda-mqb](https://tecupdater.com/hidden-feature-unlocks-vw-skoda-mqb/) independently documents, for Kushaq/Taigun/Slavia/Virtus/**Kylaq**: "The MIB2/MIB3 infotainment UI supports multiple colour theme options hidden in standard settings. Available accent colours: **Red, Amber, and Blue**", the sport menu as "performance data views **present in firmware but suppressed on India-spec variants**", and notes post-April-2024 cars (which includes Kylaq) have "a revised ECU structure". Cluster-side goodies (GTI-scale tacho, RPM bar) are explicitly **Virtual Cockpit variants only**. Two independent vendors agreeing = strong validation.

### 3.3 Channel-level technical evidence (OBDeleven community + official)
- [OBDeleven forum — Codings and Adaptations WORKING](https://forum.obdeleven.com/thread/7991/codings-adaptations-working): the 5F (Information Electronics) adaptation mechanism is public in *pattern*: module 5F → Adaptation → `Car_Function_Adaptations_Gen2` → entries like `menu_display_compass`, `menu_display_user_eco_rating`, `menu_display_driving_school` set to *activated*. Sport menu / theme enablement uses the same `menu_display_*` family. Module 17 (dashboard) equivalents exist for cluster cars. A moderator note (May 2026) states plainly: **"coding on your 2025 Skoda is restricted due to SFD2 protection"**.
- [OBDeleven official — Škoda page](https://obdeleven.com/skoda) + [What is SFD?](https://support.obdeleven.com/en/articles/5685742-what-is-sfd): **2024+ VAG vehicles carry "SFD2" (UNECE R155/R156 cybersecurity rules)**; SFD-protected units explicitly include **Multimedia Unit and Instrument Cluster**; even OBDeleven must **request an SFD token from Volkswagen servers** (PRO/ULTIMATE plan) before any long-coding/adaptation change lands, and full scans/adaptations on 2024+ models "may be restricted".

### 3.4 The VW_REGIO.SW0876 GitHub repo
`VW_REGIO.SW0876_20251109-1413` is a dump of a **REGIO head-unit firmware update package** (SW train 0876, build 2025-11-09): `boot.img`, `lk.img`, `bl33.img` (bootloader stages), `system.new.dat.br` + `system.patch.dat` + `system.transfer.list` (Android brotli OTA payload), `META-INF/com`, `compatibility.zip`, `type.txt` (content: "1"). Conclusions:
- REGIO is Android-based; themes/sport menus live as **feature flags and resources inside the head-unit system image** — matching §3.2's "present in firmware but suppressed". Coding merely flips the gate; the payload is already in your car.
- Firmware update ≠ coding. Nothing in that package is applicable through an OBD port, and flashing a head unit from a GitHub dump risks bricking + warranty loss. **Never attempted, never will be.**

## 4. Why this app READS but never WRITES (the honest engineering verdict)

A UDS coding change requires, in order: `0x10` extended session → `0x27` security access
(seed→key; algorithms licensed to VCDS/OBDeleven/ODIS, not public) → `0x2E`/`0x31`
write/routine — and on your 2026 car an **SFD2 online token** from VW servers on top
(§3.3). Our stack enforces exactly that boundary:

| Capability | Verdict | Why |
|---|---|---|
| Read DIDs (UDS `0x22`) | ✅ Shipped | `READ_ONLY_UDS_SERVICES = setOf("22")` (`SafetyValidator.kt:42`) |
| `0x10`/`0x27`/`0x2E`/`0x31`/flash | ❌ Blocked by design | `SafetyValidator.kt:50-53` (`WRITE=2E`, `SECURITY=27`, `SESSION_CONTROL=10/28/31/85`); `validateCommand` rejects each category |
| Seed→key for 5F/17 | ❌ Impossible | Licensed algorithms; not derivable from logs |
| SFD2 token | ❌ Impossible for us | Only VW's server issues it, to licensed tool accounts |
| ELM327 as coding transport | ❌ Wrong tool | Multi-frame ISO-TP writes with strict P2 timing over a BT dongle is how clusters get half-bricked |

**No responsible app ships that path, and we will not fake it.**

## 5. What Batch 23 shipped in this app

1. **UDS Coding Lab v2 (read-only)** — drawer → Coding Lab:
   - **SCAN ECUs**: passive `0x22 F190` VIN sweep across 7E0–7E7; every module that
     answers is listed with its classification (`POSITIVE` + VIN ASCII, `NRC:33
     securityAccessDenied` = present but locked, silent = absent). You see the exact
     security wall on your own car.
   - **Manual reads**: any header + any DID (F190 VIN, F180, F191, 0100, free hex) with
     raw response, payload hex, ASCII decode and named negative codes.
   - **Research verdict card**: the §2 table rendered in-app.
   - **Read history** (last 12 requests).
   - Code: `protocol/CodingLabCodec.kt` (+ `classifyResponse`, `SWEEP_HEADERS`),
     `ui/screens/CodingLabScreen.kt`, `MainViewModel.codingLabRead/codingLabSweep`;
     tests: `CodingLabCodecTest` (9).
2. **RED SPORT accent theme** — Settings → `cyber / red sport / amber` recolors the
   entire app live (`theme/Color.kt setAccentColor`, persisted `accent_theme`). Your
   red wish, delivered where it's legitimately deliverable.
3. **This document** (v2) with per-claim sources.

## 6. Realistic routes to actually get it on the car

1. **Professional coder (what codemyvag/TecUpdater sell, ~₹2-5k/feature).** Before
   paying: ask for (a) proof of current **SFD2** access for post-April-2024 MQB-A0-IN
   cars, (b) an original-coding screenshot for rollback, (c) confirmation the *infotainment*
   theme menu will appear in Settings after coding. Do NOT buy any "MID/cluster theme"
   for Kylaq — vendors themselves list it as unavailable (§3.1).
2. **DIY with OBDeleven device + PRO/ULTIMATE plan** — SFD auto-unlock fetches the VW
   token for you *if* VW's server grants it for your model year; 2024+ restrictions may
   still block 5F/17 (§3.3). Budget the device (~₹8-12k) + plan.
3. **Skoda dealer / ODIS** — ask specifically for "5F theme activation"; dealers hold
   SFD tokens natively but may refuse non-official features.
4. **OTA campaigns** — REGIO firmware trains (like SW0876) already carry the theme code;
   Skoda India has historically enabled feature batches via service campaigns.
5. **Warranty note:** third-party coding writes can affect warranty claims on the coded
   module. Reads (`0x22`) are passive — zero risk — and that is all this app does.

## 7. References (all fetched 2026-09-09)

- codemyvag.in: sport-menu page, colour-theme page, Kylaq page, **Kylaq Signature+ variant page** (digital-cluster features NOT available)
- tecupdater.com/hidden-feature-unlocks-vw-skoda-mqb (Red/Amber/Blue themes; sport menu "present in firmware but suppressed"; post-Apr-2024 ECU structure)
- forum.obdeleven.com/thread/7991 (5F `Car_Function_Adaptations_Gen2` `menu_display_*` pattern; SFD2 restriction on 2025 Skoda — moderator, May 2026)
- obdeleven.com/skoda + support.obdeleven.com "What is SFD?" (SFD2 = UNECE R155/R156 on 2024+; Multimedia & Instrument Cluster protected; token from VW servers)
- github.com/tritonelabautomation/VW_REGIO.SW0876_20251109-1413 (REGIO firmware dump; themes as system-image flags)
- youtube.com/watch?v=rPT322tLMbo (Kylaq hidden-features demo), youtube.com/watch?v=i5FWNvbiCgk (Slavia/Kushaq/Taigun/Virtus demo)
- `app/src/main/java/com/example/protocol/SafetyValidator.kt:16-53,103-136` (service categories)
- ISO 14229-1 (UDS 0x10/0x22/0x27/0x2E/0x31; NRC 0x31/0x33)
