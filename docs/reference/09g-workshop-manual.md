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
