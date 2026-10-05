#!/usr/bin/env python3
# The wsframe golden: the module's main, whose output is the C++ backend's run of it, then
# encode and readFrames against the hand-written ws_frame and read_frames they port (bibo's
# firmware/pilot/tools/dash/webdrive_fake.py at master 36c95bb, copied below unchanged as the
# oracle; bibodash.py's ws_frame is the same), over seeded payloads in every length form,
# masked and not, cut at every kind of boundary. Each must give the same bytes and frames.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dash", "wsframe.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("wsframe_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: webdrive_fake.py as written by hand -------------------------------------------
def ws_frame(opcode, payload):
    n = len(payload)
    if n < 126:
        head = bytes([0x80 | opcode, n])
    elif n < 65536:
        head = bytes([0x80 | opcode, 126]) + n.to_bytes(2, "big")
    else:
        head = bytes([0x80 | opcode, 127]) + n.to_bytes(8, "big")
    return head + payload


def read_frames(buf):
    # Every complete frame in buf: (opcode, payload) list, and the rest.
    out = []
    while len(buf) >= 2:
        b1, b2 = buf[0], buf[1]
        n, at = b2 & 0x7F, 2
        if n == 126:
            if len(buf) < 4:
                break
            n, at = int.from_bytes(buf[2:4], "big"), 4
        elif n == 127:
            if len(buf) < 10:
                break
            n, at = int.from_bytes(buf[2:10], "big"), 10
        masked = b2 & 0x80
        if len(buf) < at + (4 if masked else 0) + n:
            break
        mask = buf[at:at + 4] if masked else b"\0\0\0\0"
        at += 4 if masked else 0
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(buf[at:at + n]))
        out.append((b1 & 0x0F, data))
        buf = buf[at + n:]
    return out, buf


# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
SIZES = [0, 1, 2, 3, 124, 125, 126, 127, 200, 1000]
LONG = [65534, 65535, 65536, 65537]
OPCODES = [0x1, 0x2, 0x8, 0x9, 0xA]
encoded = 0
streams = 0
frames = 0
cuts = 0
for s in range(160):
    pieces = []
    want = []
    for k in range(rng.randint(1, 5)):
        n = LONG[s % 4] if s < 8 and k == 0 else rng.choice(SIZES) if rng.random() < 0.5 else rng.randint(0, 300)
        op = rng.choice(OPCODES)
        payload = bytes(rng.getrandbits(8) for _ in range(n))
        if rng.random() < 0.5:
            mine = bytes(m.encode(op, payload))
            assert mine == ws_frame(op, payload), (op, n)
            encoded += 1
            pieces.append(mine)
        else:
            key = bytes(rng.getrandbits(8) for _ in range(4))
            mine = bytes(m.encodeMasked(op, payload, key))
            head = mine[:len(mine) - n - 4]
            assert mine[len(head):len(head) + 4] == key and head[1] & 0x80, (op, n)
            pieces.append(mine)
        want.append((op, payload))
    stream = b"".join(pieces)
    # whole, then cut inside a header, a key, a payload, and at each frame's end
    ends = []
    at = 0
    for p in pieces:
        at += len(p)
        ends.append(at)
    cut_at = sorted({len(stream)} | {rng.randint(0, len(stream)) for _ in range(4)} | set(ends) | {e - 1 for e in ends if e > 0} | {1, 2, 3, 9})
    for cut in cut_at:
        if cut > len(stream):
            continue
        buf = bytearray(stream[:cut])
        got = []
        used = m.readFrames(buf, got)
        oracle, rest = read_frames(bytes(buf))
        assert [(f.opcode, bytes(f.payload)) for f in got] == oracle, cut
        assert bytes(buf[used:]) == rest, cut
        cuts += 1
        frames += len(got)
    whole_got = []
    m.readFrames(stream, whole_got)
    assert [(f.opcode, bytes(f.payload)) for f in whole_got] == want
    streams += 1
print("encode against ws_frame: %d frames, every byte the same" % encoded)
print("readFrames against read_frames: %d streams cut %d ways, %d frames and every rest the same" % (streams, cuts, frames))
