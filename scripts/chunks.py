"""Copy chunks between Minecraft region files (Anvil .mca): the area rollback of scripts/rollback.sh.

    python3 chunks.py <from dimension dir> <to dimension dir> <x1> <z1> <x2> <z2>

Block coordinates; every chunk touching the rectangle is copied from the old dimension folder (restored from a
backup) into the live one — terrain (region/), entities (entities/: item frames, armor stands, animals…) and
points of interest (poi/: beds, workstations). Only those chunks change; the rest of each region file stays.
The server must be stopped (or at least the chunks unloaded), or it overwrites them on its next save.
A chunk that doesn't exist in the old copy is left as it is now.
"""
import os
import shutil
import struct
import sys
import time

SECTOR = 4096


def read_header(data):
    locs = [struct.unpack(">I", data[i * 4:i * 4 + 4])[0] for i in range(1024)]
    stamps = [struct.unpack(">I", data[SECTOR + i * 4:SECTOR + i * 4 + 4])[0] for i in range(1024)]
    return locs, stamps


def chunk_bytes(data, loc):
    """The chunk's sectors (length + compression + payload, padded), or None if not stored."""
    offset, count = loc >> 8, loc & 0xFF
    if not offset or not count:
        return None
    return data[offset * SECTOR:(offset + count) * SECTOR]


def copy(src_dir, dst_dir, chunks):
    """chunks: set of (cx, cz). Returns how many were copied per file kind."""
    done = 0
    by_file = {}
    for cx, cz in chunks:
        by_file.setdefault((cx >> 5, cz >> 5), []).append((cx, cz))
    for (rx, rz), cs in sorted(by_file.items()):
        name = f"r.{rx}.{rz}.mca"
        src = os.path.join(src_dir, name)
        if not os.path.exists(src):
            continue
        sdata = open(src, "rb").read()
        if len(sdata) < 2 * SECTOR:
            continue
        slocs, sstamps = read_header(sdata)
        dst = os.path.join(dst_dir, name)
        os.makedirs(dst_dir, exist_ok=True)
        ddata = bytearray(open(dst, "rb").read()) if os.path.exists(dst) else bytearray(2 * SECTOR)
        if len(ddata) < 2 * SECTOR:
            ddata = bytearray(2 * SECTOR)
        for cx, cz in cs:
            i = (cx & 31) + (cz & 31) * 32
            body = chunk_bytes(sdata, slocs[i])
            if body is None:
                continue
            # a chunk too big for the region file lives next to it in c.<x>.<z>.mcc (compression flag 128)
            if body[4] & 0x80:
                mcc = f"c.{cx}.{cz}.mcc"
                if os.path.exists(os.path.join(src_dir, mcc)):
                    shutil.copy2(os.path.join(src_dir, mcc), os.path.join(dst_dir, mcc))
            # append at the end (sector aligned) and point the header there; the old sectors become free space
            start = (len(ddata) + SECTOR - 1) // SECTOR
            ddata.extend(b"\0" * (start * SECTOR - len(ddata)))
            ddata.extend(body)
            count = len(body) // SECTOR
            ddata[i * 4:i * 4 + 4] = struct.pack(">I", (start << 8) | count)
            ddata[SECTOR + i * 4:SECTOR + i * 4 + 4] = struct.pack(">I", sstamps[i] or int(time.time()))
            done += 1
        tmp = dst + ".tmp"
        open(tmp, "wb").write(ddata)
        os.replace(tmp, dst)
    return done


def main():
    src, dst = sys.argv[1], sys.argv[2]
    x1, z1, x2, z2 = (int(v) for v in sys.argv[3:7])
    chunks = {(cx, cz) for cx in range(min(x1, x2) >> 4, (max(x1, x2) >> 4) + 1)
              for cz in range(min(z1, z2) >> 4, (max(z1, z2) >> 4) + 1)}
    for kind in ("region", "entities", "poi"):
        n = copy(os.path.join(src, kind), os.path.join(dst, kind), chunks)
        print(f"{kind}: {n} of {len(chunks)} chunks restored")


if __name__ == "__main__":
    main()
