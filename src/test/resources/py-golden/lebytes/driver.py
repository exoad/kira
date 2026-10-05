#!/usr/bin/env python3
# The lebytes golden: the module's main, whose output is the C++ backend's run of it, then
# kira:bytes' reads and writes against struct, the CRCs against binascii and zlib, and
# encodeRaw and parseFrame against the hand-written encode_raw and parse_frame they port
# (bibo's tools/dbw/dbwcodec.py at master 36c95bb, copied below unchanged but for the spec,
# which a stub of its two numbers stands for, and crc16, which binascii's CRC-CCITT computes).
# A read or write past the end must stop the program where struct raises.
#
#   python driver.py <the directory kira --target py --out wrote>
import binascii
import importlib.util
import math
import os
import random
import runpy
import struct
import sys
import zlib
from collections import namedtuple

path = os.path.join(sys.argv[1], "src", "dbw", "lebytes.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("lebytes_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: dbwcodec.py as written by hand --------------------------------------------------
HEADER_BYTES = 10
CRC_BYTES = 2
Frame = namedtuple("Frame", "version flags seq type payload")


class FrameError(ValueError):
    """A frame or payload this codec was asked to build is not valid."""


class _Spec:
    magic = b"\xfb\xb1"
    major = 1


def load():
    return _Spec()


def crc16(data: bytes, crc: int = 0xFFFF) -> int:
    return binascii.crc_hqx(data, crc)


def encode_raw(typ: int, payload: bytes = b"", seq: int = 0, flags: int = 0, version=None, spec=None) -> bytes:
    spec = spec or load()
    if version is None:
        version = spec.major
    if len(payload) > 0xFFFF:
        raise FrameError("payload too long")
    body = spec.magic + struct.pack("<BBHHH", version, flags, seq & 0xFFFF, typ, len(payload)) + bytes(payload)
    return body + struct.pack("<H", crc16(body))


def parse_frame(data: bytes, spec=None) -> Frame:
    """One whole, CRC-valid frame from exactly its bytes."""
    spec = spec or load()
    data = bytes(data)
    if len(data) < HEADER_BYTES + CRC_BYTES or data[:2] != spec.magic:
        raise FrameError("not a frame")
    version, flags, seq, typ, ln = struct.unpack_from("<BBHHH", data, 2)
    if len(data) != HEADER_BYTES + ln + CRC_BYTES:
        raise FrameError("length does not match the header")
    got = struct.unpack_from("<H", data, len(data) - 2)[0]
    if got != crc16(data[:-2]):
        raise FrameError("CRC mismatch")
    return Frame(version, flags, seq, typ, data[HEADER_BYTES:-2])


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)


def outcome(f, *args):
    try:
        return f(*args)
    except RuntimeError as e:
        return "panic: %s" % e
    except struct.error:
        return "short"


def same_float(a, b):
    return (a != a and b != b) or (a == b and math.copysign(1.0, a) == math.copysign(1.0, b))


READS = [(m.u16At, "<H", 2), (m.u32At, "<I", 4), (m.u64At, "<Q", 8)]
WRITES = [(m.putU16, "<H", 2), (m.putU32, "<I", 4), (m.putU64, "<Q", 8)]
reads = writes = shorts = 0
for _ in range(3000):
    n = rng.randint(0, 20)
    buf = bytearray(rng.getrandbits(8) for _ in range(n))
    at = rng.randint(0, 22)
    for read, fmt, width in READS:
        mine = outcome(read, buf, at)
        if at + width <= n:
            assert mine == struct.unpack_from(fmt, buf, at)[0], (buf.hex(), at, fmt)
            reads += 1
        else:
            assert mine == "panic: kira: slice out of range", (buf.hex(), at, fmt, mine)
            shorts += 1
    mine = outcome(m.f64At, buf, at)
    if at + 8 <= n:
        assert same_float(mine, struct.unpack_from("<d", buf, at)[0]), (buf.hex(), at)
        reads += 1
    else:
        assert mine == "panic: kira: slice out of range"
        shorts += 1
    for write, fmt, width in WRITES:
        x = rng.getrandbits(8 * width)
        got, want = bytearray(buf), bytearray(buf)
        mine = outcome(write, got, at, x)
        if at + width <= n:
            struct.pack_into(fmt, want, at, x)
            assert mine is None and got == want, (buf.hex(), at, fmt, x)
            writes += 1
        else:
            assert mine == "panic: kira: slice out of range" and got == buf, (buf.hex(), at, fmt)
            shorts += 1
    x = struct.unpack("<d", struct.pack("<Q", rng.getrandbits(64)))[0] if rng.random() < 0.5 else rng.choice([0.0, -0.0, 1.5, math.inf, -math.inf, 1e-310, math.nan])
    got, want = bytearray(buf), bytearray(buf)
    mine = outcome(m.putF64, got, at, x)
    if at + 8 <= n:
        struct.pack_into("<d", want, at, x)
        assert mine is None and got == want, (buf.hex(), at, x)
        writes += 1
    else:
        assert mine == "panic: kira: slice out of range" and got == buf
        shorts += 1
print("kira:bytes against struct: %d reads and %d writes the same, %d past the end stopped" % (reads, writes, shorts))

crcs = 0
for _ in range(500):
    data = bytes(rng.getrandbits(8) for _ in range(rng.randint(0, 64)))
    assert m.crc16(data) == binascii.crc_hqx(data, 0xFFFF), data.hex()
    assert m.crc32(data) == zlib.crc32(data), data.hex()
    crcs += 1
print("crc16 and crc32 against binascii and zlib: %d buffers, every CRC the same" % crcs)

frames = refused = 0
for _ in range(2000):
    payload = bytes(rng.getrandbits(8) for _ in range(rng.choice([0, 1, 2, 7, 16, 40, rng.randint(0, 300)])))
    typ, seq, flags, version = rng.getrandbits(16), rng.getrandbits(16), rng.getrandbits(8), rng.choice([0, 1, 2, 255])
    mine = bytes(m.encodeRaw(typ, payload, seq, flags, version))
    want = encode_raw(typ, payload, seq, flags, version)
    assert mine == want, (typ, seq, flags, version, payload.hex())
    data = bytearray(want)
    if rng.random() < 0.5:
        cut = rng.random()
        if cut < 0.3:
            data = data[:rng.randint(0, len(data))]
        elif cut < 0.6:
            data += bytes([rng.getrandbits(8)])
        else:
            k = rng.randrange(len(data))
            data[k] ^= 1 << rng.randint(0, 7)
    try:
        oracle = parse_frame(data)
    except FrameError:
        oracle = None
    got = m.parseFrame(data)
    if oracle is None:
        assert got is None, data.hex()
        refused += 1
    else:
        assert (got.version, got.flags, got.seq, got.type, bytes(got.payload)) == tuple(oracle), data.hex()
        frames += 1
print("encodeRaw and parseFrame against encode_raw and parse_frame: 2000 frames built the same, %d parsed and %d refused alike" % (frames, refused))
