# Škoda Gearbox 09G — Workshop Manual (Edition 07.2014), distilled

Source (direct PDF, verified accessible 2026-09-08):
`https://procarmanuals.com/wp-content/uploads/pdfs/transmission/skoda-gearbox-09g-workshop-manual-edition-072014.pdf`
Landing page: `https://procarmanuals.com/pdf-online-skoda-gearbox-09g-workshop-manual-edition-07-2014/`
(the landing page lazy-loads the PDF via JS; the direct `wp-content/uploads/pdfs/...` path works —
found through a Wayback snapshot of the old page layout).

Covers: Fabia II, Octavia II/III, Rapid (incl. India), Roomster, Superb II, Yeti — the same
**Aisin AW 6-speed 09G** family as the Kylaq's **AQ250-6F**. Parse limit: manual pages 1–26
(Rep. gr. 00/32/37 head); ATF-check procedure (p.147) and valve-body sections are beyond the
30-page PDF parse cap of the fetch tool.

## Gearbox identification
- Manufacturer: **AISIN AW**; gearbox code `2T` = 09G (e.g. `FXA 02 H 2T 00144`: FXA = gearbox
  ID chars, 02 = year, H = month A–M, 2T = 09G, serial).
- ID characters also appear on the vehicle data sticker.
- Installed in combination with 4-cylinder engines (Škoda applications).

## Ratio sets (factory tables, all Škoda 09G applications)
**Standard set** (GSY/HFS/GJZ/HFR/HTN/HTM/KGK/KGH/KGJ — 1.6/75 kW, 1.6/85 kW FSI, 2.0/110 kW FSI,
1.6/77 kW, JUF/KGG/MFZ/QAW Fabia II/Roomster/Rapid India/Rapid):

| Gear | Ratio |
|---|---|
| 1 | **4.148** |
| 2 | **2.370** |
| 3 | **1.556** |
| 4 | **1.155** |
| 5 | **0.859** |
| 6 | **0.686** |
| R | **3.394** |

**Variant sets** (proof that absolute scaling differs per application — the app's gear model
therefore self-calibrates rpm-per-kmh while keeping the SSP relative ratios):

| Code | Engine | 1 | 2 | 3 | 4 | 5 | 6 | R | Idler | Final |
|---|---|---|---|---|---|---|---|---|---|---|
| KGV (Superb II) | 1.8/112 kW TFSI | 4.044 | 2.371 | 1.556 | 1.159 | 0.852 | 0.672 | 3.193 | 48/53 = 0.906 | 61/15 = 4.067 |
| PLS (Rapid) | 1.6/81 kW MPI | 4.670 | 2.530 | 1.560 | 1.130 | 0.860 | 0.690 | 3.390 | 48/53 = 0.906 | 58/15 = 3.867 |
| PAL/QNQ (Octavia III) | 1.6/81 kW MPI | 4.670 | 2.530 | 1.556 | 1.130 | 0.859 | 0.686 | 3.394 | 48/53 = 0.906 | 61/15 = 4.067 |
| QEM (Yeti) | 1.6/81 kW MPI | 4.460 | 2.510 | 1.560 | 1.140 | 0.850 | 0.670 | 3.190 | 49/52 = 1.061 | 61/15 = 4.067 |

- **Intermediate (idler) gear variants:** drive 49 / output 52 = **1.061** (early Octavia II,
  Yeti) or drive 48 / output 53 = **0.906** (Fabia II, Roomster, Rapid, later builds).
- **Final drive variants:** crown 61 / output shaft 15 = **4.067** or crown 58 = **3.867**.

## ATF (Rep. gr. 00 §1.3 + §2.1)
- Planetary gear **new filling: 7.0 L**; **top-up: approx. 3 L**.
- Oil filling is shared by **planetary gear and final drive together**.
- **Filled for life** — no change within servicing; change only after repairs.
- Only ATF available as the spare part may be used (catalogue part; the SSP 291 spec is
  **G 052 025 A2**, Esso JWS 3309 equivalent). Other oils → functional problems or gearbox failure.
- Gearboxes from production 06.2006 have **no ATF filler tube**; level check per Rep. gr. 37 §4
  (p.147, beyond parse cap) — requires lifting the vehicle, ATF at ~35–45 °C, overflow-plug method.

## Torque converter (Rep. gr. 32)
- Converter is **welded — replace complete** if damaged; check hub for wear.
- Drain via suction (V.A.G 1782) if ATF contaminated or after major repair.
- Installation depth check: dimension `-a-` between housing flange and converter threaded bores
  must be **≥ 19.5 mm**; converter must rotate freely behind the drive plate before tightening
  the engine/gearbox flange screws, else the converter driver or ATF pump is destroyed.
- **Torque converter lock-up clutch (TCC):** closing is load- and speed-sensitive, low-vibration;
  with TCC closed, **gears 2–6 are mechanically driven without converter slip** (only 1st always
  runs through the converter hydraulics at launch).

## Controls & electronics (Rep. gr. 37, component summary)
| Component | Designator | Notes |
|---|---|---|
| Automatic gearbox control unit | **J217** | determines shift points; self-diagnosis; emergency running programme on sensor/component failure; gradient-adaptive shift maps (up/down), Tiptronic direct gear select for engine braking |
| Selector lever lock solenoid | **N110** | locks P/N until brake pressed; integrated in gearshift mechanism (not separately replaceable) |
| Slide valve body | — | bolted under the housing, covered by oil pan; valves regulate hydraulic pressure to clutches/brakes |
| Gearbox oil temperature sender | **G93** | integrated in the 8-pin wiring loom on the valve body |
| Gearbox input rpm sender | **G182** | on housing behind valve body |
| Gearbox output rpm sender | **G195** | on housing behind valve body |
| Multi-function switch | **F125** | on top of housing at gearshift shaft; tells TCU the selector position; must be set correctly (p.187) |
| Tiptronic switch | **F189** | in the gearshift mechanism PCB (not separately replaceable) |
| Selector lever position indicator | **Y6** | in dash insert; dark display = emergency run w/ TCU off, fully lit = emergency run w/ TCU on |
| Kick-down switch | **F8** | **not present on petrol engines** — kick-down is a stored threshold of accelerator pedal senders G79/G185 in the ECU, signalled over CAN |
| Brake light switch | **F** | footwell (≤05.2010) or on master cylinder (≥06.2010); signal to TCU via CAN |
| Terminal 50 relay | **J682** | E-box or relay holder under dash (by model/date) |

## Shift mechanism / service notes (Rep. gr. 37 §2–3)
- Selector lever control cable inspection/adjustment; ignition-key removal lock check;
  emergency release out of P (p.105).
- Basic settings required **every time the gearbox is installed**.
- Never run the engine or tow with oil pan removed / without ATF.
- Self-locking nuts and angle-tightened bolts must be replaced on removal; slide valve body
  must not be twisted — tighten diagonally in stages.
- ESD: earth yourself before touching gearbox electrics; never touch plug contacts.
- Sensors G93/G182/G195, F125, N110, F189, F8, F are all checked via self-diagnosis (VCDS
  "Targeted fault-finding").

## Relevance to the Kylaq 1.0 TSI Signature+ AT (AQ250-6F) app model
- Confirms the factory planetary set 4.148/2.370/1.556/1.155/0.859/0.686 + R 3.394 already
  implemented in `engine/GearModel.kt` (identical to SSP 291 GSY data).
- Variant ratio sets + two idler/final combos confirm per-application absolute scaling drift →
  validates the app's **self-calibrating rpm-per-kmh scale** (PRIOR 26.0, bounded ±25%).
- ATF: 7.0 L new fill ✓ implemented; **3.0 L top-up and "filled for life"** added to the parts
  guide from this manual.
- No J1979/OBD PID exposes F125 selector position or engaged gear on this platform — the app's
  gear estimation + user-tagged D/S/M mode remains the correct approach.

---

# Deep-service sections (recovered beyond the 30-page PDF cap)

Recovered via ManualsLib's HTML rendering of the same 197-page manual
(`https://www.manualslib.com/manual/2115103/Skoda-Fabia-Ii-2007.html?page=N`, ML page = body page + 4)
and — crucially — the **Skoda SLAVIA 2022 Maintenance Manual, Edition 11.2021**
(`https://www.manualslib.com/manual/4126971/Skoda-Slavia-2022.html?page=N`), the India MQB-A0
sibling of the Kylaq with the SAME AQ250/09G. Slavia text = Indian-market procedures.

## ATF level check & top-up (WM body p.147–151; Slavia §4.1.1 identical)
Special tools: ATF filling system **V.A.G 1924**, oil-filling adapter **VAS 6262 / 6262A**,
quick coupling **VAS 6262/2**, catch pan **VAS 6208**, goggles/gloves.

Test conditions:
- Gearbox NOT in emergency running mode; vehicle absolutely horizontal (four-column lift or pit).
- Selector in **P**, engine idling; A/C and heating OFF.
- Diagnostic tester connected → "Vehicle self-diagnosis" → **"02 - gearbox electronics"**.
- **ATF temperature ≤ 30 °C at test start** (cool the gearbox first if needed); temperature is
  read in measuring block "08 - read measured value block".
- Level changes with temperature: too cold → over-fill risk; too hot → under-fill risk; both
  impair operation. Only spare-part ATF; shake the reservoir before opening.

Procedure:
1. Shorten the VAS 6262A vent pipe to dimension **-a- = 210 mm** (measured from the green band)
   so it doesn't touch the bottle bottom. Filling hose/adapter must be clean; never mix ATFs.
2. Remove sound dampening; position drip tray; start engine, idle. (Radiator fan may start —
   keep distance.)
3. At **35 °C ATF temperature**: unscrew the **ATF inspection plug** (overflow plug, bottom of
   gearbox). Gasket ring is ALWAYS replaced. ATF in the overflow tube drains first.
4. **Level correct** if more ATF keeps dripping out (**~1 drop per second**) via the overflow
   tube **before the ATF reaches 40 °C**.
5. Fit plug with new gasket ring, tighten to **27 Nm** (Slavia states the torque explicitly).
6. **At the latest at 45 °C — in hot countries (India) 50 °C — the plug must be closed again.**
7. If nothing flows out up to 45 °C → fill: attach V.A.G 1924 reservoir as high as possible,
   fill until ATF overflows between 35–45 °C, let excess drip until it just starts to drain,
   close plug (27 Nm, new ring). Slavia: **fill 1 L increments when topping up**.
8. End function "08", tip "06 - End output", ignition off, disconnect tester.
- Note: gearboxes from production **06.2006 have no filler tube** — filling goes through the
  inspection-plug opening with the VAS 6262/2 adapter (older Octavia II: filler tube at front
  of gearbox under the starter; cap destroyed on removal → always replace).

## Change ATF / top up after repair (WM body p.152–154)
- Engine OFF. Same tools. Drain into VAS 6208 (observe disposal rules).
- **Never start the engine without ATF; never tow the vehicle without ATF.**
- Refill via inspection opening, then run the level check above.
- **Slavia 2022 (India service schedule) quantities: fill 3 L of ATF when CHANGING
  (drain-and-fill; converter + cooler retain the rest), fill 1 L if topping up.** Total
  capacity remains 7.0 L (planetary + final drive share the ATF).
- The 2014 EU manual calls the fill "lifetime"; the India maintenance manual lists
  "Automatic gearbox 09G: change ATF" as a scheduled service item — for the Kylaq, follow
  the India schedule.

## Emergency release out of P (WM body p.105–106)
N110 locks the selector in P; released electrically only with ignition on/engine running +
brake pedal + knob button. Dead battery / blown fuse / defective solenoid → lever stuck in P.
1. Check fuses and battery voltage first.
2. Remove the cover for the shift mechanism (body p.58 procedure).
3. **Fabia II / Roomster / Rapid / Yeti / Octavia II from 10.2009 / Superb II / Octavia III
   (i.e. the modern layout, same as Kylaq): press the YELLOW PLASTIC WEDGE** in the arrow
   direction — this releases the magnet locking. (Old Octavia II ≤05.2009: screwdriver into
   the retaining eye, press lever -B- inward.)
4. Press the selector-lever button and pull the lever out of P.
5. If shifted back into P, it locks again. Shift mechanism is only replaceable as a complete
   unit (N110/F189 are not separately serviceable).

## Component/plug map (body p.104, Octavia II ≥11.2011 / Fabia II / Roomster / Rapid layout)
- A = 10-pin plug: gearshift mechanism → J217
- B = 4-pin plug: selector lever lock solenoid N110 + selector-lever-blocked switch **F319** in P
- C = 10-pin plug: cover for gearshift mechanism (Tiptronic F189 lives in its PCB)
- (Fabia II/Roomster ≤10.2011: A = 2-pin N110, B = 10-pin J217 lines, C = 10-pin cover)

## ManualsLib page map for future deep fetches (Fabia II manual 2115103, body+4)
| Topic | Body p. | ML page |
|---|---|---|
| Ignition key removal lock check | 57 | 61 |
| Shift mechanism cover removal | 58 | 62 |
| Selector cable inspect/adjust | 55–57 | 59–61 |
| Selector mechanism R&R | 74–92 | 78–96 |
| Emergency release P | 105–106 | 109–110 |
| Gearbox removal | 107–132 | 111–136 |
| Tightening torques (incl. M10/M12) | 134–145 | 138–149 |
| ATF level check | 147–151 | 151–155 |
| ATF change after repair | 152–154 | 156–158 |
| ATF cooler circuit / radiator | 155–164 | 159–168 |
| Oil pan / strainer / valve body overview | 165–171 | 169–175 |
| Valve body R&R | 171–180 | 175–184 |
| Wiring looms (8-pin G93 / 14-pin) | 180–184 | 184–188 |
| G182 / G195 rpm senders | 183–184 | 187–188 |
| F125 removal / setting | 185–188 | 189–192 |
| Final drive seals | 189–193 | 193–197 |

Slavia 2022 Maintenance Manual (India): ATF change §4.1 = ML p.46–51; final drive/joint boots
§4.2 = ML p.51–52; tow starting/towing = ML p.103; road test = ML p.105.
