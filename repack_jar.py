#!/usr/bin/env python3
"""Rebuild services.jar with a patched dex, preserving entry order, storage mode and dex alignment."""
import os
import struct
import sys
import zipfile

ALIGN = 4
PAD_HEADER_ID = 0xD935  # same extra-field id zipalign uses for padding


def repack(src, dst, replacements):
    zin = zipfile.ZipFile(src)
    with zipfile.ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            data = replacements.get(info.filename)
            if data is None:
                data = zin.read(info.filename)
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.internal_attr = info.internal_attr
            out.create_system = info.create_system
            if out.compress_type == zipfile.ZIP_STORED:
                offset = zout.fp.tell()
                header = 30 + len(out.filename.encode("utf-8"))
                pad = -(offset + header) % ALIGN
                if pad:
                    out.extra = struct.pack("<HH", PAD_HEADER_ID, pad) + b"\0" * pad
            zout.writestr(out, data)
    zin.close()


def main():
    src, dst = sys.argv[1], sys.argv[2]
    replacements = {}
    for pair in sys.argv[3:]:
        name, path = pair.split("=", 1)
        replacements[name] = open(path, "rb").read()
    repack(src, dst, replacements)

    zin = zipfile.ZipFile(dst)
    bad = zin.testzip()
    if bad:
        print("corrupt entry: " + bad)
        sys.exit(1)
    for info in zin.infolist():
        if info.filename.endswith(".dex"):
            off = info.header_offset + 30 + len(info.filename) + len(info.extra)
            print("%-14s stored=%s data_offset=%d aligned=%s size=%d" % (
                info.filename, info.compress_type == zipfile.ZIP_STORED, off,
                off % ALIGN == 0, info.file_size))
    print("wrote %s (%d bytes)" % (dst, os.path.getsize(dst)))


if __name__ == "__main__":
    main()
