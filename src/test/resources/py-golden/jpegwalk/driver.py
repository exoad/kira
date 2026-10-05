#!/usr/bin/env python3
# The jpegwalk golden: the module's main, whose output is the C++ backend's run of it, then
# jpegSize, jpegEnd and splitJpegs against the hand-written jpeg_size, jpeg_end and split_jpegs
# they port (bibo's firmware/pilot/tools/dash/streamenc.py at master 36c95bb, copied below
# unchanged as the oracle), over seeded JPEG-shaped frames (random segments, fill bytes,
# restarts, a table holding FF D9, cuts and flipped bytes) and streams of them with noise.
# Each must give the same size, end, JPEGs and rest.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dash", "jpegwalk.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("jpegwalk_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: streamenc.py as written by hand -----------------------------------------------
def jpeg_size(frame):
    # (w, h) of a JPEG that has a SOF, an SOS after it and ends in EOI, else None.
    if frame[:2] != b"\xff\xd8" or frame[-2:] != b"\xff\xd9":
        return None
    i, n, size = 2, len(frame), None
    while i + 4 <= n:
        if frame[i] != 0xFF:
            return None
        marker = frame[i + 1]
        if marker == 0xFF:
            i += 1
            continue
        if 0xD0 <= marker <= 0xD9 or marker == 0x01:
            return None                         # an EOI or a restart before any scan
        seg = (frame[i + 2] << 8) | frame[i + 3]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC) and i + 9 <= n:
            size = ((frame[i + 7] << 8) | frame[i + 8], (frame[i + 5] << 8) | frame[i + 6])
        elif marker == 0xDA:
            return size if size and size[0] and size[1] else None
        i += 2 + seg
    return None


def jpeg_end(buf, soi):
    # The index just past the EOI of the JPEG starting at soi, or -1 while it is incomplete.
    # The header is walked by segment lengths, so a table byte pair FF D9 is never taken for
    # the end; in the entropy-coded data after SOS an FF is always stuffed or a marker.
    i, n = soi + 2, len(buf)
    while i + 4 <= n:
        if buf[i] != 0xFF:
            j = buf.find(b"\xff\xd9", i)
            return -1 if j < 0 else j + 2
        marker = buf[i + 1]
        if marker == 0xFF:
            i += 1
        elif marker == 0xD9:
            return i + 2
        elif 0xD0 <= marker <= 0xD7 or marker == 0x01:
            i += 2
        elif marker == 0xDA:
            j = buf.find(b"\xff\xd9", i + 2 + ((buf[i + 2] << 8) | buf[i + 3]))
            return -1 if j < 0 else j + 2
        else:
            i += 2 + ((buf[i + 2] << 8) | buf[i + 3])
    return -1


def split_jpegs(buf):
    # (complete JPEGs, the rest) from a byte stream.
    out, pos = [], 0
    while True:
        soi = buf.find(b"\xff\xd8", pos)
        if soi < 0:
            return out, b""
        end = jpeg_end(buf, soi)
        if end < 0:
            return out, buf[soi:]
        out.append(buf[soi:end])
        pos = end


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)


def segment(marker, body):
    return bytes([0xFF, marker]) + (len(body) + 2).to_bytes(2, "big") + body


def frame():
    w, h = rng.choice([0, 1, 160, 320, 640, 1280, 65535]), rng.choice([0, 1, 120, 240, 480, 720, 65535])
    out = b"\xff\xd8"
    for _ in range(rng.randint(0, 4)):
        marker = rng.choice([0xE0, 0xE1, 0xDB, 0xC4, 0xFE, 0xDD, 0xC8, 0xCC])
        body = bytes(rng.getrandbits(8) for _ in range(rng.randint(0, 40)))
        if marker == 0xDB and rng.random() < 0.5:
            body += b"\xff\xd9"
        out += segment(marker, body)
        if rng.random() < 0.2:
            out += b"\xff" * rng.randint(1, 3)
    if rng.random() < 0.9:
        sof = rng.choice([0xC0, 0xC1, 0xC2, 0xC4, 0xC8, 0xCF])
        out += segment(sof, bytes([8]) + h.to_bytes(2, "big") + w.to_bytes(2, "big") + bytes([3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]))
    if rng.random() < 0.1:
        out += rng.choice([b"\xff\xd0", b"\xff\x01", b"\xff\xd9"])
    out += segment(0xDA, bytes([3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0]))
    for _ in range(rng.randint(0, 30)):
        b = rng.getrandbits(8)
        out += b"\xff\x00" if b == 0xFF else b"\xff" + bytes([0xD0 + rng.randint(0, 7)]) if b < 8 else bytes([b])
    out += b"\xff\xd9"
    if rng.random() < 0.15:
        out = out[:rng.randint(0, len(out))]
    if rng.random() < 0.15 and out:
        k = rng.randrange(len(out))
        out = out[:k] + bytes([rng.getrandbits(8)]) + out[k + 1:]
    return out


def pair(size):
    return None if size is None else (size.w, size.h)


sized = 0
ends = 0
for _ in range(3000):
    f = frame()
    assert pair(m.jpegSize(f)) == jpeg_size(f), f.hex()
    sized += jpeg_size(f) is not None
    if len(f) >= 2:
        end = m.jpegEnd(f, 0)
        assert (-1 if end is None else end) == jpeg_end(f, 0), f.hex()
        ends += end is not None
streams = 0
jpegs = 0
for _ in range(400):
    parts = []
    for _ in range(rng.randint(0, 5)):
        if rng.random() < 0.3:
            parts.append(bytes(rng.getrandbits(8) for _ in range(rng.randint(0, 6))))
        parts.append(frame())
    buf = b"".join(parts)
    split = m.splitJpegs(buf)
    want, rest = split_jpegs(buf)
    assert [bytes(j.data) for j in split.jpegs] == want, buf.hex()
    assert bytes(split.rest) == rest, buf.hex()
    streams += 1
    jpegs += len(want)
print("jpegSize and jpegEnd against jpeg_size and jpeg_end: 3000 frames, %d sized and %d ended, every answer the same" % (sized, ends))
print("splitJpegs against split_jpegs: %d streams, %d JPEGs and every rest the same" % (streams, jpegs))
