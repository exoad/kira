#!/usr/bin/env python3
# The linkwatch golden: the module's main, whose output is the C++ backend's run of it, then
# parseLink, parseWireless and firstManaged against the hand-written parse_link, parse_wireless
# and first_managed they port (bibo's firmware/pilot/tools/dash/linkwatch.py at master deb187c,
# copied below unchanged as the oracle), over linkwatch_test.py's captures and seeded ASCII text
# built from the lines `iw` and /proc/net/wireless write, broken and padded every way Python's
# whitespace allows. They agree wherever the hand-written ones return; where they raise (a
# bitrate float() refuses, an infinite level int() refuses) the port gives none, and Python's \d
# and float() also take other scripts' digits: those are listed at the end.
#
#   python driver.py <the directory kira --target py --out wrote>
import importlib.util
import os
import random
import re
import runpy
import sys

path = os.path.join(sys.argv[1], "src", "dash", "linkwatch.kira.py")
runpy.run_path(path, run_name="__main__")
spec = importlib.util.spec_from_file_location("linkwatch_kira", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


# ---- the oracle: linkwatch.py as written by hand -----------------------------------------------
def parse_link(text):
    """Parse `iw dev X link` output into the snapshot fields, or None when unrecognised."""
    if not isinstance(text, str):
        return None
    out = {"ssid": "", "freq_mhz": None, "dbm": None, "tx_mbps": None, "rx_mbps": None,
           "connected": False}
    if re.search(r"^\s*Not connected\.?\s*$", text, re.M):
        return out
    if not re.match(r"\s*Connected to ", text):
        return None
    out["connected"] = True
    m = re.search(r"^\s*SSID: ?(.*?)\s*$", text, re.M)
    if m:
        out["ssid"] = m.group(1)
    m = re.search(r"^\s*freq: (\d+)", text, re.M)
    if m:
        out["freq_mhz"] = int(m.group(1))
    m = re.search(r"^\s*signal: (-?\d+)", text, re.M)
    if m:
        out["dbm"] = int(m.group(1))
    for key in ("tx", "rx"):
        m = re.search(r"^\s*%s bitrate: ([\d.]+) MBit/s" % key, text, re.M)
        if m:
            out[key + "_mbps"] = float(m.group(1))
    return out


def parse_wireless(text, iface):
    """/proc/net/wireless fallback: the link level in dBm for iface, or None."""
    for line in (text or "").splitlines():
        if line.strip().startswith(iface + ":"):
            f = line.split(":", 1)[1].split()
            try:
                v = int(float(f[2]))
                return v - 256 if v > 0 else v
            except (IndexError, ValueError):
                return None
    return None


def first_managed(text):
    """The first interface of type managed in `iw dev` output."""
    name = None
    for line in (text or "").splitlines():
        s = line.strip()
        if s.startswith("Interface "):
            name = s.split()[1]
        elif s == "type managed" and name:
            return name
    return None


def link_of(text):
    p = m.parseLink(text)
    if p is None:
        return None
    return {"ssid": p.ssid, "freq_mhz": p.freqMhz, "dbm": p.dbm, "tx_mbps": p.txMbps, "rx_mbps": p.rxMbps,
            "connected": p.connected}


# ---- the cases ---------------------------------------------------------------------------------
FIVE = """Connected to b8:f8:53:f8:ce:84 (on wlan0)
\tSSID: Fios-2CTx6
\tfreq: 5280
\tRX: 34530416 bytes (202326 packets)
\tTX: 113467223 bytes (111939 packets)
\tsignal: -45 dBm
\trx bitrate: 600.4 MBit/s 80MHz HE-MCS 11 HE-NSS 1 HE-GI 0 HE-DCM 0
\ttx bitrate: 540.3 MBit/s 80MHz HE-MCS 10 HE-NSS 1 HE-GI 0 HE-DCM 0
"""
TWO = """Connected to 3c:84:6a:11:22:33 (on wlan0)
\tSSID: WhoopWhoop
\tfreq: 2437.0
\tsignal: -71 dBm
\trx bitrate: 65.0 MBit/s MCS 7
\ttx bitrate: 72.2 MBit/s MCS 7 short GI
"""
captured = [FIVE, TWO, "Not connected.\n", "", "command failed: No such device (-19)\n", "\x00\xff junk",
            FIVE.replace("Fios-2CTx6", "my phone  net")]
for text in captured:
    assert link_of(text) == parse_link(text), text
assert m.firstManaged("phy#0\n\tInterface p2p0\n\t\ttype P2P-device\n\tInterface wlan0\n\t\ttype managed\n") == "wlan0"
assert m.parseWireless("Inter-| sta\n face | tus | link level noise\n wlan0: 0000   70.  -40.  -256\n", "wlan0") == -40

rng = random.Random(20261005)
SPACE = [" ", "\t", "\x0b", "\x0c", "\r", "\x1c", "\x1d", "\x1e", "\x1f", "  ", ""]
BREAK = ["\n", "\r\n", "\r", "\x0b", "\x0c", "\x1c", "\x1d", "\x1e"]


def pad():
    return "".join(rng.choice(SPACE) for _ in range(rng.randint(0, 3)))


def number():
    return rng.choice(["%d" % rng.randint(0, 99999), "%d" % rng.randint(-120, 120), "%.1f" % rng.uniform(0, 1200),
                       "-", "", "x", "%d.0" % rng.randint(0, 6000), ".5", "5.", "0", "00042", "-0",
                       "%d" % rng.randint(0, 10 ** 18)])


def rate():
    return rng.choice(["%.1f" % rng.uniform(0, 1200), "%d" % rng.randint(0, 2400), "5.", ".5", "0", "", "x1"])


def link_line():
    k = rng.randint(0, 11)
    if k == 0:
        return "Connected to %02x:%02x (on wlan0)" % (rng.randint(0, 255), rng.randint(0, 255))
    if k == 1:
        return rng.choice(["Not connected", "Not connected.", "Not connected..", "Not  connected", "not connected"])
    if k == 2:
        return rng.choice(["SSID:", "SSID: ", "SSID:  ", "SSID:x"]) + rng.choice(["WhoopWhoop", "my phone  net", "", " a "]) + pad()
    if k == 3:
        return rng.choice(["freq: ", "freq:", "freq:  "]) + number() + rng.choice(["", ".0", " MHz", "x"])
    if k == 4:
        return rng.choice(["signal: ", "signal:"]) + number() + rng.choice([" dBm", "", "dBm", ".5"])
    if k in (5, 6):
        return rng.choice(["tx", "rx", "TX"]) + rng.choice([" bitrate: ", " bitrate:", "  bitrate: "]) + rate() + \
            rng.choice([" MBit/s", " MBit/s MCS 7", "MBit/s", " MBit", " mbit/s"])
    if k == 7:
        return rng.choice(["RX: 1 bytes", "TX: 2 bytes (3 packets)", "\tdtim period: 2"])
    if k == 8:
        return ""
    return rng.choice(["Connected to ", "SSID: dup", "freq: 1", "signal: -1", "tx bitrate: 1 MBit/s"])


def wireless_line():
    iface = rng.choice(["wlan0", "wlan1", "eth0", "wlan0x"])
    fields = [rng.choice(["0000", "70.", "-40.", "-256", "200.", "1e2", "nan", "-0.5", "0.9", "x", "", "40", "255."])
              for _ in range(rng.randint(0, 5))]
    sep = rng.choice([" ", "  ", "\t", ""])
    return pad() + iface + rng.choice([":", ": ", " :"]) + sep + sep.join(fields) + pad()


def managed_line():
    return pad() + rng.choice(["Interface wlan%d" % rng.randint(0, 3), "Interface  p2p0 x", "Interface", "Interface ",
                               "type managed", "type  managed", "type P2P-device", "phy#0", "type managed x"]) + pad()


links = wireless = managed = 0
for k in range(5000):
    lines = [link_line() for _ in range(rng.randint(0, 9))]
    text = "".join(pad() + line + rng.choice(["\n", "\n", "\r\n", "\n" + pad()]) for line in lines)
    try:
        want = parse_link(text)
    except ValueError:
        continue
    assert link_of(text) == want, text
    links += 1
for k in range(5000):
    text = "".join(rng.choice([wireless_line, managed_line])() + rng.choice(BREAK) for _ in range(rng.randint(0, 6)))
    if rng.random() < 0.3:
        text = text[:-1] if text else text
    try:
        want = parse_wireless(text, "wlan0")
    except OverflowError:
        continue
    assert m.parseWireless(text, "wlan0") == want, text
    assert m.firstManaged(text) == first_managed(text), text
    assert m.splitLines(text) == text.splitlines(), text
    assert all(m.words(line) == line.split() for line in text.splitlines()), text
    wireless += 1
print("parseLink against parse_link: %d texts, every field the same" % links)
print("parseWireless, firstManaged, splitLines and words against the hand-written ones: %d texts the same" % wireless)


def outcome(f, *args):
    try:
        return repr(f(*args))
    except Exception as e:
        return type(e).__name__


for text, key in (("Connected to x\n\ttx bitrate: 1.2.3 MBit/s\n", "tx_mbps"),
                  ("Connected to x\n\trx bitrate: . MBit/s\n", "rx_mbps"),
                  ("Connected to x\n\tfreq: ٥٢\n", "freq_mhz")):
    print("parse_link %s: python %s, kira %s" % (ascii(text.split("\t")[1].strip()),
                                                outcome(lambda t: parse_link(t)[key], text), link_of(text)[key]))
for text in (" wlan0: 1 2 inf\n", " wlan0: 1 2 ٥\n", " wlan0: 1 2 1_0\n"):
    print("parse_wireless %s: python %s, kira %s" % (ascii(text.strip()), outcome(parse_wireless, text, "wlan0"),
                                                    m.parseWireless(text, "wlan0")))
