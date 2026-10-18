held = []


def give(f):
    held.append({"a": [1, 2]})
    f((held[0], b"\x03\x04"))


def scribble():
    held[0]["a"].append(3)
    held[0]["b"] = 1
