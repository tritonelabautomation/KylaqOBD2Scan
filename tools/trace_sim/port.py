"""
Faithful Python port of the Kotlin OBD parsing chain used by KylaqOBD2Scan.

Purpose: the sandbox has no JVM/Android SDK, so this port is used to *replay*
real ELM327 traces (RX/TX logs captured from a Skoda Kylaq) through the exact
same algorithms implemented in:

  - com.example.protocol.CanFrameParser
  - com.example.protocol.IsoTpParser
  - com.example.protocol.PidDecoder            (subset of decoder types)
  - com.example.protocol.PidDiscoveryDecoder   (bitmap extraction)
  - com.example.discovery.PidCapabilityManager (live-eligibility / preferred ECU)
  - com.example.scheduler.ObdScheduler         (telemetry key publication)

It is a verification harness only; it is not part of the Android build.
Every function documents the Kotlin source line it mirrors.
"""

import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------- CanFrameParser

SF, FF, CF, FC, NON = "SINGLE_FRAME", "FIRST_FRAME", "CONSECUTIVE_FRAME", "FLOW_CONTROL", "NON_ISO_TP"

_RE_INDEX_PREFIX = re.compile(r"^[0-9A-F]{1,3}:.*")
_RE_TOKEN_BYTE = re.compile(r"^[0-9A-F]{1,2}$")
_RE_CANID_3_8 = re.compile(r"^[0-9A-F]{3,8}$")
_RE_ALL_HEX = re.compile(r"^[0-9A-F]+$")


@dataclass
class RawCanFrame:
    raw_line: str
    can_id: Optional[str]
    data_bytes: List[int]
    data_hex: str
    is_iso_tp: bool
    pci_type: str
    payload_bytes: List[int]


def _chunked(s: str, n: int) -> List[str]:
    return [s[i:i + n] for i in range(0, len(s), n)]


def parse_frame(line: str) -> RawCanFrame:
    """Mirrors CanFrameParser.parseFrame (Kotlin)."""
    trimmed = line.strip().upper()

    if _RE_INDEX_PREFIX.match(trimmed):
        trimmed = trimmed.split(":", 1)[1].strip()

    tokens = [t for t in re.split(r"\s+", trimmed) if t]

    empty = RawCanFrame(line, None, [], "", False, NON, [])
    if not tokens:
        return empty

    can_id = None
    byte_tokens: List[str] = []

    if len(tokens) >= 2:
        first_token = tokens[0]
        rest_tokens = tokens[1:]
        rest_are_hex_bytes = all(_RE_TOKEN_BYTE.match(t) for t in rest_tokens)

        if _RE_CANID_3_8.match(first_token) and rest_are_hex_bytes:
            can_id = first_token
            byte_tokens += [("0" + t) if len(t) == 1 else t for t in rest_tokens]
        elif all(_RE_TOKEN_BYTE.match(t) for t in tokens):
            can_id = None
            byte_tokens += [("0" + t) if len(t) == 1 else t for t in tokens]

    if not byte_tokens:
        clean_hex = trimmed.replace(" ", "")
        if _RE_ALL_HEX.match(clean_hex) and clean_hex:
            if (len(clean_hex) >= 10
                    and any(clean_hex.startswith(p) for p in ("18DA", "18DB", "18EA", "18EC"))
                    and (len(clean_hex) - 8) % 2 == 0):
                can_id = clean_hex[:8]
                byte_tokens += _chunked(clean_hex[8:], 2)
            elif (len(clean_hex) >= 5 and (len(clean_hex) - 3) % 2 == 0
                  and (clean_hex.startswith("7E") or clean_hex.startswith("7D")
                       or clean_hex.startswith("7F") or clean_hex.startswith("18")
                       or re.match(r"^[0-9A-F]{3}$", clean_hex[:3]))):
                can_id = clean_hex[:3]
                byte_tokens += _chunked(clean_hex[3:], 2)
            elif len(clean_hex) >= 6 and clean_hex.startswith("07E") and (len(clean_hex) - 4) % 2 == 0:
                can_id = clean_hex[:4]
                byte_tokens += _chunked(clean_hex[4:], 2)
            elif len(clean_hex) % 2 == 0:
                can_id = None
                byte_tokens += _chunked(clean_hex, 2)

    if not byte_tokens:
        return empty

    data_bytes = []
    for t in byte_tokens:
        try:
            data_bytes.append(int(t, 16))
        except ValueError:
            pass
    data_hex = "".join("%02X" % b for b in data_bytes)

    if not data_bytes:
        return RawCanFrame(line, can_id, [], "", False, NON, [])

    first_byte = data_bytes[0]
    pci_nibble = (first_byte >> 4) & 0x0F

    if pci_nibble == 0:
        length = first_byte & 0x0F
        if 1 <= length <= len(data_bytes) - 1:
            payload = data_bytes[1:1 + length]
        elif len(data_bytes) > 1:
            payload = data_bytes[1:]
        else:
            payload = data_bytes
        return RawCanFrame(line, can_id, data_bytes, data_hex, True, SF, payload)
    if pci_nibble == 1:
        return RawCanFrame(line, can_id, data_bytes, data_hex, True, FF,
                           data_bytes[2:] if len(data_bytes) > 2 else [])
    if pci_nibble == 2:
        return RawCanFrame(line, can_id, data_bytes, data_hex, True, CF,
                           data_bytes[1:] if len(data_bytes) > 1 else [])
    if pci_nibble == 3:
        return RawCanFrame(line, can_id, data_bytes, data_hex, True, FC, [])
    return RawCanFrame(line, can_id, data_bytes, data_hex, False, NON, data_bytes)


# ------------------------------------------------------------------ IsoTpParser

@dataclass
class IsoTpMessage:
    can_id: Optional[str]
    individual_frames: List[RawCanFrame]
    reconstructed_payload_hex: str
    reconstructed_bytes: List[int]
    is_complete: bool
    total_expected_length: int
    is_malformed: bool = False
    malformed_reason: Optional[str] = None


def _create_iso_tp_message(can_id, frames, payload, expected_length,
                           is_malformed=False, malformed_reason=None) -> IsoTpMessage:
    trimmed = payload[:expected_length] if 1 <= expected_length <= len(payload) else list(payload)
    is_done = (len(trimmed) == expected_length and expected_length > 0)
    return IsoTpMessage(
        can_id=can_id,
        individual_frames=list(frames),
        reconstructed_payload_hex="".join("%02X" % b for b in trimmed),
        reconstructed_bytes=trimmed,
        is_complete=is_done and not is_malformed,
        total_expected_length=expected_length,
        is_malformed=is_malformed or (not is_done and expected_length > 0),
        malformed_reason=malformed_reason,
    )


def reassemble_lines(lines: List[str]) -> List[IsoTpMessage]:
    """Mirrors IsoTpParser.reassembleLines (Kotlin)."""
    frames = [parse_frame(l) for l in lines]
    frames = [f for f in frames if f.data_bytes]
    if not frames:
        return []

    grouped: Dict[str, List[RawCanFrame]] = {}
    for f in frames:
        grouped.setdefault(f.can_id or "NO_HEADER", []).append(f)

    results: List[IsoTpMessage] = []

    for can_id, can_frames in grouped.items():
        resolved_can_id = None if can_id == "NO_HEADER" else can_id

        current_frames: List[RawCanFrame] = []
        expected_total_length = 0
        payload_acc: List[int] = []
        is_multi_frame = False
        expected_sn = 1
        is_current_malformed = False
        current_reason: Optional[str] = None

        for frame in can_frames:
            if frame.pci_type == SF:
                if current_frames:
                    results.append(_create_iso_tp_message(resolved_can_id, current_frames, payload_acc,
                                                          expected_total_length, is_current_malformed,
                                                          current_reason))
                    current_frames = []
                    payload_acc = []
                    is_multi_frame = False
                    is_current_malformed = False
                    current_reason = None

                sf_length = frame.data_bytes[0] & 0x0F
                available = len(frame.data_bytes) - 1
                sf_malformed = sf_length <= 0 or sf_length > available
                sf_bytes = frame.payload_bytes if sf_malformed else frame.data_bytes[1:1 + sf_length]

                results.append(IsoTpMessage(
                    can_id=resolved_can_id,
                    individual_frames=[frame],
                    reconstructed_payload_hex="".join("%02X" % b for b in sf_bytes),
                    reconstructed_bytes=sf_bytes,
                    is_complete=not sf_malformed,
                    total_expected_length=sf_length,
                    is_malformed=sf_malformed,
                    malformed_reason=(f"Invalid single frame length: {sf_length} (available: {available})"
                                      if sf_malformed else None),
                ))

            elif frame.pci_type == FF:
                if current_frames:
                    results.append(_create_iso_tp_message(resolved_can_id, current_frames, payload_acc,
                                                          expected_total_length, is_current_malformed,
                                                          current_reason))
                    current_frames = []
                    payload_acc = []

                is_multi_frame = True
                is_current_malformed = False
                current_reason = None
                current_frames.append(frame)

                expected_total_length = (((frame.data_bytes[0] & 0x0F) << 8) | (frame.data_bytes[1] & 0xFF)) \
                    if len(frame.data_bytes) >= 2 else 0

                if expected_total_length > 4095:
                    is_current_malformed = True
                    current_reason = (f"First Frame length field ({expected_total_length}) exceeds "
                                      f"ISO 15765-2 maximum of 4095 - rejecting as malformed")
                    expected_total_length = 0
                    payload_acc = []
                    current_frames.append(frame)
                    continue

                expected_sn = 1
                payload_acc += frame.payload_bytes

                if expected_total_length < 8:
                    is_current_malformed = True
                    current_reason = (f"First Frame specifies invalid ISO-TP length < 8 "
                                      f"({expected_total_length})")

            elif frame.pci_type == CF:
                if is_multi_frame:
                    current_frames.append(frame)
                    actual_sn = frame.data_bytes[0] & 0x0F
                    if actual_sn != expected_sn:
                        is_current_malformed = True
                        current_reason = (f"ISO-TP sequence number mismatch: expected {expected_sn}, "
                                          f"got {actual_sn}")
                    expected_sn = (expected_sn + 1) % 16

                    payload_acc += frame.payload_bytes

                    if len(payload_acc) >= expected_total_length and expected_total_length > 0:
                        trimmed = payload_acc[:expected_total_length]
                        results.append(IsoTpMessage(
                            can_id=resolved_can_id,
                            individual_frames=list(current_frames),
                            reconstructed_payload_hex="".join("%02X" % b for b in trimmed),
                            reconstructed_bytes=trimmed,
                            is_complete=not is_current_malformed,
                            total_expected_length=expected_total_length,
                            is_malformed=is_current_malformed,
                            malformed_reason=current_reason,
                        ))
                        current_frames = []
                        payload_acc = []
                        is_multi_frame = False
                        is_current_malformed = False
                        current_reason = None
                else:
                    results.append(IsoTpMessage(
                        can_id=resolved_can_id,
                        individual_frames=[frame],
                        reconstructed_payload_hex="".join("%02X" % b for b in frame.payload_bytes),
                        reconstructed_bytes=frame.payload_bytes,
                        is_complete=False,
                        total_expected_length=len(frame.payload_bytes),
                        is_malformed=True,
                        malformed_reason="Orphan consecutive frame received without preceding First Frame",
                    ))

            elif frame.pci_type == FC:
                current_frames.append(frame)

            else:  # NON_ISO_TP
                b = frame.payload_bytes
                results.append(IsoTpMessage(
                    can_id=resolved_can_id,
                    individual_frames=[frame],
                    reconstructed_payload_hex="".join("%02X" % x for x in b),
                    reconstructed_bytes=b,
                    is_complete=True,
                    total_expected_length=len(b),
                    is_malformed=False,
                    malformed_reason=None,
                ))

        if current_frames:
            trimmed = payload_acc[:expected_total_length] if 1 <= expected_total_length <= len(payload_acc) \
                else list(payload_acc)
            is_done = (len(trimmed) == expected_total_length and expected_total_length > 0)
            results.append(IsoTpMessage(
                can_id=resolved_can_id,
                individual_frames=current_frames,
                reconstructed_payload_hex="".join("%02X" % b for b in trimmed),
                reconstructed_bytes=trimmed,
                is_complete=is_done and not is_current_malformed,
                total_expected_length=expected_total_length,
                is_malformed=is_current_malformed or (not is_done and expected_total_length > 0),
                malformed_reason=current_reason or (
                    None if is_done else
                    f"Incomplete multi-frame message: received {len(trimmed)}/{expected_total_length} bytes"),
            ))

    return results


# -------------------------------------------------------------------- PidDecoder

@dataclass
class PidDefinition:
    id: str
    service: str
    pid: str
    name: str
    unit: str
    decoder_type: str
    is_research: bool = False


@dataclass
class DecodedResult:
    parameter_name: str
    numeric_value: Optional[float]
    display_value: str
    unit: str
    raw_payload_hex: str
    data_bytes: List[int]
    is_known: bool


def decode(pid_def: PidDefinition, payload_bytes: List[int]) -> DecodedResult:
    """Mirrors PidDecoder.decode (Kotlin) for the decoder types used by live polling."""
    raw_hex = "".join("%02X" % b for b in payload_bytes)
    if not payload_bytes:
        return DecodedResult(pid_def.name, None, "NO DATA", pid_def.unit, raw_hex, [], False)

    expected_service_ack = int(pid_def.service, 16) + 0x40
    expected_pid = int(pid_def.pid, 16)
    is_research = pid_def.is_research or pid_def.decoder_type == "RESEARCH_RAW"

    if len(payload_bytes) >= 2 and payload_bytes[0] == expected_service_ack and payload_bytes[1] == expected_pid:
        data_bytes = payload_bytes[2:]
    elif is_research and len(payload_bytes) >= 1 and payload_bytes[0] == expected_service_ack:
        data_bytes = payload_bytes[1:]
    elif is_research:
        data_bytes = payload_bytes
    else:
        return DecodedResult(pid_def.name, None, "INVALID_RESPONSE", pid_def.unit, raw_hex, [], False)

    a = data_bytes[0] if len(data_bytes) > 0 else 0
    b = data_bytes[1] if len(data_bytes) > 1 else 0

    if is_research:
        fmt = " ".join("%02X" % x for x in data_bytes)
        return DecodedResult(pid_def.name, None, fmt or raw_hex, "RAW", raw_hex, data_bytes, False)

    if not data_bytes:
        return DecodedResult(pid_def.name, None, "UNKNOWN", pid_def.unit, raw_hex, [], False)

    t = pid_def.decoder_type
    if t == "PERCENT_255":
        v = a * 100.0 / 255.0
        return DecodedResult(pid_def.name, v, "%.1f" % v, "%", raw_hex, data_bytes, True)
    if t == "TEMP_MINUS_40":
        v = float(a - 40)
        return DecodedResult(pid_def.name, v, "%.0f" % v, "C", raw_hex, data_bytes, True)
    if t == "RAW_A_KPA":
        v = float(a)
        return DecodedResult(pid_def.name, v, "%.0f" % v, "kPa", raw_hex, data_bytes, True)
    if t == "RPM_FORMULA":
        v = ((a * 256.0) + b) / 4.0
        return DecodedResult(pid_def.name, v, "%.0f" % v, "RPM", raw_hex, data_bytes, True)
    if t == "RAW_A_KMH":
        v = float(a)
        return DecodedResult(pid_def.name, v, "%.0f" % v, "km/h", raw_hex, data_bytes, True)
    if t == "VOLTAGE_1000":
        v = ((a * 256.0) + b) / 1000.0
        return DecodedResult(pid_def.name, v, "%.2f" % v, "V", raw_hex, data_bytes, True)
    if t == "FUEL_TRIM":
        v = (a - 128) * 100.0 / 128.0
        return DecodedResult(pid_def.name, v, "%+.1f" % v, "%", raw_hex, data_bytes, True)
    if t == "PERCENT_LOAD_255":
        v = ((a * 256.0) + b) / 2.55
        return DecodedResult(pid_def.name, v, "%.1f" % v, "%", raw_hex, data_bytes, True)
    raise AssertionError("decoder type not ported: %s" % t)


# ----------------------------------------------------------- PidDiscoveryDecoder

def extract_bitmap_from_line(base_pid: int, line: str):
    """Mirrors PidDiscoveryDecoder.extractBitmapFromLine (Kotlin)."""
    trimmed = line.strip().upper()
    if not trimmed or any(k in trimmed for k in ("NO DATA", "ERROR", "STOPPED", "SEARCHING",
                                                 "UNABLE TO CONNECT", "BUS INIT", "?")):
        return None

    frame = parse_frame(trimmed)
    can_id = frame.can_id
    byte_seq = frame.payload_bytes if frame.payload_bytes else frame.data_bytes

    neg_idx = next((i for i, v in enumerate(byte_seq) if v == 0x7F), -1)
    if neg_idx != -1 and neg_idx + 1 < len(byte_seq) and byte_seq[neg_idx + 1] == 0x01:
        return None

    expected_service = 0x41
    expected_pid = base_pid & 0xFF

    idx = next((i for i, v in enumerate(byte_seq) if v == expected_service), -1)
    if idx != -1 and idx + 5 < len(byte_seq) and byte_seq[idx + 1] == expected_pid:
        return can_id, bytes(byte_seq[idx + 2:idx + 6])

    tokens = [t for t in re.split(r"[^0-9A-F]+", trimmed) if t]
    service_token = "41"
    pid_token = "%02X" % base_pid
    token_idx = next((i for i, v in enumerate(tokens) if v == service_token), -1)
    if token_idx != -1 and token_idx + 5 < len(tokens) and tokens[token_idx + 1] == pid_token:
        try:
            vals = [int(tokens[token_idx + k], 16) for k in (2, 3, 4, 5)]
        except ValueError:
            vals = None
        if vals is not None:
            line_can_id = tokens[0] if (tokens and _RE_CANID_3_8.match(tokens[0])
                                        and tokens[0] != service_token) else can_id
            return line_can_id, bytes(vals)

    hex_only = re.sub(r"[^0-9A-F]", "", trimmed)
    pattern = service_token + pid_token
    c_idx = hex_only.find(pattern)
    if c_idx != -1 and c_idx + 4 + 8 <= len(hex_only):
        hex_bytes = hex_only[c_idx + 4:c_idx + 12]
        extracted = []
        for chunk in _chunked(hex_bytes, 2):
            try:
                extracted.append(int(chunk, 16))
            except ValueError:
                pass
        if len(extracted) == 4:
            return can_id, bytes(extracted)
    return None


def extract_bitmaps_by_can_id(base_pid: int, response_lines: List[str],
                              skip_any_7f: bool = True) -> Dict[str, bytes]:
    """Mirrors PidDiscoveryDecoder.extractBitmapsByCanId (Kotlin).

    `skip_any_7f=True` reproduces the shipped behaviour (skip every line that
    contains the substring "7F"); `False` reproduces the structural check only.
    """
    results: Dict[str, bytes] = {}
    default_index = 1
    for line in response_lines:
        trimmed = line.strip().upper()
        if skip_any_7f and "7F" in trimmed:
            continue
        extracted = extract_bitmap_from_line(base_pid, line)
        if extracted is None:
            continue
        can_id = extracted[0] or f"ECU_{default_index}"
        if extracted[0] is None:
            default_index += 1
        if can_id not in results:
            results[can_id] = extracted[1]
    return results


# ---------------------------------------------------------- PidCapabilityManager

DIRECT_VALIDATED, LIVE_ELIGIBLE, BITMAP_SUPPORTED, NOT_SUPPORTED = (
    "DIRECT_VALIDATED", "LIVE_ELIGIBLE", "BITMAP_SUPPORTED", "NOT_SUPPORTED")


class PidCapabilityManager:
    def __init__(self):
        self.capability_map: Dict[str, str] = {}
        self.ecu_capability_map: Dict[str, Dict[str, str]] = {}
        self.pid_to_ecus: Dict[str, set] = {}

    @staticmethod
    def _clean(pid_id: str) -> str:
        up = pid_id.upper()
        return up[2:] if up.startswith("01") else up

    def mark_pid_status(self, pid_id: str, status: str, ecu_id: Optional[str] = None):
        clean = self._clean(pid_id)
        up = pid_id.upper()
        if ecu_id is None:
            self.capability_map[clean] = status
            self.capability_map[up] = status
            return
        ecu = ecu_id.upper()
        ecu_map = self.ecu_capability_map.setdefault(ecu, {})
        ecu_map[clean] = status
        ecu_map[up] = status
        if status in (DIRECT_VALIDATED, LIVE_ELIGIBLE):
            self.pid_to_ecus.setdefault(clean, set()).add(ecu)
            self.pid_to_ecus.setdefault(up, set()).add(ecu)
            self.capability_map[clean] = status
            self.capability_map[up] = status
        elif status == BITMAP_SUPPORTED:
            if self.capability_map.get(clean) not in (DIRECT_VALIDATED, LIVE_ELIGIBLE):
                self.capability_map[clean] = status
                self.capability_map[up] = status

    def validating_ecus(self, pid_id: str) -> List[str]:
        clean = self._clean(pid_id)
        s = self.pid_to_ecus.get(clean) or self.pid_to_ecus.get(pid_id.upper())
        return sorted(s) if s else []

    def preferred_ecu(self, pid_id: str) -> Optional[str]:
        ecus = self.validating_ecus(pid_id)
        if not ecus:
            return None
        if "7E8" in ecus:
            return "7E8"
        if "7E1" in ecus:
            return "7E1"
        return ecus[0]

    def is_live_eligible(self, pid_id: str, ecu_id: Optional[str] = None) -> bool:
        clean = self._clean(pid_id)
        status = self.capability_map.get(clean) or self.capability_map.get(pid_id.upper())
        ecus = self.pid_to_ecus.get(clean) or self.pid_to_ecus.get(pid_id.upper())
        if ecu_id is not None:
            ecu_map = self.ecu_capability_map.get(ecu_id.upper())
            if not ecu_map:
                return False
            status = ecu_map.get(clean) or ecu_map.get(pid_id.upper())
            return status in (DIRECT_VALIDATED, LIVE_ELIGIBLE) and bool(
                ecus and any(e.upper() == ecu_id.upper() for e in ecus))
        return status in (DIRECT_VALIDATED, LIVE_ELIGIBLE) and bool(ecus)


# ---------------------------------------------------------------- KylaqProtocolProfile

FUNCTIONAL_REQUEST_ID = "7DF"
PHYSICAL_REQUEST_RANGE = ["7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7"]
TYPICAL_RESPONSE_RANGE = ["7E8", "7E9", "7EA", "7EB", "7EC", "7ED", "7EE", "7EF"]
STANDARD_ECU_MAPPING = {r: q for r, q in zip(TYPICAL_RESPONSE_RANGE, PHYSICAL_REQUEST_RANGE)}


def get_physical_request_id(rx_can_id: str) -> str:
    up = rx_can_id.upper()
    if up in STANDARD_ECU_MAPPING:
        return STANDARD_ECU_MAPPING[up]
    if up in TYPICAL_RESPONSE_RANGE:
        return PHYSICAL_REQUEST_RANGE[TYPICAL_RESPONSE_RANGE.index(up)]
    return FUNCTIONAL_REQUEST_ID
