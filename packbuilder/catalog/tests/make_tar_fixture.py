"""Writes the ustar fixture that :core:download's VirtualTarTest compares its stream with.

    python packbuilder/catalog/tests/make_tar_fixture.py

Python's tarfile (USTAR_FORMAT) writes three small files with the contents, mode, owner and time
the Kotlin test gives its own files; the test then expects VirtualTar's bytes to equal these up to
the end of the two zero blocks (tarfile pads the archive further to a 10,240-byte record).
"""
import io
import tarfile
from pathlib import Path

OUT = Path(__file__).resolve().parents[3] / "android/core/download/src/test/resources/io/github/spysyweeb/beamline/download/python-ustar.tar"
MTIME = 1763251200

FILES = [
    ("manifest.json", b'{"manifest_version":3,"pack_id":"us-pa","parts":{"map":{"file":"map.pmtiles"},"search":{"file":"search.sqlite"}}}\n'),
    ("map.pmtiles", bytes(i % 251 for i in range(1000))),
    ("search.sqlite", bytes((i * 7) % 256 for i in range(513))),
]


def main():
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for name, data in FILES:
            info = tarfile.TarInfo(name)
            info.size, info.mtime, info.mode, info.uid, info.gid, info.uname, info.gname = len(data), MTIME, 0o644, 0, 0, "", ""
            t.addfile(info, io.BytesIO(data))
    OUT.write_bytes(buf.getvalue())
    print(f"wrote {OUT.name}: {len(buf.getvalue())} bytes")


if __name__ == "__main__":
    main()
