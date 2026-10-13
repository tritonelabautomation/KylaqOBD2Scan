"""Mirror of the new Kotlin LiveTelemetryStore + a replay of the bundled Kylaq trace
fixture, used to derive the exact expected values asserted by LiveTelemetryPipelineTest.

Run:  python3 tools/trace_sim/store_semantics.py
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from port import (PidCapabilityManager, PidDefinition, decode, reassemble_lines,
                  DIRECT_VALIDATED, BITMAP_SUPPORTED)
from replay_trace import PID_DEFS

NO_ECU = "DEFAULT"
ENGINE_ECU = "7E8"
STALE_DISPLAY = "Not available (stale)"


class LiveTelemetryStore:
    """Faithful Python mirror of scheduler/LiveTelemetryStore.kt (keep in sync)."""

    def __init__(self):
        self.by_pid = {}
        self.decoded = {}
        self.numeric = {}

    # -- selection -----------------------------------------------------------
    @staticmethod
    def _rank(ecu, preferred):
        if preferred and ecu == preferred.strip().upper():
            return 0
        if ecu == ENGINE_ECU:
            return 1
        if ecu == NO_ECU:
            return 2
        return 3

    @staticmethod
    def _tier(item):
        if not item["stale"] and item["valid"]:
            return 0
        if not item["stale"]:
            return 1
        if item["valid"]:
            return 2
        return 3

    def _select_primary(self, entries, preferred):
        return min(entries, key=lambda kv: (self._tier(kv[1]), self._rank(kv[0], preferred), kv[0]))

    def primary(self, pid, preferred=None):
        entries = list(self.by_pid.get(pid, {}).items())
        if not entries:
            return None
        return self._select_primary(entries, preferred)[1]

    def _republish(self, pid, preferred):
        entries = list(self.by_pid.get(pid, {}).items())
        if not entries:
            return
        winner = self._select_primary(entries, preferred)[1]
        if winner["stale"] and winner["valid"]:
            display = STALE_DISPLAY
        elif not winner["valid"]:
            display = winner["display"]
        else:
            display = f"{winner['display']} {winner['unit']}".strip()
        self.decoded[pid] = display
        if winner["numeric"] is not None and not winner["stale"] and winner["valid"]:
            self.numeric[pid] = winner["numeric"]
        else:
            self.numeric.pop(pid, None)

    # -- writes --------------------------------------------------------------
    def publish(self, pid, ecu, display, unit, numeric, valid, preferred=None, stale=False):
        key = (ecu or NO_ECU).strip().upper() or NO_ECU
        self.by_pid.setdefault(pid, {})[key] = {
            "display": display, "unit": unit, "numeric": numeric,
            "valid": valid, "stale": stale,
        }
        self._republish(pid, preferred)

    def publish_unavailable(self, pid, ecu, preferred=None, display="Not available", unit=""):
        self.publish(pid, ecu, display, unit, None, False, preferred)

    def mark_stale(self, pid, ecu=None):
        targets = self.by_pid.get(pid, {})
        for key, item in targets.items():
            if ecu is None or key == ecu:
                item["stale"] = True
        self._republish(pid, ecu and None)

    @property
    def active_pid_count(self):
        return len(self.decoded)


TRACE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "app", "src", "test", "resources", "traces", "kylaq",
                     "f39f1ebd_raw.txt")

LINE = re.compile(r"^(\d\d:\d\d:\d\d\.\d\d\d)\s+(TX|RX)\s+([><])\s+(.*)$")


def parse_fixture(path):
    txns, cur = [], None
    for raw in open(path, encoding="utf-8"):
        raw = raw.rstrip("\n")
        m = LINE.match(raw.strip())
        if not m:
            continue  # comment / header lines in the fixture
        ts, direction, _marker, payload = m.groups()
        if direction == "TX":
            cur = {"ts": ts, "cmd": payload.strip(), "rx": []}
            txns.append(cur)
        elif cur is not None:
            cur["rx"].append(payload.strip())
    return txns


def replay():
    caps = PidCapabilityManager()
    store = LiveTelemetryStore()
    # bootstrap: 7E8 is the engine ECU and answers every PID in the trace;
    # 7E9 (transmission/body) answers 010C, 010D, 0111, 0142 and 0105.
    answers_both = {"010C", "010D", "0111", "0142", "0105"}
    for pid in PID_DEFS:
        caps.mark_pid_status(pid, DIRECT_VALIDATED, ecu_id="7E8")
        if pid in answers_both:
            caps.mark_pid_status(pid, DIRECT_VALIDATED, ecu_id="7E9")

    counts = {"pid_queries": 0, "decoded_samples": 0, "no_data": 0, "late_frames": 0}
    for txn in parse_fixture(TRACE):
        cmd = txn["cmd"].upper()
        if cmd not in PID_DEFS:
            continue
        counts["pid_queries"] += 1
        pid_def = PID_DEFS[cmd]
        rx = [l for l in txn["rx"] if l and not l.startswith("[")]
        if any(l.upper().startswith("NO DATA") for l in rx):
            counts["no_data"] += 1
            store.publish_unavailable(cmd, caps.preferred_ecu(cmd), caps.preferred_ecu(cmd))
            continue
        msgs = reassemble_lines(rx)
        preferred = caps.preferred_ecu(cmd)
        msgs_sorted = sorted(msgs, key=lambda m: 0 if (preferred and (m.can_id or "").upper() == preferred)
                             else (1 if (m.can_id or "").upper() == "7E8" else 2))
        ack = int(pid_def.service, 16) | 0x40
        req_pid = int(pid_def.pid, 16)
        for msg in msgs_sorted:
            if msg.is_malformed:
                continue
            frame = msg.reconstructed_bytes
            # late/unsolicited frame for a different PID -> logged, never published
            if len(frame) >= 2 and frame[0] == ack and frame[1] != req_pid:
                counts["late_frames"] += 1
                continue
            res = decode(pid_def, frame)
            caps.mark_pid_status(cmd, DIRECT_VALIDATED, ecu_id=msg.can_id)
            store.publish(cmd, msg.can_id, res.display_value, res.unit,
                          res.numeric_value, res.is_known, caps.preferred_ecu(cmd))
            counts["decoded_samples"] += 1
    return caps, store, counts


def main():
    caps, store, counts = replay()
    print("counts:", counts)
    print("preferred ECUs:", {p: caps.preferred_ecu(p) for p in PID_DEFS})
    print("ECU-aware keys:", sorted(f"{e}_{p}" for p, m in store.by_pid.items() for e in m))
    print("\nplain-key decoded map (what the UI reads):")
    for pid in PID_DEFS:
        print(f"   {pid} {PID_DEFS[pid].name:<24} -> {store.decoded.get(pid)!r}"
              f"  numeric={store.numeric.get(pid)!r}")
    print("\nactivePidCount =", len(store.decoded), "(distinct PIDs, was inflated by ECU keys)")

    # demonstrate the primary/secondary behaviour the tests assert
    print("\n-- multi-ECU jitter check --")
    s2 = LiveTelemetryStore()
    s2.publish("010C", "7E9", "973", "RPM", 973.0, True, "7E8")   # secondary answers first
    s2.publish("010C", "7E8", "970", "RPM", 970.0, True, "7E8")   # preferred answers second
    print("   after 7E9 then 7E8:", s2.decoded["010C"], s2.numeric["010C"])
    s3 = LiveTelemetryStore()
    s3.publish("010C", "7E8", "970", "RPM", 970.0, True, "7E8")
    s3.publish("010C", "7E9", "973", "RPM", 973.0, True, "7E8")   # secondary answers last
    print("   after 7E8 then 7E9:", s3.decoded["010C"], s3.numeric["010C"])

    print("\n-- primary goes stale -> secondary takes over --")
    s4 = LiveTelemetryStore()
    s4.publish("010C", "7E8", "970", "RPM", 970.0, True, "7E8")
    s4.publish("010C", "7E9", "973", "RPM", 973.0, True, "7E8")
    s4.mark_stale("010C", "7E8")
    s4._republish("010C", "7E8")
    print("   ", s4.decoded["010C"], s4.numeric["010C"])

    print("\n-- all samples stale -> honest 'Not available (stale)' --")
    s5 = LiveTelemetryStore()
    s5.publish("010C", "7E8", "970", "RPM", 970.0, True, "7E8")
    s5.mark_stale("010C", "7E8")
    s5._republish("010C", "7E8")
    print("   ", s5.decoded["010C"], "numeric:", s5.numeric.get("010C"))

    print("\n-- timeout on primary must not blank a healthy secondary --")
    s6 = LiveTelemetryStore()
    s6.publish("0105", "7E8", "70", "C", 70.0, True, "7E8")
    s6.publish("0105", "7E9", "70", "C", 70.0, True, "7E8")
    s6.publish_unavailable("0105", "7E8", "7E8")
    print("   ", s6.decoded["0105"], s6.numeric.get("0105"))


if __name__ == "__main__":
    main()
