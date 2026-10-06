#!/usr/bin/env python3
# main (the C++ run), then the Kira against dbwcodec.py's load() and Spec at 8c2a7a8, copied unchanged
# below, over bibo's dbw.json beside this file and seeded edits of it.   python driver.py <out dir>
import importlib.util
import json
import os
import random
import runpy
import struct
import sys
import tempfile
from pathlib import Path

path = os.path.join(sys.argv[1], "src", "dbw", "dbwjson.kira.py")
runpy.run_path(path, run_name="__main__")
spec_ = importlib.util.spec_from_file_location("dbwjson_kira", path)
m = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(m)

HEADER_BYTES = 10
CRC_BYTES = 2


class SpecError(Exception):
    """dbw.json is missing, or does not say what this codec needs to be sure of."""


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


def load(path=None) -> Spec:
    p = Path(path)
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise SpecError(f"cannot read {p}: {e}") from e
    return Spec(data, p)


def canon(s):
    # Every table Spec builds, in the order the Kira writes them.
    return {"magic": list(s.magic), "major": s.major, "minor": s.minor, "min_vcu_minor": s.min_vcu_minor,
            "max_payload": s.max_payload, "timing_vcu": s.timing_vcu, "timing_host": s.timing_host,
            "frame_gap_us": s.frame_gap_us, "messages": s.messages, "by_id": s.by_id, "fault_bits": s.fault_bits,
            "fault_names": s.fault_names, "info_bits": s.info_bits, "bumper_flag_bits": s.bumper_flag_bits,
            "reasons": s.reasons, "reason_by_value": s.reason_by_value, "nack_codes": s.nack_codes,
            "nack_names": s.nack_names, "enums": s.enums, "enum_names": s.enum_names, "status_line": s.status_line}


def oracle(p):
    try:
        return json.dumps(canon(load(p)))
    except (SpecError, ValueError) as e:
        return "%s: %s" % (type(e).__name__, e)
    except StopIteration:
        return "StopIteration"


rng = random.Random(20261006)
work = tempfile.mkdtemp()
target = os.path.join(work, "dbw.json")
real = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "dbw.json"), encoding="utf-8").read()


def run(text):
    with open(target, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    want = oracle(target)
    got = m.tables(target, Path(target).read_text(encoding="utf-8"))
    assert got == want, (text[:300], got[:300], want[:300])
    return want


first = run(real)
data = json.loads(real)
print("tables against dbwcodec's Spec on bibo's dbw.json: %d messages, %d reasons, %d bytes of tables, the same"
      % (len(data["messages"]), len(data["reasons"]), len(first)))


def edit(d):
    pick = rng.randrange(14)
    msgs = d["messages"]
    k = rng.randrange(len(msgs))
    if pick == 0:
        msgs[k]["id"] = rng.choice([rng.randrange(65536), msgs[(k + 1) % len(msgs)]["id"]])
    elif pick == 1:
        msgs[k]["name"] = rng.choice(["RENAMED", msgs[(k + 1) % len(msgs)]["name"], "é"])
    elif pick == 2 and isinstance(msgs[k]["fields"], list):
        msgs[k]["struct"] = msgs[k]["struct"] + rng.choice(["B", "H", "2s", "x", "Q", "3I"])
    elif pick == 3:
        msgs[k]["size"] = rng.choice([msgs[k]["size"] + 1, float(msgs[k]["size"]), msgs[k]["size"]])
    elif pick == 4:
        d["framing"]["header_bytes"] = rng.choice([10, 10.0, 11, "10", True, None])
    elif pick == 5:
        d["framing"]["crc"]["check"] = rng.choice(["0x29B1", "29b1", "0X29b1", " 0x29B1 ", "0x29B2", "0xZZ", "", "0x"])
    elif pick == 6:
        d["framing"]["magic"] = [rng.choice(["0xFB", "0xfb", "FB", "0x00", "0xFF", "0x100", "0xG1", "B1"]) for _ in range(rng.randint(0, 3))]
    elif pick == 7:
        d["timing"]["vcu"]["FRAME_GAP_MS"] = rng.choice([20, 25, 20.5, 0, -1])
    elif pick == 8:
        rng.shuffle(msgs)
    elif pick == 9:
        msgs.append(json.loads(json.dumps(rng.choice(msgs))))
    elif pick == 10:
        rows = d[rng.choice(["fault_bits", "info_bits", "reasons", "nack_codes"])]
        if rows:
            row = rng.choice(rows)
            for key in ("bit", "value", "code"):
                if key in row:
                    row[key] = rng.choice([rng.randrange(300), rows[0][key], 1.5, -2])
            row["name"] = rng.choice([row["name"], "SAME", rows[0]["name"]])
    elif pick == 11:
        e = d["enums"][rng.choice(list(d["enums"]))]["values"]
        rng.choice(e)["value"] = rng.choice([0, 7, e[0]["value"], 2.5])
    elif pick == 12:
        d["status_line"] = rng.choice([None, "text", [1, 2], {"doc": "é", "n": float("nan")}, d["status_line"]])
    else:
        d["protocol"]["minor"] = rng.choice([1, 0.5, "1.0", None, [1]])


def damaged(text):
    while True:
        i = rng.randrange(len(text))
        out = text[:i] + rng.choice(["", ",", "}", "]", "\"", "x", "\\", "\x01"]) + text[i + 1:]
        try:
            json.loads(out)
        except ValueError:
            return out


outcomes = {}
for k in range(600):
    if k % 5 == 4:
        text = damaged(rng.choice([real, json.dumps(data)]))
    else:
        d = json.loads(real)
        for _ in range(rng.choice([1, 1, 2, 3])):
            edit(d)
        text = json.dumps(d, indent=rng.choice([None, 1, 2]), ensure_ascii=rng.random() < 0.5)
    got = run(text)
    kind = got.split(":")[0] if not got.startswith("{") else "tables"
    if got.startswith("SpecError: cannot read"):
        kind = "unreadable"
    outcomes[kind] = outcomes.get(kind, 0) + 1
print("tables against load(): 600 edited dbw.json texts, the same tables or the same error: %s"
      % ", ".join("%s %d" % (k, outcomes[k]) for k in sorted(outcomes)))
