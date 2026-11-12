# Stale verdicts: NO_RESPONSE over a live bus, and the VIN that never resolved (2026-09-19)

Owner report: dashboard screenshot showing Bluetooth and Adapter green, CAN and ECU grey,
`Verification NO_RESPONSE`, the banner "No ECU response received on current protocol profile",
`VIN Unavailable` with a READ VIN button - and at the same time a recording ACTIVE for 99:17 with
TX/RX 72,970, **CAN Frames 39,808**, errors 88, 44 PIDs active at Fast polling. Those two pictures
cannot both be true; one of them was stale state. Both roots were in code, not in the car.

## Defect 1 - a connect-time verdict that evidence could never revise

`ObdQuickConnect.publishHealth` judges NO_RESPONSE when its connect-time probes find zero
validated pids - correct behaviour against an ECU that is still waking up on the bus. But nothing
ever revised the verdict afterwards: `publishHealth` only writes while the current value is
UNKNOWN or TESTING, and the phone verification flow only runs on demand. So a single early probe
painted the banner, the grey CAN/ECU dots (`isEcu = health == WORKING || PARTIAL`) and the
NO_RESPONSE badge for the whole session, while the scheduler happily counted tens of thousands of
valid ISO-TP messages.

Fix: evidence over verdict. `healthAfterFrameEvidence` (pure, tested) upgrades NO_RESPONSE or
UNKNOWN to WORKING once 25 valid frames have arrived; the scheduler evaluates it every 25th frame.
PARTIAL, WORKING, TESTING and ADAPTER_ERROR are untouched - a partial capability bitmap and an
adapter fault are claims a frame count cannot settle, and an on-demand verification run must keep
ownership of its own verdict.

## Defect 2 - the VIN only resolved if a human opened the scan screen

`fetchVehicleVin()` (mode 09 pid 02, the 20-byte multi-frame VIN read) was called from exactly two
places: the AutoScan screen's flow and the dashboard's READ VIN button. The auto-connect path -
background service, adapter attaches, auto-record starts - never opens that screen, so on a drive
the phone connected by itself the ECU was never asked for its VIN at all: `vehicleVin` stayed null
forever, which is what "VIN is not resolved yet" observed.

Fix: a throttled watcher in MainViewModel on (connectionState, protocolHealth). Once the link is
CONNECTED and frame evidence has the bus WORKING or PARTIAL, and the VIN is still missing or
"VIN Unavailable", it waits 1.2 s and asks the ECU itself - at most once per minute, so a flapping
verdict cannot spam mode-09 reads. The dashboard button also stays visible after a failed attempt
("VIN Unavailable" used to hide it, leaving nothing to press).

## What this does NOT claim

* That the 0902 read itself was faulty: the transport serialises every command through one mutex,
  so polling cannot corrupt the VIN's multi-frame reassembly. The defect was that nobody asked.
* That PARTIAL health is now cosmetic: it still means some probed pids did not answer, and the
  capability bitmap remains the authority on which pids are live.
