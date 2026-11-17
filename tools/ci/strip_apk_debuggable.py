#!/usr/bin/env python3
"""Set android:debuggable=false (and testOnly=false) in a packaged APK.

AGP still writes application-debuggable from the debug variant even after the
merged text manifests are edited. ColorOS Files refuses GUI install of
debuggable APKs. This patches the binary AXML inside the APK; the caller must
zipalign + apksigner afterwards.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_ELEMENT_TYPE = 0x0102
TYPE_INT_BOOLEAN = 0x12
ATTR_DEBUGGABLE = 0x0101000F
ATTR_TEST_ONLY = 0x0101045C  # android.R.attr.testOnly


def u16(buf: bytes | bytearray, off: int) -> int:
    return struct.unpack_from("<H", buf, off)[0]


def u32(buf: bytes | bytearray, off: int) -> int:
    return struct.unpack_from("<I", buf, off)[0]


def parse_string_pool(buf: bytes, pool_off: int) -> list[str]:
    """Return the strings in a ResStringPool chunk at pool_off."""
    hsize = u16(buf, pool_off + 2)
    string_count = u32(buf, pool_off + 8)
    flags = u32(buf, pool_off + 16)
    strings_start = u32(buf, pool_off + 20)
    utf8 = bool(flags & 0x100)
    strings: list[str] = []
    for i in range(string_count):
        off = pool_off + hsize + i * 4
        str_off = pool_off + strings_start + u32(buf, off)
        if utf8:
            # UTF-8: u8/u16 charLen, u8/u16 byteLen, bytes, NUL
            # Skip char length (1 or 2 bytes) then byte length, then data.
            def read_len(p: int) -> tuple[int, int]:
                first = buf[p]
                if first & 0x80:
                    return ((first & 0x7F) << 8) | buf[p + 1], p + 2
                return first, p + 1

            _, p = read_len(str_off)
            byte_len, p = read_len(p)
            strings.append(buf[p : p + byte_len].decode("utf-8", errors="replace"))
        else:
            char_len = u16(buf, str_off)
            if char_len & 0x8000:
                char_len = ((char_len & 0x7FFF) << 16) | u16(buf, str_off + 2)
                data_off = str_off + 4
            else:
                data_off = str_off + 2
            raw = buf[data_off : data_off + char_len * 2]
            strings.append(raw.decode("utf-16le", errors="replace"))
    return strings


def patch_axml(data: bytes) -> tuple[bytes, int]:
    if len(data) < 8 or u16(data, 0) != RES_XML_TYPE:
        raise SystemExit(f"not binary AXML (type={u16(data, 0) if len(data) >= 2 else -1:#x})")

    buf = bytearray(data)
    xml_header_size = u16(buf, 2)
    name_by_rid = {ATTR_DEBUGGABLE: None, ATTR_TEST_ONLY: None}  # type: dict[int, int | None]
    strings: list[str] = []

    pos = xml_header_size
    while pos + 8 <= len(buf):
        ctype = u16(buf, pos)
        hsize = u16(buf, pos + 2)
        csize = u32(buf, pos + 4)
        if csize < 8 or pos + csize > len(buf):
            break
        if ctype == RES_STRING_POOL_TYPE and not strings:
            strings = parse_string_pool(bytes(buf), pos)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE:
            n = (csize - hsize) // 4
            for i in range(n):
                rid = u32(buf, pos + hsize + i * 4)
                if rid in name_by_rid:
                    name_by_rid[rid] = i
        pos += csize

    wanted: set[int] = {idx for idx in name_by_rid.values() if idx is not None}
    for i, s in enumerate(strings):
        if s in ("debuggable", "testOnly"):
            wanted.add(i)

    if not wanted:
        raise SystemExit("AXML has no debuggable/testOnly resource-map entry or string")

    patched = 0

    def patch_attrs(attrs_off: int, attr_size: int, attr_count: int) -> None:
        nonlocal patched
        if attr_size < 20:
            return
        for i in range(attr_count):
            aoff = attrs_off + i * attr_size
            if aoff + 20 > len(buf):
                return
            name_idx = u32(buf, aoff + 4)
            data_type = buf[aoff + 15]
            if name_idx in wanted and data_type == TYPE_INT_BOOLEAN:
                struct.pack_into("<I", buf, aoff + 16, 0)
                patched += 1

    def walk(from_ext: bool) -> None:
        p = xml_header_size
        while p + 8 <= len(buf):
            ctype = u16(buf, p)
            hsize = u16(buf, p + 2)
            csize = u32(buf, p + 4)
            if csize < 8 or p + csize > len(buf):
                break
            if ctype == RES_XML_START_ELEMENT_TYPE and hsize >= 16 and csize >= hsize + 20:
                ext = p + hsize
                attr_start = u16(buf, ext + 8)
                attr_size = u16(buf, ext + 10)
                attr_count = u16(buf, ext + 12)
                base = ext if from_ext else p
                patch_attrs(base + attr_start, attr_size, attr_count)
            p += csize

    # AOSP: attributeStart is from ResXMLTree_attrExt. Fall back to node start.
    walk(from_ext=True)
    if patched == 0:
        walk(from_ext=False)

    if patched == 0:
        raise SystemExit("found debuggable/testOnly names but no boolean attributes to clear")
    return bytes(buf), patched


def rewrite_apk(src: Path, dst: Path) -> int:
    with zipfile.ZipFile(src, "r") as zin:
        try:
            original = zin.read("AndroidManifest.xml")
        except KeyError as exc:
            raise SystemExit("APK has no AndroidManifest.xml") from exc
        patched, count = patch_axml(original)
        with zipfile.ZipFile(dst, "w") as zout:
            for item in zin.infolist():
                if item.filename.startswith("META-INF/"):
                    continue
                payload = patched if item.filename == "AndroidManifest.xml" else zin.read(item.filename)
                info = zipfile.ZipInfo(filename=item.filename, date_time=item.date_time)
                info.compress_type = item.compress_type
                info.external_attr = item.external_attr
                zout.writestr(info, payload)
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("out")
    args = parser.parse_args()
    src = Path(args.apk)
    dst = Path(args.out)
    if not src.is_file():
        print(f"missing APK: {src}", file=sys.stderr)
        return 2
    count = rewrite_apk(src, dst)
    print(f"cleared {count} debuggable/testOnly boolean(s) -> {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
