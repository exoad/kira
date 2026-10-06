#!/usr/bin/env python3
# The dbwspec golden: the module's main, whose output is the C++ backend's run of it, then the Kira
# Spec, pack, unpack, bitNames, bitsMask, the name lookups and rttSamples against the hand-written
# code they port, copied below unchanged as the oracle: bibo's tools/dbw/dbwcodec.py (Spec, _msg,
# _bytes_value, pack, unpack, bit_names, bits_mask, type_name, state_name, reason_name, nack_name)
# and tools/dbw/dbwcli.py's rtt_samples, at a7da7ff, over dbw.json's messages and tables (the parts
# Spec reads, embedded below) and seeded payloads, field maps, tables and heartbeats. The Kira Spec
# is filled from the same JSON, its Maps in its order; each one's order is checked against the
# oracle's dict. They agree on all of them, a bytes field's hex with a Unicode digit or space,
# which fromhex refuses as Kira does, among them; where they say it differently (a value out of its
# type's range, in struct's own words in Python) is listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import json
import os
import random
import runpy
import struct
import sys

path = os.path.join(sys.argv[1], "src", "dbw", "dbwspec.kira.py")
runpy.run_path(path, run_name="__main__")
spec_ = importlib.util.spec_from_file_location("dbwspec_kira", path)
m = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(m)

DBW_JSON = r"""{
"protocol": {"major": 1, "minor": 0, "min_vcu_minor": 0},
"framing": {"magic": ["0xFB", "0xB1"], "max_payload": 52, "header_bytes": 10, "crc_bytes": 2, "crc": {"check": "0x29B1"}},
"timing": {"vcu": {"FRAME_GAP_MS": 20}, "host": {}},
"status_line": {},
"messages": [
{"name": "HEARTBEAT", "id": 1, "dir": "host_to_vcu", "size": 5, "struct": "<IB", "frozen": false, "fields": [{"name": "host_ms", "type": "u32", "range": [0, 4294967295]}, {"name": "mode", "type": "u8", "range": [1, 4]}]},
{"name": "ESTOP_SET", "id": 2, "dir": "host_to_vcu", "size": 2, "struct": "<H", "frozen": false, "fields": [{"name": "reason", "type": "u16", "range": [256, 511]}]},
{"name": "CLEAR_LATCHED_FAULT", "id": 3, "dir": "host_to_vcu", "size": 2, "struct": "<H", "frozen": false, "fields": [{"name": "mask", "type": "u16", "range": [0, 95]}]},
{"name": "IDENTIFY_REQUEST", "id": 4, "dir": "host_to_vcu", "size": 0, "struct": "<", "frozen": true, "fields": []},
{"name": "CONFIG_SET", "id": 5, "dir": "host_to_vcu", "size": 16, "struct": "<HHHHHHBBBB", "frozen": false, "fields": [{"name": "servo_min_us", "type": "u16", "range": [1000, 2000]}, {"name": "servo_center_us", "type": "u16", "range": [1000, 2000]}, {"name": "servo_max_us", "type": "u16", "range": [1000, 2000]}, {"name": "esc_min_us", "type": "u16", "range": [1501, 2000]}, {"name": "esc_max_us", "type": "u16", "range": [1502, 2000]}, {"name": "esc_rev_us", "type": "u16", "range": [1000, 1500]}, {"name": "slew_steer_us", "type": "u8", "range": [1, 200]}, {"name": "slew_throttle_us", "type": "u8", "range": [1, 200]}, {"name": "bumper_flags", "type": "u8", "range": [0, 3]}, {"name": "bumper_filter_ms", "type": "u8", "range": [0, 5]}]},
{"name": "DRIVE_SETPOINT", "id": 257, "dir": "host_to_vcu", "size": 6, "struct": "<HHH", "frozen": false, "fields": [{"name": "throttle_us", "type": "u16", "range": [1000, 2000]}, {"name": "steer_us", "type": "u16", "range": [1000, 2000]}, {"name": "duration_ms", "type": "u16", "range": [1, 250]}]},
{"name": "NACK", "id": 28674, "dir": "vcu_to_host", "size": 8, "struct": "<HHHH", "frozen": true, "fields": [{"name": "rejected_seq", "type": "u16", "range": [0, 65535]}, {"name": "rejected_type", "type": "u16", "range": [0, 65535]}, {"name": "code", "type": "u16", "range": [1, 13]}, {"name": "detail", "type": "u16", "range": [0, 65535]}]},
{"name": "VCU_HELLO", "id": 32768, "dir": "vcu_to_host", "size": 44, "struct": "<BBBBIBBBB8s24s", "frozen": true, "fields": [{"name": "proto_major", "type": "u8", "range": [0, 255]}, {"name": "proto_minor", "type": "u8", "range": [0, 255]}, {"name": "fw_major", "type": "u8", "range": [0, 255]}, {"name": "fw_minor", "type": "u8", "range": [0, 255]}, {"name": "build_id", "type": "u32", "range": [0, 4294967295]}, {"name": "build_flags", "type": "u8", "range": [0, 3]}, {"name": "board", "type": "u8", "range": [1, 3]}, {"name": "reset_reason", "type": "u8", "range": [0, 255]}, {"name": "reserved", "type": "u8", "range": [0, 0]}, {"name": "serial", "type": "bytes", "count": 8, "range": null}, {"name": "describe", "type": "bytes", "count": 24, "range": null}]},
{"name": "TELEMETRY", "id": 32769, "dir": "vcu_to_host", "size": 32, "struct": "<IihBBHHHHHHBBHHH", "frozen": false, "fields": [{"name": "t_us", "type": "u32", "range": [0, 4294967295]}, {"name": "ticks", "type": "i32", "range": [-2147483648, 2147483647]}, {"name": "tps", "type": "i16", "range": [-32768, 32767]}, {"name": "hall_state", "type": "u8", "range": [0, 7]}, {"name": "hall_flags", "type": "u8", "range": [0, 7]}, {"name": "hall_invalid", "type": "u16", "range": [0, 65535]}, {"name": "hall_skips", "type": "u16", "range": [0, 65535]}, {"name": "esc_us", "type": "u16", "range": [0, 2000]}, {"name": "servo_us", "type": "u16", "range": [0, 2000]}, {"name": "esc_meas_us", "type": "u16", "range": [0, 65535]}, {"name": "servo_meas_us", "type": "u16", "range": [0, 65535]}, {"name": "state", "type": "u8", "range": [1, 5]}, {"name": "info", "type": "u8", "range": [0, 127]}, {"name": "last_rx_seq", "type": "u16", "range": [0, 65535]}, {"name": "hb_seq", "type": "u16", "range": [0, 65535]}, {"name": "hb_age_ms", "type": "u16", "range": [0, 65535]}]},
{"name": "SAFETY_STATE", "id": 32773, "dir": "vcu_to_host", "size": 30, "struct": "<IBBHHHHHHBBHHHHH", "frozen": false, "fields": [{"name": "t_ms", "type": "u32", "range": [0, 4294967295]}, {"name": "state", "type": "u8", "range": [1, 5]}, {"name": "host_mode", "type": "u8", "range": [0, 4]}, {"name": "reason", "type": "u16", "range": [0, 255]}, {"name": "active", "type": "u16", "range": [0, 65535]}, {"name": "latched", "type": "u16", "range": [0, 65535]}, {"name": "ack_seq", "type": "u16", "range": [0, 65535]}, {"name": "ack_type", "type": "u16", "range": [0, 65535]}, {"name": "hold_left_ms", "type": "u16", "range": [0, 1000]}, {"name": "hb_count", "type": "u8", "range": [0, 255]}, {"name": "reserved", "type": "u8", "range": [0, 0]}, {"name": "hb_gap_max_ms", "type": "u16", "range": [0, 65535]}, {"name": "nacks", "type": "u16", "range": [0, 65535]}, {"name": "crc_errors", "type": "u16", "range": [0, 65535]}, {"name": "discarded", "type": "u16", "range": [0, 65535]}, {"name": "tx_dropped", "type": "u16", "range": [0, 65535]}]},
{"name": "CONFIG_REPORT", "id": 32774, "dir": "vcu_to_host", "size": 16, "struct": "<HHHHHHBBBB", "frozen": false, "fields": "same_as:CONFIG_SET"}
],
"fault_bits": [{"bit": 0, "name": "ESTOP"}, {"bit": 1, "name": "HEARTBEAT_TIMEOUT"}, {"bit": 2, "name": "BUMPER"}, {"bit": 3, "name": "LINK_RESET"}, {"bit": 4, "name": "WATCHDOG_RESET"}, {"bit": 5, "name": "PIN_MAP"}, {"bit": 6, "name": "INTERNAL"}, {"bit": 7, "name": "RESERVED_7"}, {"bit": 8, "name": "RESERVED_8"}, {"bit": 9, "name": "RESERVED_9"}, {"bit": 10, "name": "RESERVED_10"}, {"bit": 11, "name": "RESERVED_11"}, {"bit": 12, "name": "RESERVED_12"}, {"bit": 13, "name": "RESERVED_13"}, {"bit": 14, "name": "RESERVED_14"}, {"bit": 15, "name": "RESERVED_15"}],
"info_bits": [{"bit": 0, "name": "SETPOINT_LIVE"}, {"bit": 1, "name": "HB_FRESH"}, {"bit": 2, "name": "BUMPER_RAW"}, {"bit": 3, "name": "BUMPER_ENABLED"}, {"bit": 4, "name": "HOST_SESSION"}, {"bit": 5, "name": "ESC_LIVE"}, {"bit": 6, "name": "SERVO_LIVE"}],
"bumper_flag_bits": [{"bit": 0, "name": "ENABLE"}, {"bit": 1, "name": "PRESSED_LOW"}],
"reasons": [{"value": 0, "name": "NONE", "recoverable": true}, {"value": 1, "name": "ESTOP", "recoverable": false}, {"value": 2, "name": "HEARTBEAT_TIMEOUT", "recoverable": true}, {"value": 3, "name": "BUMPER", "recoverable": false}, {"value": 4, "name": "LINK_RESET", "recoverable": true}, {"value": 5, "name": "WATCHDOG_RESET", "recoverable": false}, {"value": 6, "name": "PIN_MAP", "recoverable": false}, {"value": 7, "name": "INTERNAL", "recoverable": false}, {"value": 8, "name": "SETPOINT_EXPIRED", "recoverable": true}, {"value": 9, "name": "HOLD", "recoverable": true}, {"value": 10, "name": "NOT_ARMED", "recoverable": true}, {"value": 257, "name": "LIDAR_STALE", "recoverable": true}, {"value": 258, "name": "OBSTACLE_STOP_STALE", "recoverable": true}, {"value": 259, "name": "TICK_STALE", "recoverable": true}, {"value": 260, "name": "CONTROL_DEAD", "recoverable": true}, {"value": 261, "name": "VCU_SILENT", "recoverable": true}, {"value": 262, "name": "VCU_VERSION_MISMATCH", "recoverable": false}, {"value": 263, "name": "VCU_NOT_FOUND", "recoverable": true}, {"value": 264, "name": "ROSTER_INCOMPLETE", "recoverable": true}, {"value": 265, "name": "ROSTER_ARMING", "recoverable": true}, {"value": 266, "name": "UNKNOWN_FAULT_BITS", "recoverable": false}, {"value": 267, "name": "VIEWER_ESTOP", "recoverable": false}, {"value": 268, "name": "SIGNAL", "recoverable": false}, {"value": 269, "name": "MODE_CHANGE", "recoverable": false}, {"value": 270, "name": "FINISHED", "recoverable": false}, {"value": 271, "name": "OBSTACLE", "recoverable": true}, {"value": 272, "name": "OLD_FIRMWARE", "recoverable": false}, {"value": 273, "name": "LINK_LOST", "recoverable": true}, {"value": 274, "name": "ARM_TIMEOUT", "recoverable": true}, {"value": 275, "name": "DISARM", "recoverable": false}, {"value": 276, "name": "ROSTER_FAULT", "recoverable": true}, {"value": 277, "name": "REARM_LIMIT", "recoverable": false}, {"value": 278, "name": "SPEED_CAP", "recoverable": true}, {"value": 279, "name": "SPEED_UNKNOWN", "recoverable": false}],
"nack_codes": [{"code": 1, "name": "UNKNOWN_TYPE"}, {"code": 2, "name": "BAD_LENGTH"}, {"code": 3, "name": "WRONG_DIRECTION"}, {"code": 4, "name": "BAD_FLAGS"}, {"code": 5, "name": "VERSION"}, {"code": 6, "name": "OUT_OF_RANGE"}, {"code": 7, "name": "NOT_ARMED"}, {"code": 8, "name": "HOLD"}, {"code": 9, "name": "STATE"}, {"code": 10, "name": "STILL_ACTIVE"}, {"code": 11, "name": "ESTOP_LATCHED"}, {"code": 12, "name": "FAULT_LATCHED"}, {"code": 13, "name": "NOT_PERMITTED"}],
"enums": {"State": {"values": [{"name": "BOOT", "value": 0}, {"name": "HOLD", "value": 1}, {"name": "DISARMED", "value": 2}, {"name": "ARMING", "value": 3}, {"name": "ARMED", "value": 4}, {"name": "FAULT", "value": 5}]}, "HostMode": {"values": [{"name": "MANUAL", "value": 1}, {"name": "AUTO", "value": 2}, {"name": "BENCH", "value": 3}, {"name": "PROGRAM", "value": 4}]}, "Board": {"values": [{"name": "PICO2", "value": 1}, {"name": "PICO2_W", "value": 2}, {"name": "HOSTED", "value": 3}]}, "ResetReason": {"values": [{"name": "POWER_ON", "value": 0}, {"name": "WATCHDOG", "value": 1}, {"name": "BROWNOUT", "value": 2}, {"name": "RUN_PIN", "value": 3}, {"name": "SOFTWARE", "value": 4}, {"name": "HOSTED", "value": 5}, {"name": "UNKNOWN", "value": 255}]}}
}"""

# ---- the oracle: dbwcodec.py and dbwcli.py as written by hand ------------------------------------
HEADER_BYTES = 10
CRC_BYTES = 2


class SpecError(Exception):
    """dbw.json is missing, or does not say what this codec needs to be sure of."""


class FrameError(ValueError):
    """A frame or payload this codec was asked to build is not valid."""


def crc16(data: bytes, crc: int = 0xFFFF) -> int:
    # CRC-16/CCITT-FALSE, which dbwcodec takes from dbwframe.kira.
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = (crc << 1 ^ 0x1021 if crc & 0x8000 else crc << 1) & 0xFFFF
    return crc


class Spec:
    """dbw.json, indexed."""

    def __init__(self, data: dict, path=None):
        self.data = data
        self.path = path
        fr = data["framing"]
        self.magic = bytes(int(x, 16) for x in fr["magic"])
        self.major = data["protocol"]["major"]
        self.minor = data["protocol"]["minor"]
        self.min_vcu_minor = data["protocol"]["min_vcu_minor"]
        self.max_payload = fr["max_payload"]
        if fr["header_bytes"] != HEADER_BYTES or fr["crc_bytes"] != CRC_BYTES:
            raise SpecError("the frame shape in dbw.json is not the one this codec implements")
        if crc16(b"123456789") != int(fr["crc"]["check"], 16):
            raise SpecError("crc16 does not give the spec's check value")
        self.timing_vcu = data["timing"]["vcu"]
        self.timing_host = data["timing"]["host"]
        self.frame_gap_us = self.timing_vcu["FRAME_GAP_MS"] * 1000
        self.messages = {}
        self.by_id = {}
        for m in data["messages"]:
            fields = m["fields"]
            if isinstance(fields, str):
                other = fields.split(":", 1)[1]
                fields = next(x for x in data["messages"] if x["name"] == other)["fields"]
            entry = {"name": m["name"], "id": m["id"], "dir": m["dir"], "fields": fields, "size": m["size"],
                     "struct": m["struct"], "frozen": m["frozen"]}
            if struct.calcsize(entry["struct"]) != entry["size"]:
                raise SpecError(f"{m['name']}: struct and size disagree")
            self.messages[m["name"]] = entry
            self.by_id[m["id"]] = entry
        self.fault_bits = {b["name"]: b["bit"] for b in data["fault_bits"]}
        self.fault_names = {b["bit"]: b["name"] for b in data["fault_bits"]}
        self.info_bits = {b["name"]: b["bit"] for b in data["info_bits"]}
        self.bumper_flag_bits = {b["name"]: b["bit"] for b in data["bumper_flag_bits"]}
        self.reasons = {r["name"]: r for r in data["reasons"]}
        self.reason_by_value = {r["value"]: r for r in data["reasons"]}
        self.nack_codes = {n["name"]: n["code"] for n in data["nack_codes"]}
        self.nack_names = {n["code"]: n["name"] for n in data["nack_codes"]}
        self.enums = {name: {v["name"]: v["value"] for v in e["values"]} for name, e in data["enums"].items()}
        self.enum_names = {name: {v["value"]: v["name"] for v in e["values"]} for name, e in data["enums"].items()}
        self.status_line = data["status_line"]


def _msg(name_or_type, spec):
    if isinstance(name_or_type, int):
        m = spec.by_id.get(name_or_type)
    else:
        m = spec.messages.get(name_or_type)
    if m is None:
        raise FrameError(f"unknown message {name_or_type!r}")
    return m


def _bytes_value(v, count, fname):
    if isinstance(v, str):
        try:
            v = bytes.fromhex(v)
        except ValueError as e:
            raise FrameError(f"{fname}: not hex") from e
    v = bytes(v)
    if len(v) != count:
        raise FrameError(f"{fname}: {len(v)} bytes, the field is {count}")
    return v


def pack(name, fields: dict, spec=None) -> bytes:
    """The payload of message `name` from a dict of every field by name (bytes fields as bytes or hex)."""
    spec = spec or load()
    m = _msg(name, spec)
    names = [f["name"] for f in m["fields"]]
    extra = set(fields) - set(names)
    if extra:
        raise FrameError(f"{m['name']}: unknown field(s) {sorted(extra)}")
    missing = [n for n in names if n not in fields]
    if missing:
        raise FrameError(f"{m['name']}: missing field(s) {missing}")
    vals = []
    for f in m["fields"]:
        v = fields[f["name"]]
        if f["type"] == "bytes":
            v = _bytes_value(v, f["count"], f["name"])
        vals.append(v)
    try:
        return struct.pack(m["struct"], *vals)
    except struct.error as e:
        raise FrameError(f"{m['name']}: {e}") from e


def unpack(name, payload: bytes, spec=None) -> dict:
    """Fields by name; bytes fields come back as lowercase hex strings, as the spec's golden frames write them."""
    spec = spec or load()
    m = _msg(name, spec)
    if len(payload) != m["size"]:
        raise FrameError(f"{m['name']}: {len(payload)} payload bytes, the message is {m['size']}")
    vals = struct.unpack(m["struct"], payload)
    out = {}
    for f, v in zip(m["fields"], vals):
        out[f["name"]] = v.hex() if isinstance(v, bytes) else v
    return out


def type_name(typ: int, spec=None) -> str:
    spec = spec or load()
    m = spec.by_id.get(typ)
    return m["name"] if m else f"0x{typ:04X}"


def bit_names(table: dict, value: int) -> list:
    """Names of the bits set in value, low bit first; a bit the table lacks shows as BIT<n>."""
    inv = {b: n for n, b in table.items()}
    return [inv.get(i, f"BIT{i}") for i in range(16) if value >> i & 1]


def bits_mask(table: dict, names) -> int:
    m = 0
    for n in names:
        m |= 1 << table[n]
    return m


def state_name(spec, value: int) -> str:
    return spec.enum_names["State"].get(value, f"STATE{value}")


def reason_name(spec, code: int) -> str:
    r = spec.reason_by_value.get(code)
    return r["name"] if r else f"0x{code:04X}"


def nack_name(spec, code: int) -> str:
    return spec.nack_names.get(code, f"NACK{code}")


def rtt_samples(spec, events, sent_hb):
    """Round trips in ms: telemetry receipt minus heartbeat send minus the VCU's own hb_age_ms."""
    pending = dict(sent_hb)       # seq -> t_us
    out = []
    for e in events:
        if e.msg.name != "TELEMETRY" or not e.msg.fields:
            continue
        s = e.msg.fields["hb_seq"]
        t0 = pending.pop(s, None)
        if t0 is not None:
            out.append((e.t_us - t0) / 1000 - e.msg.fields["hb_age_ms"])
    return out


# ---- the Kira Spec, filled from the same JSON ---------------------------------------------------
def kira_spec(data):
    s = m.Spec()
    for msg in data["messages"]:
        fields = msg["fields"]
        if isinstance(fields, str):
            other = fields.split(":", 1)[1]
            fields = next(x for x in data["messages"] if x["name"] == other)["fields"]
        k = s.addMessage(msg["name"], msg["id"], msg["dir"], msg["size"])
        for f in fields:
            k.addField(f["name"], f["type"], f.get("count", 0))
        assert k.packedSize() == struct.calcsize(msg["struct"]), msg["name"]
    for b in data["fault_bits"]:
        s.faultBits.add(b["name"], b["bit"])
    for b in data["info_bits"]:
        s.infoBits.add(b["name"], b["bit"])
    for r in data["reasons"]:
        s.reasons.add(r["name"], r["value"])
    for n in data["nack_codes"]:
        s.nacks.add(n["name"], n["code"])
    for name, e in data["enums"].items():
        t = s.enumTable(name)
        for v in e["values"]:
            t.add(v["name"], v["value"])
    return s


def kira_pack(s, name, fields):
    ints = {k: v for k, v in fields.items() if isinstance(v, int)}
    hexes = {k: (v if isinstance(v, str) else bytes(v).hex()) for k, v in fields.items() if not isinstance(v, int)}
    p = m.pack(s, name, ints, hexes)
    return p.error, bytes(p.payload)


def kira_unpack(s, name, payload):
    u = m.unpack(s, name, bytearray(payload))
    if u.error:
        return u.error
    out = {}
    for f in s.messages[name].fields:
        out[f.name] = u.hex[f.name] if f.kind == "bytes" else u.ints[f.name]
    return out


data = json.loads(DBW_JSON)
oracle = Spec(data)
mine = kira_spec(data)

# The indexes hold the same keys in the same order, each to the same entry.
assert list(mine.messages) == list(oracle.messages)
assert list(mine.byId) == list(oracle.by_id)
assert all(mine.byId[i].name == oracle.by_id[i]["name"] for i in oracle.by_id)
assert [[(f.name, f.kind) for f in k.fields] for k in mine.messages.values()] == \
    [[(f["name"], f["type"]) for f in e["fields"]] for e in oracle.messages.values()]
assert list(mine.faultBits.byName.items()) == list(oracle.fault_bits.items())
assert list(mine.faultBits.byValue.items()) == list(oracle.fault_names.items())
assert list(mine.infoBits.byName.items()) == list(oracle.info_bits.items())
assert list(mine.nacks.byName.items()) == list(oracle.nack_codes.items())
assert list(mine.nacks.byValue.items()) == list(oracle.nack_names.items())
assert list(mine.reasons.byName.items()) == [(n, r["value"]) for n, r in oracle.reasons.items()]
assert list(mine.reasons.byValue.items()) == [(v, r["name"]) for v, r in oracle.reason_by_value.items()]
assert list(mine.enums) == list(oracle.enums)
assert all(list(mine.enums[n].byName.items()) == list(oracle.enums[n].items()) for n in oracle.enums)
assert all(list(mine.enums[n].byValue.items()) == list(oracle.enum_names[n].items()) for n in oracle.enums)
print("Spec against dbwcodec.Spec: %d messages, %d ids, every table and enum in the same order"
      % (len(mine.messages), len(mine.byId)))

# ---- the cases ---------------------------------------------------------------------------------
rng = random.Random(20261005)
RANGES = {"u8": (0, 255), "i8": (-128, 127), "u16": (0, 65535), "i16": (-32768, 32767),
          "u32": (0, 4294967295), "i32": (-2147483648, 2147483647)}
NAMES = list(oracle.messages) + ["NOPE", "heartbeat", "HEARTBEAT_", ""]
EXTRA = ["zz", "aa", "mode2", "Mode", "x", "host_ms_", "serial0", "A", "_b"]


def int_value(kind, bad):
    lo, hi = RANGES[kind]
    if bad:
        return rng.choice([lo - 1, hi + 1, rng.randint(-2 ** 40, 2 ** 40)])
    return rng.choice([rng.randint(lo, hi), rng.randint(lo, hi), lo, hi, max(lo, 0)])


# A bytes field as bytes or hex, lower or upper case, spaced or not; a bad one is a byte short or
# long, or has a character fromhex refuses (a Unicode digit and space among them).
def hex_value(count, bad):
    n = rng.choice([count - 1, count + 1, 0]) if bad and rng.random() < 0.5 else count
    raw = bytes(rng.randrange(256) for _ in range(max(n, 0)))
    pick = rng.random()
    if pick < 0.15 and not (bad and n == count):
        return raw
    text = raw.hex()
    if pick < 0.35:
        text = text.upper()
    elif pick < 0.55:
        text = " ".join(text[i:i + 2] for i in range(0, len(text), 2)) + rng.choice(["", " ", "\t", "\n"])
    if bad and n == count:
        i = rng.randrange(len(text) + 1)
        text = text[:i] + rng.choice(["g", "x", " 0", "-", "0", "١", "　"]) + text[i:]
    return text


packs = refused = 0
for k in range(6000):
    name = rng.choice(NAMES) if rng.random() < 0.1 else rng.choice(list(oracle.messages) + ["VCU_HELLO"] * 3)
    entry = oracle.messages.get(name)
    # most maps are whole; a few miss a field, have a bad one (a bytes one when there is one,
    # mostly), or carry names no field has
    fields = {}
    flaw = rng.random()
    names = [f["name"] for f in entry["fields"]] if entry else []
    blobs = [f["name"] for f in entry["fields"] if f["type"] == "bytes"] if entry else []
    skip = rng.choice(names) if names and flaw < 0.08 else None
    worse = rng.choice(blobs if blobs and rng.random() < 0.6 else names) if names and 0.08 <= flaw < 0.3 else None
    for f in entry["fields"] if entry else []:
        if f["name"] != skip:
            bad = f["name"] == worse
            fields[f["name"]] = hex_value(f["count"], bad) if f["type"] == "bytes" else int_value(f["type"], bad)
    if 0.3 <= flaw < 0.38:
        for _ in range(rng.choice([1, 1, 2, 3])):
            fields[rng.choice(EXTRA)] = rng.randint(0, 9)
    try:
        want = ("", pack(name, fields, oracle))
    except FrameError as e:
        want = (str(e), b"")
        cause = e.__cause__
    got = kira_pack(mine, name, fields)
    if want[0] and isinstance(cause, struct.error):
        # struct.error's words are Python's own: both refuse a value out of its type's range
        assert got[0].startswith(name + ": ") and " is out of range for " in got[0], (name, fields, got)
        refused += 1
    else:
        assert got == want, (name, fields, got, want)
        if want[0]:
            refused += 1
    packs += 1
    if not want[0]:
        assert kira_unpack(mine, name, want[1]) == unpack(name, want[1], oracle)
print("pack against dbwcodec.pack: %d field maps, %d refused (an unknown or missing field, bad hex, a bytes "
      "field's length, a value out of its type), every payload and every other error the same" % (packs, refused))

unpacks = 0
for k in range(6000):
    name = rng.choice(NAMES)
    size = oracle.messages[name]["size"] if name in oracle.messages else rng.randrange(40)
    n = rng.choice([size, size, size, size + 1, max(size - 1, 0), 0])
    payload = bytes(rng.randrange(256) for _ in range(n))
    try:
        want = unpack(name, payload, oracle)
    except FrameError as e:
        want = str(e)
    got = kira_unpack(mine, name, payload)
    assert got == want and (isinstance(want, str) or list(got) == list(want)), (name, payload, got, want)
    unpacks += 1
print("unpack against dbwcodec.unpack: %d payloads, every field in field order and every error the same" % unpacks)

bits = 0
for k in range(3000):
    table = {}
    for _ in range(rng.randrange(20)):
        table[rng.choice(EXTRA + ["ESTOP", "BUMPER", "HB"])] = rng.randrange(18)
    value = rng.choice([rng.randrange(1 << 16), rng.randrange(1 << 20), -rng.randrange(1 << 16), 0])
    assert list(m.bitNames(table, value)) == bit_names(table, value), (table, value)
    names = [rng.choice(list(table) + ["NOPE"]) for _ in range(rng.randrange(5))] if table else []
    try:
        want = bits_mask(table, names)
    except KeyError:
        want = None
    assert m.bitsMask(table, names) == want, (table, names)
    bits += 1
for v in [0, 1, 0x27, 0xFFFF, 0x1_0001, -1]:
    assert list(m.bitNames(mine.faultBits.byName, v)) == bit_names(oracle.fault_bits, v)
    assert list(m.bitNames(mine.infoBits.byName, v)) == bit_names(oracle.info_bits, v)
print("bitNames and bitsMask against bit_names and bits_mask: %d tables, the spec's own and a name to "
      "a bit given twice among them, the same" % bits)

codes = 0
for code in list(range(0, 300)) + [0x7002, 0x8000, 0x8001, 0x8005, 0x8006, 0xFFFF, 0x10000]:
    assert m.typeName(mine, code) == type_name(code, oracle), code
    assert m.reasonName(mine, code) == reason_name(oracle, code), code
    assert m.nackName(mine, code) == nack_name(oracle, code), code
    assert m.stateName(mine, code) == state_name(oracle, code), code
    codes += 1
print("typeName, reasonName, nackName and stateName against type_name, reason_name, nack_name and "
      "state_name: %d codes, the same" % codes)


class Msg:
    def __init__(self, name, fields):
        self.name, self.fields = name, fields


class Ev:
    def __init__(self, t_us, msg):
        self.t_us, self.msg = t_us, msg


rtts = samples = 0
for k in range(2000):
    t = rng.randrange(1 << 40)
    sent = {}
    for _ in range(rng.randrange(30)):
        sent[rng.randrange(40)] = t + rng.randrange(-5000, 2_000_000)
    events, mine_events = [], []
    for _ in range(rng.randrange(40)):
        name = rng.choice(["TELEMETRY", "TELEMETRY", "TELEMETRY", "SAFETY_STATE", "NACK"])
        seq, age, at = rng.randrange(45), rng.randrange(300), t + rng.randrange(3_000_000)
        has = rng.random() < 0.9
        events.append(Ev(at, Msg(name, {"hb_seq": seq, "hb_age_ms": age} if has else None)))
        mine_events.append(m.Ev(at, name, has, seq, age))
    before = dict(sent)
    want = rtt_samples(oracle, events, sent)
    got = m.rttSamples(mine_events, sent)
    assert got == want and sent == before and list(sent) == list(before), (sent, got, want)
    rtts += 1
    samples += len(want)
print("rttSamples against rtt_samples: %d runs, %d round trips, each heartbeat matched once and the "
      "caller's Map of them untouched" % (rtts, samples))

# Where the two say it differently: a value out of its type's range, in struct's words in Python.
late = {"throttle_us": 70000, "steer_us": 1500, "duration_ms": 1}
try:
    pack("DRIVE_SETPOINT", late, oracle)
except FrameError as e:
    print("python refuses throttle_us 70000 in struct's words (%s), kira says %s"
          % (type(e.__cause__).__name__, kira_pack(mine, "DRIVE_SETPOINT", late)[0]))
