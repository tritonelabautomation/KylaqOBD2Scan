# VAG ECU / DID research audit — 2026-09-17

Trigger: a second AI was asked what open-source GitHub work exists for this car's ECU and
transmission DIDs. Its answer recommended `bri3d/MQBSimosLogVariables` (Simos18 `0x22`
ReadLocalIdentifier CSV) as "directly applicable" to the Kylaq 1.0 TSI, cited
`aep/vag_reverse_engineering` as protocol validation, and concluded the AQ250/09G TCU has
zero public DID documentation. Every claim was re-checked at source (standing rule: fetch
before judging). This document records what held, what did not, and the one fact that
invalidates the headline recommendation.

## 1. Claim-by-claim verdict

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `bri3d/MQBSimosLogVariables` exists, collects `0x22` ReadLocalIdentifier variables + scaling equations as CSV for Torque-style loggers | **TRUE** | Repo README: "Collect 0x22 ReadLocalIdentifier variables and conversions for use in Torque and other logging software. Compatible with Simos18 cars like MQB / MK7 VW Golf R, GTI, 1.8, Audi S3/A3". 4 commits, 14 stars. `exportedPIDs.csv` rows include `Charge Pressure Actual` = `0x22202a`, `(A*256+B)*0.1`, kPa, header `7E0`, empty `startDiagnostic`; `Torque Actual` = `0x22437c` ×0.1 Nm; `Wastegate Position Actual/Specified` = `0x2239a2/a3` ×0.01; `Fuel Rail Pressure MQB` = `0x22f423` ×10 kPa; lambda actual/specified = `0x2210c0` / `0x221456` ×0.000977 |
| 2 | Simos18 "manages gasoline engines … ranging from the 1.0 TSI three-cylinder to the 2.0 TSI EA888 Gen3" | **TRUE as quoted, but it is a marketing page** (`reverseengineer.net`, 2026-03-23) describing the *European* MQB estate — Golf VII/VIII, Audi A3/Q3, Octavia, Leon. It says nothing about India 2.0 cars | fetched page |
| 3 | "If your Kylaq's engine ECU is a Simos18 variant, this repo's DID list is directly usable" | **PREMISE FALSE — see §2.** The Kylaq's 1.0 TSI is not Simos18 | §2 |
| 4 | `aep/vag_reverse_engineering` confirms the `22 1D D0` → `62 1D D0 B6` byte sequence and that `10 03` may be needed first | **TRUE but misrepresented** | Repo **archived read-only 2022-08-16**, last commit **2019-05-06**, 24 stars. The quoted exchange is on request/response IDs **0x765/0x7CF** — not 7E0/7E8 — and DID `1DD0` is the author's **HV battery state of charge** (value "decreases as I drain the HV battery … with the AC"): an **e-Golf/GTE**, not a 1.0 TSI. Day-5 note: "The session thing is not needed to get this specific value." **Day 4 is the part that matters to us and was omitted: "ELM327 cannot send these messages"** — the author went on to reflash the PIC18F25K80 to work around it. So this repo is *not* validation that our adapter can do it |
| 5 | `bri3d/VW_Flash` supports Simos18.1/6/10, DQ250-MQB, DQ381-MQB DSG, Gen5 Haldex — no AQ250/09G | **TRUE, verbatim** | README: "Currently supports full custom reflashing of the Continental/Siemens Simos18.1/6, and Simos18.10 control units … as well as the Temic DQ250-MQB, Bosch DQ381-MQB DSG, and Gen 5 Haldex4Motion". Supported hardware: Macchina A0 + BridgeLEG (J2534), Tactrix OpenPort 2.0 J2534, SocketCAN — **never an ELM327** |
| 6 | AQ250/09G tuning is closed-source/commercial (RevMap) and no public DID docs exist | **SUBSTANTIALLY TRUE**, with one omission: commercial flash support for the box does exist — PCMflash lists `AL1000/AQ250/AQ450 (0C8/09G)` UDS virtual read/write/checksum. Still closed, still paid, still no DIDs | ecutools.eu PCMflash protocol list |
| 7 | "Confirm Simos18 vs MED17 via Mode 09 PID 04 (Calibration ID)" | **RIGHT METHOD** — and it is already implemented here (`EcuDiscoveryManager` reads `0904`/`090A`, plus `22 F187`/`22 F189`). **We have simply never recorded the real car's answer**: the only `04C906027A` in this repo is a *synthetic* `SimulationTransport` fixture, not owner data | `SimulationTransport.kt:314`, `kylaq-pid-validation-2026-09-16.md` (no 0904/090A row) |

## 2. The fact that kills the headline recommendation

Every 1.0 TSI EA211 (85 kW / 115 PS) in the VW Group India 2.0 + MQB-A0 family runs
**Bosch MED17.1.27**, not Continental Simos18. From the DAMOS/OLSx tuning-file indexes
(Bosch hardware number `0261S21435`, VAG hardware `04C907309Bx`, SW `04C9060xx`):

| Car | Engine ECU | VAG SW version |
|---|---|---|
| **Škoda Kushaq 1.0, 115 PS/85 kW** | Bosch MED17.1.27 | `04C906025DF` (HW `04C907309BB`) |
| **Škoda Slavia 1.0 TSI, 115 PS** | Bosch MED17.1.27 | `04C906025DL` |
| **VW Taigun 1.0 TSI, 115 PS** | Bosch MED17.1.27 | `04C906025DL` |
| **VW Virtus 1.0, 115 PS** | Bosch MED17.1.27 | `04C906025CD` |
| VW Polo BZ4 / Nivus / UP! GTI / Audi A1 1.0 TFSI, 115 PS | Bosch MED17.1.27 | `04C906025FQ` / `…CT` / `…F` / `…DB` |
| Škoda Karoq 1.0 TSI 115 PS (EU, DKRF) | Bosch MED17.1.27 | `04C906025BK` |

The Kylaq is the same 1.0 TSI (85 kW/115 PS, 178 Nm) on MQB-A0-IN, and its ECU part-number
prefix will be `04C9xx` (the `04C` block = 1.0 L EA211). Simos18 is the **EA888 1.8/2.0**
controller in this ecosystem; the Simos18 LocalIdentifier map (`0x202A` charge pressure,
`0x437C` torque, `0x39A2` wastegate …) is a different ECU's RAM layout and has no reason to
resolve on a MED17.1.27.

**Confirmation is a 30-second owner action, not a debate:** read Mode `0904` (Calibration ID)
+ `090A` (ECU Name) + `22 F187` (spare part number) + `22 F189` (SW version) at `7E0`.
An answer of the form `04C9060xx` / `04C907309xx` ⇒ MED17.1.27 family ⇒ the Simos18 CSV is
out. An answer containing `SIMOS`/`SC8`/`SCG` ⇒ the CSV becomes a live candidate list.
Until that is recorded, **no third-party DID may be treated as applicable** (F-6 rule: the
car outranks every article).

## 3. What the other answer missed about *this* app

1. **The probe it recommends as future work already ships.** Coding Lab does a passive
   `0x22 F190` VIN sweep across `7E0`–`7E7` and manual `0x22` reads at any header/DID
   (`MainViewModel.kt:1358/1386`, `ATSH 7E0` at `:1369/1406`); `SafetyValidator` permits
   `READ_ONLY_UDS_SERVICES = setOf("22")`. So `7E1` (transmission, per the VIN-authority
   model) is *already* inside the sweep range — nothing new to build to try it.
2. **It blocks the one step the plan depends on.** `SESSION_CONTROL_SERVICES = setOf("10",
   "28", "31", "85")` — a user-typed `10 03` is refused by design (the DTC reader sends it
   on a fixed, internal path only). Any DID that needs extended session cannot be reached
   from Coding Lab, and that is deliberate: on a 2026 car the interesting data additionally
   sits behind `0x27` seed-key **and SFD2 online authorisation** (our own empirical result —
   `vag-coding-research.md` §3: refused with an SFD2 error).
3. **The engine-side prize it dangles, we already have over plain J1979.** The 2026-09-16
   owner run validated `0161`/`0162` (torque demand/delivered %), **`0163` engine reference
   torque = 175 Nm**, **`0165` turbocharger boost pressure**, `019D` fuel mass rate, `018B`
   fuel-pump duty on this exact car. Torque % × 175 Nm *is* the live torque channel the power
   curve is built from. UDS `0x22` would add resolution and channels (lambda, wastegate duty,
   per-cylinder timing), not the existence of torque/boost.
4. **The real gap is gearbox-side, and it is a documentation gap, not a tooling gap.** We hold
   `09g-workshop-manual.md` (AQ250/09G: sensors G93/G182/G195, F125, N110, F189 Tiptronic
   switch) — signal names without DIDs. No open repo bridges that; VCDS/ODIS measuring blocks
   are the licensed route, and the app will keep estimating gear from rpm/speed physics
   (`Aq250GearModel`) rather than invent a DID it cannot verify.

## 4. Open item this audit exposes in our own records

`kylaq-pid-validation-2026-09-16.md` documents two responders, **7E8** (engine) and **7E9**,
with `7E9`'s identity never established, and its §2 note "(manual gearbox)" next to silent
`01A4` — an inference that contradicts the car (6-speed TC automatic). Most likely reading:
`01A4` is silent because the *engine* ECU does not publish gear and the TCU is not answering
Mode 01 on this car; the AQ250 does expose gear through the Tiptronic switch (F189) and its
own measuring blocks, not through J1979. Next owner run should capture, verbatim:

1. `0902` VIN, `0904` Calibration ID, `090A` ECU Name at `7DF` (which ECUs answer Mode 09).
2. Coding Lab → SCAN ECUs (`22 F190` sweep `7E0`–`7E7`) → which IDs answer at all.
3. Coding Lab manual reads: `7E0` + `F187`, `F189`, `F191`, `F1A2`; `7E1` + `F187`, `F189`,
   `F190`. Record the raw hex; identify `7E9`/`7E1` from part numbers, never from guesses.
4. One probe of the Simos18 CSV's best-known DID at `7E0`: `22 202A` (charge pressure). A
   `62 202A …` answer would falsify §2; `7F 22 31` (requestOutOfRange) or `7F 22 33`
   (securityAccessDenied) confirms it and is itself the citable result.

No app constants, decoders or UI change on the strength of third-party DID lists. Anything
new enters the catalog only after it answers on this car with a physically plausible value
(NO-FAKE-VALUES rule).

## Sources fetched 2026-09-17

- github.com/bri3d/MQBSimosLogVariables (+ `exportedPIDs.csv`)
- github.com/aep/vag_reverse_engineering/blob/master/LOG.md (archived 2022-08-16)
- github.com/bri3d/VW_Flash README (raw)
- reverseengineer.net/continental-simos-18-ecu-reverse-engineering/ (marketing-grade)
- damos-world.com OLSx indexes (Kushaq / Slavia / Taigun / Virtus / Karoq / Polo 1.0 TSI)
- ecudiag.es MED17.1.27 `0261S21435` hardware listings; ecutools.eu PCMflash protocol list
- repo: `docs/reference/kylaq-pid-validation-2026-09-16.md`, `vag-coding-research.md`,
  `09g-workshop-manual.md`, `skoda-github-ecosystem-survey.md`,
  `app/src/main/java/com/example/{discovery/EcuDiscoveryManager,protocol/SafetyValidator,
  ui/viewmodel/MainViewModel,ui/screens/CodingLabScreen,bluetooth/SimulationTransport}.kt`
