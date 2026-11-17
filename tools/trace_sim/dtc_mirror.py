"""Mirror of the final DtcDecoder.extractDtcs + decodeDtcHex, run against every
fixture used by DtcDecoderAdversarialTest and DecoderRealWorldVerificationTest."""
import re

def decode_dtc_hex(h):
    if len(h) != 4: return None
    try:
        hi = int(h[0:2], 16); lo = int(h[2:4], 16)
    except ValueError:
        return None
    if hi == 0 and lo == 0: return None
    cat = (hi & 0xC0) >> 6
    letter = {0:'P',1:'C',2:'B',3:'U'}.get(cat,'P')
    second = (hi & 0x30) >> 4
    third = format(hi & 0x0F, 'X')
    fourth = format((lo & 0xF0) >> 4, 'X')
    fifth = format(lo & 0x0F, 'X')
    return f"{letter}{second}{third}{fourth}{fifth}"

def to_hex(body): return "".join("%02X" % (b & 0xFF) for b in body)

def strip_count(body):
    declared = body[0] if body else None
    if declared is None or declared <= 0 or len(body) < 3: return to_hex(body)
    if (len(body) - 1) % 2 != 0: return to_hex(body)
    payload_len = 1 + 2 * declared
    pairs_available = (len(body) - 1) // 2
    exact = declared == pairs_available
    zero_padded = (declared < pairs_available and len(body) >= payload_len
                   and all(b == 0x00 for b in body[payload_len:]))
    if exact or zero_padded:
        return to_hex(body[1:min(payload_len, len(body))])
    return to_hex(body)

def extract_dtcs(payload_hex, mode=0x03):
    clean = re.sub(r"[^0-9A-Fa-f]", "", payload_hex).upper()
    if len(clean) < 2: return []
    ack = "%02X" % ((mode + 0x40) & 0xFF)
    data_start = -1
    for prefix in (0, 2, 3, 5, 8, 10):
        if prefix + 2 > len(clean): continue
        cand = clean[prefix:]
        if cand.startswith("7F"): return []
        if cand.startswith(ack):
            data_start = prefix + 2
            break
    if data_start < 0 or data_start > len(clean): return []
    body_hex = clean[data_start:]
    body = [int(c, 16) for c in [body_hex[i:i+2] for i in range(0, len(body_hex), 2)] if len(c) == 2]
    data_hex = strip_count(body)
    out = []
    i = 0
    while i + 4 <= len(data_hex):
        d = decode_dtc_hex(data_hex[i:i+4])
        if d: out.append(d)
        i += 4
    # dedupe, keep order
    seen = []
    for d in out:
        if d not in seen: seen.append(d)
    return seen

CASES = [
    # (payload, mode, expected)
    ("430104", 0x03, ["P0104"]),
    ("4301048030901231B345", 0x03, ["P0104","B0030","B1012","P31B3"]),
    ("470201", 0x07, ["P0201"]),
    ("7F0311", 0x03, []),
    ("7F0722", 0x07, []),
    ("7E87F0311", 0x03, []),
    ("7E8430104", 0x03, ["P0104"]),
    ("7E9470201", 0x07, ["P0201"]),
    ("7E9430201", 0x07, []),
    ("470104", 0x03, []),
    ("430201", 0x07, []),
    ("4301040000", 0x03, ["P0104"]),
    ("430000", 0x03, []),
    ("43", 0x03, []),
    ("", 0x03, []),
    ("DEADBEEF", 0x03, []),
    ("43 01 04", 0x03, ["P0104"]),
    ("43p0104", 0x03, ["P0104"]),
    ("4301040104", 0x03, ["P0104"]),
    ("437FF0", 0x03, ["C3FF0"]),
    ("43C010C011", 0x03, ["U0010","U0011"]),
    # DecoderRealWorldVerificationTest
    ("430101", 0x03, ["P0101"]),
    ("4300", 0x03, []),
    ("7F0312", 0x03, []),
    ("410101", 0x03, []),
    # NEW: real SAE J1979 wire format (count byte)
    ("430201040108", 0x03, ["P0104","P0108"]),          # ISO-TP payload, 2 DTCs
    ("7E806430201040108", 0x03, ["P0104","P0108"]),     # raw ELM line w/ CAN id + PCI
    ("7E8 06 43 02 01 04 01 08", 0x03, ["P0104","P0108"]),
    ("430101 04", 0x03, ["P0104"]),                     # count=1 + one DTC
    ("4302010401080000", 0x03, ["P0104","P0108"]),      # count + zero frame padding
    ("4301C010", 0x03, ["U0010"]),                      # count=1, U-code
    ("470301 0401 0801 0C", 0x07, None),                # count=3 pending
]


def main():
    fails = 0
    for payload, mode, expected in CASES:
        got = extract_dtcs(payload, mode)
        if expected is None:
            print(f"  info  {payload!r} mode={mode:#04x} -> {got}")
            continue
        ok = got == expected
        if not ok: fails += 1
        print(f"  {'PASS' if ok else 'FAIL'}  {payload!r} mode={mode:#04x} -> {got} (expected {expected})")
    print(f"\n{len(CASES)-1} cases, {fails} failures")


if __name__ == "__main__":
    main()
