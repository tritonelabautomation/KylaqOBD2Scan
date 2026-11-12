# QA/QC — Polling mode vs CAN spec vs ELM327 reality (2026-09-19)

Owner, with a dashboard screenshot (profile ISO 15765-4 11-bit/500k, Polling Mode Fast 125 ms,
0 PIDs active): *"ISO 15765-4 CAN 11-bit 500kbps vs current sampling period of Safe 500ms,
normal 250ms, fast 125ms what's the difference any issues with current hardware ELM327
capabilities?"*

## The three layers, and which number lives where

1. **Bus layer — ISO 15765-4, 11-bit IDs, 500 kbit/s.** How fast bits move on the car's CAN and
   how frames are addressed. One OBD request is ~110-130 bits on the wire ≈ **0.26 ms**; the
   ECU's reply similar plus its processing time. Even at Fast (~7-8 requests/s) the diagnostic
   traffic is **well under 1 % of bus load** - the powertrain bus never notices, and no polling
   mode can disturb vehicle traffic or harm the car. This layer is a *capability*, not a rate
   the app sets.
2. **Link layer — phone ↔ ELM327 over Bluetooth Classic SPP.** The ELM327 is a half-duplex
   serial command/response bridge: one request in flight, text in, text out. Each round trip
   costs the BT latency plus UART time - **~100-150 ms on a good chip**. The owner's own
   99-minute session sustained 39,808 frames ≈ **6.7 req/s ≈ 150 ms/round trip**: squarely in
   the "high-quality adapter" band the Fast chip advertises.
3. **App layer — the Safe/Normal/Fast chips.** In `ObdScheduler` the mode is a **multiplier on
   each PID's own due interval** (×2 / ×1 / ×0.5, floored at 60 ms) plus an inter-command delay
   (100 / 30 / 10 ms). The chip labels (500/250/125 ms) are per-COMMAND targets. With P live
   PIDs in the priority-sorted round robin, one full cycle costs ≈ P × observed round trip -
   at ~40 live PIDs and 150 ms that is **~6 s per signal**, which is exactly why the staleness
   floors are 2.5/5/15 s and why the tank-level row for since-refuel arrives every few seconds.
   No mode makes any single signal refresh at its label rate; the serial link governs.

## Hardware verdict for this adapter

- At Fast it sustained ~6.7 req/s for 99 minutes - the good-chip band. **Keep Fast** while the
  Errors counter stays at/near zero.
- The failure mode Fast can expose is a **clone ELM327** (slow UART, tiny buffers): BUFFER FULL,
  timeouts, climbing Errors. That is precisely what Safe (100 ms inter-command, ×2 intervals)
  exists for. If Errors climb or tiles stale out often, drop to Normal first, Safe second -
  logging integrity never depends on the mode (journal flushes per row; timeouts are handled;
  adaptive stale thresholds keep tiles honest instead of frozen-wrong).
- The screenshot's "UNVERIFIED" badge is unrelated to cadence: it is the profile validation
  state - run Test Protocol once the link is live (standing owner item: re-run PID discovery).

## What ships

The polling card now prints the measured layer, refreshed every 3 s while connected:
"Observed on this link: ~X ms per request (Y req/s). One full cycle over N live PIDs ≈ Z s -
that is how often each signal refreshes. The CAN bus (500 kbit/s) is never the limit; the
ELM327 serial round trip is." Source: the scheduler's existing per-PID EWMA of observed query
gaps (`queryGapEwmaMs`), averaged by the new pure `PollCadence` object; live-PID count = enabled
∩ validated-live. Before first connection it says so honestly instead of showing nothing.

Tests: `PollCadenceTest` (mean null until measured; cycle = gap × live PIDs; req/s edge at 0).
