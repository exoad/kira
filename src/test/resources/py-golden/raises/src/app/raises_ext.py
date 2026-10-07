# The sidecar of raises.kira: each function raises what driver.py and raises.kira ask for.
import json
import sys


def readText(path):
    with open(path) as f:
        return f.read()


def lookup(key):
    return {}[key]


def parse(text):
    if not text:
        raise ValueError()
    return int(text)


def fail(kind):
    if kind == "timeout":
        raise TimeoutError("slow")
    if kind == "gone":
        raise FileNotFoundError(2, "gone")
    if kind == "denied":
        raise PermissionError(13, "denied")


def decode(text):
    return json.loads(text)


def escape(kind):
    if kind == "value":
        raise ValueError("not listed")
    if kind == "interrupt":
        raise KeyboardInterrupt
    if kind == "exit":
        sys.exit(3)


def wrongResult():
    return None
