"""
Replay a captured ELM327 TX/RX trace through the ported pipeline and report what
the app's UI layer would actually display.

Usage:
    python3 tools/trace_sim/replay_trace.py [trace_file]

The trace format is the RawLogManager / previous-app format:
    HH:MM:SS.mmm TX > <command>
    HH:MM:SS.mmm RX < <line>
"""

import sys
import os
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from port import (PidCapabilityManager, PidDefinition, decode, get_physical_request_id,
                  reassemble_lines, extract_bitmaps_by_can_id, DIRECT_VALIDATED)

# Live PID set from DefaultPidDefinitions (subset exercised by the reference trace)
PID_DEFS = OrderedDict([
    ("010C", PidDefinition("010C", "01", "0C", "Engine RPM", "RPM", "RPM_FORMULA")),
    ("010D", PidDefinition("010D", "01", "0D", "Vehicle Speed", "km/h", "RAW_A_KMH")),
    ("0111", PidDefinition("0111", "01", "11", "Throttle Position", "%", "PERCENT_255")),
    ("010B", PidDefinition("010B", "01", "0B", "Intake MAP", "kPa", "RAW_A_KPA")),
    ("0105", PidDefinition("0105", "01", "05", "Engine Coolant Temp", "C", "TEMP_MINUS_40")),
    ("010F", PidDefinition("010F", "01", "0F", "Intake Air Temp", "C", "TEMP_MINUS_40")),
    ("0142", PidDefinition("0142", "01", "42", "Control Module Voltage", "V", "VOLTAGE_1000")),
])

# Keys the UI layer reads (TelemetryDashboardContent / DrivingDashboardScreen /
# AiDoctorScreen / PidDetailScreen / Android Auto ObdDashboardScreen)
UI_KEYS = list(PID_DEFS.keys())


def parse_trace(path):
    """Group RX lines under the preceding TX command, as ElmTransport.sendCommand would."""
    transactions = []
    current = None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.rstrip("\n")
            if not raw.strip():
                continue
            parts = raw.split(" ", 3)
            if len(parts) < 4:
                continue
            ts, direction, _marker, payload = parts[0], parts[1], parts[2], parts[3]
            if direction == "TX":
                current = {"ts": ts, "cmd": payload.strip(), "rx": []}
                transactions.append(current)
            else:
                if current is None:
                    current = {"ts": ts, "cmd": "?", "rx": []}
                    transactions.append(current)
                current["rx"].append(payload.strip())
    return transactions


def replay(transactions, publish_composite_only):
    """Mirror ObdScheduler.executePidQuery() + updateTelemetry()."""
    caps = PidCapabilityManager()
    telemetry = {}          # key -> (numeric, display)
    current_header = ""
    stats = {"tx": 0, "rx_msgs": 0, "decoded": 0, "malformed": 0, "timeouts": 0,
             "atsh": 0, "atsh_skipped_cache_hit": 0}

    for txn in transactions:
        cmd = txn["cmd"].upper().strip()
        rx_lines = [l for l in txn["rx"] if not l.startswith("[")]

        if cmd.startswith("ATSH"):
            header = cmd.split()[-1]
            if header != current_header:
                stats["atsh"] += 1
                current_header = header
            else:
                stats["atsh_skipped_cache_hit"] += 1
            continue

        if cmd == "0902":
            msgs = reassemble_lines(txn["rx"])
            for m in msgs:
                if m.is_malformed or not m.is_complete:
                    stats["malformed"] += 1
                    continue
                chars = "".join(chr(b) for b in m.reconstructed_bytes[3:] if 32 <= b < 127)
                print(f"  [VIN] from {m.can_id}: {chars!r} "
                      f"(bytes={len(m.reconstructed_bytes)}, len_field={m.total_expected_length})")
            continue

        if not any(cmd == pid for pid in PID_DEFS):
            continue

        stats["tx"] += 1
        if not rx_lines:
            stats["timeouts"] += 1
            continue

        pid_def = PID_DEFS[cmd]
        msgs = reassemble_lines(rx_lines)
        stats["rx_msgs"] += len(msgs)

        preferred = caps.preferred_ecu(pid_def.id)
        msgs_sorted = sorted(msgs, key=lambda m: 0 if (preferred and (m.can_id or "").upper() == preferred)
                             else (1 if (m.can_id or "").upper() == "7E8" else 2))

        for msg in msgs_sorted:
            if msg.is_malformed:
                stats["malformed"] += 1
                continue
            result = decode(pid_def, msg.reconstructed_bytes)
            rx_can_id = msg.can_id
            if rx_can_id:
                caps.mark_pid_status(pid_def.id, DIRECT_VALIDATED, ecu_id=rx_can_id)
            display = f"{result.display_value} {result.unit}".strip()

            # --- ObdScheduler.updateTelemetry() key publication ---
            ecu_aware_key = f"{rx_can_id or 'DEFAULT'}_{pid_def.id}"
            telemetry[ecu_aware_key] = (result.numeric_value, display)
            if not publish_composite_only:
                telemetry[pid_def.id] = (result.numeric_value, display)
            stats["decoded"] += 1

    # Cross-signal synthesis lookups (ObdScheduler.onTelemetrySignalUpdated)
    synth = {
        "speedKmh": telemetry.get("010D"),
        "engineRpm": telemetry.get("010C"),
        "throttlePct": telemetry.get("0111"),
    }
    return telemetry, caps, stats, synth


def report(label, transactions, publish_composite_only):
    print(f"\n=== {label} ===")
    telemetry, caps, stats, synth = replay(transactions, publish_composite_only)
    print("  stats:", stats)
    print("  live-eligible PIDs:",
          [p for p in PID_DEFS if caps.is_live_eligible(p)])
    print("  validating ECUs per PID:",
          {p: caps.validating_ecus(p) for p in PID_DEFS if caps.validating_ecus(p)})
    print("  preferred ECU per PID:",
          {p: caps.preferred_ecu(p) for p in PID_DEFS if caps.preferred_ecu(p)})

    print("  -- what the UI would render (plain PID key lookups) --")
    for key in UI_KEYS:
        val = telemetry.get(key)
        print(f"     {PID_DEFS[key].name:<24} liveDecodedMap[{key!r}] -> "
              f"{val[1] if val else 'Not available  <-- KEY MISSING'}")
    print("  -- powertrain cross-signal synthesis inputs --")
    for k, v in synth.items():
        print(f"     {k:<12} -> {v[0] if v else None}")
    print(f"  -- map size reported in header: '{len(telemetry)} PIDs Active' "
          f"(expected {len(UI_KEYS)})")
    print("  -- raw telemetry keys --")
    print("     ", sorted(telemetry.keys()))
    return telemetry, synth


def check_bitmap_7f_bug():
    print("\n=== PidDiscoveryDecoder.extractBitmapsByCanId : '7F' substring skip ===")
    line = "7E8 06 41 00 BF BF 7F 00"      # 0x7F is legitimate bitmap DATA here
    shipped = extract_bitmaps_by_can_id(0x00, [line], skip_any_7f=True)
    fixed = extract_bitmaps_by_can_id(0x00, [line], skip_any_7f=False)
    print(f"  shipped behaviour -> {shipped}   (empty == bitmap silently dropped)")
    print(f"  structural check  -> {fixed}")
    neg = "7E8 03 7F 01 11"
    print(f"  real negative response still rejected? "
          f"{extract_bitmaps_by_can_id(0x00, [neg], skip_any_7f=False)}")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "reference_trace.txt")
    transactions = parse_trace(path)
    print(f"parsed {len(transactions)} transactions from {path}")

    report("CURRENT CODE (composite ECU_PID keys only)", transactions, publish_composite_only=True)
    report("AFTER FIX (also publish preferred-ECU value under plain PID key)",
           transactions, publish_composite_only=False)
    check_bitmap_7f_bug()


if __name__ == "__main__":
    main()
