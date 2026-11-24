"""Sizes of native libraries in APKs, and whether they are 16 KB page aligned. Standard library only.

For every lib/<abi>/*.so in each APK: size in the APK and uncompressed, whether it is stored
uncompressed, whether its data starts on a 16 KB boundary inside the zip (needed to load it
straight from the APK), and the alignment of every PT_LOAD segment of the ELF file (16 KB pages
need p_align >= 0x4000). With --gzip it also prints gzip -9 of each library as a rough estimate of
its download size (an estimate: Play's delivery compression is not gzip).

    python android/routing/scripts/apk_sizes.py android/routing/demo/build/outputs/apk/release/*.apk [--gzip] [--json out.json]
"""
from __future__ import annotations

import argparse
import gzip
import json
import struct
import sys
import zipfile
from pathlib import Path


def elf_load_aligns(data: bytes) -> list[int]:
    if data[:4] != b"\x7fELF":
        return []
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        e_phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        e_phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type != 1:  # PT_LOAD
            continue
        p_align = struct.unpack_from(endian + "Q", data, off + 0x30)[0] if is64 else struct.unpack_from(endian + "I", data, off + 0x1C)[0]
        out.append(p_align)
    return out


def data_offset(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Offset of the entry's data in the zip (local header + name + extra)."""
    zf.fp.seek(info.header_offset)
    h = zf.fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", h, 26)
    return info.header_offset + 30 + name_len + extra_len


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apks", nargs="+", type=Path)
    ap.add_argument("--gzip", action="store_true")
    ap.add_argument("--json", type=Path)
    a = ap.parse_args()
    rows = []
    for apk in a.apks:
        with zipfile.ZipFile(apk) as zf:
            print(f"{apk.name}: {apk.stat().st_size:,} bytes")
            for info in zf.infolist():
                if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                    continue
                data = zf.read(info)
                aligns = elf_load_aligns(data)
                off = data_offset(zf, info)
                row = {
                    "apk": apk.name, "lib": info.filename, "bytes": info.file_size, "in_apk": info.compress_size,
                    "stored": info.compress_type == zipfile.ZIP_STORED, "zip_offset_16k": off % 16384 == 0,
                    "load_align": [hex(x) for x in aligns], "elf_16k": bool(aligns) and min(aligns) >= 0x4000,
                }
                if a.gzip:
                    row["gzip9_estimate"] = len(gzip.compress(data, 9))
                rows.append(row)
                print(f"  {info.filename:45} {info.file_size:>11,} B  in apk {info.compress_size:>11,} B  "
                      f"{'stored' if row['stored'] else 'deflated'}  zip16k={row['zip_offset_16k']}  "
                      f"LOAD align {','.join(row['load_align'])}"
                      + (f"  gzip-9 {row['gzip9_estimate']:,} B (estimate)" if a.gzip else ""))
    if a.json:
        a.json.parent.mkdir(parents=True, exist_ok=True)
        a.json.write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
