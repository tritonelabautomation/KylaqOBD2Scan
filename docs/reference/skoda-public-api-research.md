# Škoda Public Vehicle API (via CarConnectivity-connector-skoda) — usefulness verdict for KylaqOBD2Scan

**Date:** 2026-09-13 · **Source reviewed:** github.com/tillsteinbach/CarConnectivity-connector-skoda
(README + commit history incl. #65 "migrate to Škoda Public API", fetched in full),
reddit r/enyaq "Škoda finally opens up its API" (28 Aug 2026), reddit r/Kylaq dongle thread (Jun 2025),
skoda-auto.com Kylaq connectivity pages.

## 1. What the repo actually is (2026 state)

A **Python** connector for the CarConnectivity framework talking to the **official Škoda Public
Vehicle API (beta)** at `public.api.connect.skoda-auto.cz`:

- **Auth:** `X-API-Key` header. Keys are **free and official**, created inside the **MyŠkoda app
  v8.16+** (Profile → Smart Home / key management, `go.skoda.eu/api-keys`). No credential scraping,
  no OAuth reverse-engineering anymore (old MyŠkoda/web-session auth was deleted in #65).
- **Shape:** one call per cycle: `GET /api/v1/vehicles/{vin}`. No list-vehicles endpoint — VIN(s)
  must be configured explicitly.
- **Rate limit:** **20 requests/hour per key**, minimum poll interval **300 s**.
- **Feature table (README):** vehicle status doors/windows/lights ✅ · **fuel level + range ✅** ·
  **odometer ✅** · **parking position GPS ✅ (needs Remote Access licence)** · AC status +
  start/stop ✅ · window heating ✅ · charging (EV) ✅ — but **lock/unlock ❌, honk ❌, wake-up ❌,
  maintenance/inspection info ❌, MQTT push ❌**.
- Known quirk worth remembering if porting: enums arrive **UPPERCASE** (`GASOLINE`, `ELECTRIC`).

## 2. Why it matters for THIS owner (India, Kylaq 1.0 TSI Signature+ AT 2026)

- r/Kylaq (Jun 2025): connectivity dongle (with Jio SIM) is **pre-installed and free on Signature+
  and Prestige**; other variants were cut off / sold a ₹16k dongle. **Owner's Signature+ is exactly
  an eligible trim** — his car already phones home to MyŠkoda+; the public API key flow is the
  missing door.
- His JioMotive JCR 1440 (B) is a *separate, aftermarket* path; the Škoda factory cloud is a
  **complementary** channel, not a replacement for either the JCR or our ELM327 J1979 stream.

## 3. Verdict — useful, but as a FUTURE OPTIONAL "Cloud Link", not today

| Question | Answer |
|---|---|
| Can we ship the Python library in the Android app? | **No** — wrong platform; CarConnectivity is a server-side Python framework. |
| Is the API itself worth porting to Kotlin later? | **Yes, conditionally** — it is official, key-based, and exposes exactly what J1979 cannot see while parked: fuel level %, range, odometer, parking GPS, AC-on state. |
| Does it fit the realtime dashboard? | **No** — 20 req/h cap. Realtime stays ELM327; cloud = low-frequency enrichment (≥300 s). |
| Any work saved by reading it now? | **Yes** — maintenance/inspection is NOT in the public API, so in-app Maintenance reminders correctly stay odometer/manual-based; no remote lock/unlock ambitions to chase. |

### Concrete future uses if the owner's key test passes (all additive, Batch-19 rule)
1. **Fuel-log cross-check & odometer auto-fill** — cloud odometer pre-fills the Fuel & Costs form;
   cloud fuel-level step-change corroborates a logged full-tank fill (anti-typo guard).
2. **"Where is my car" card** — parking GPS when away from the car (OBD dongle is dark then).
3. **AC-on / window-open reminders** on hot days from cloud AC+door state (feeds AcClimateModel context).
4. **Range vs our km/L model** reconciliation line in Trip Overview.

Porting shape (when green-lit): OkHttp `GET /api/v1/vehicles/{vin}` + `X-API-Key`, 300 s
WorkManager/foreground poll, disk cache with `max_age`, and **loud failure labels** per Batch-17
doctrine (key missing / 429 rate-limited / region-unsupported must SAY so, never silent "--").

## 4. Go/no-go checklist for the owner (≈5 minutes, ₹0)
1. MyŠkoda(+) app updated to **v8.16+** on his phone.
2. Profile → **Smart Home** → create an API key (if the menu is absent in the Indian build, the
   public API region-gate closes this route — record the result and move on; app stays standalone).
3. Note the VIN (RC book / windshield) — needed because there is no list-vehicles endpoint.
4. Optional proof call: `curl -H "X-API-Key: <key>" https://public.api.connect.skoda-auto.cz/api/v1/vehicles/<VIN>`
   → 200 with fuel/odometer JSON = route OPEN; 401/403/404 = region or licence gate.

## 5. Decision recorded
**No code change today.** This document is the persisted research; a "Cloud Link" batch starts only
after the owner's key test returns 200. Until then KylaqOBD2Scan remains 100 % local J1979 +
on-phone analytics, which is also its privacy selling point (no cloud, no subscription).
