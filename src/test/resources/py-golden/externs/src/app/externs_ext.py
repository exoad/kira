# The sidecar of externs.kira: it reports what it is given and hands back what driver.py names.
held = {}
shared = []


def show(v):
    return type(v).__name__ + " " + repr(v)


def seenScalars(a, b, c, d, e, f, g):
    return " | ".join(show(x) for x in (a, b, c, d, e, f, g))


def seenValues(xs, b, m, t, some, absent):
    return " | ".join(show(x) for x in (xs, b, m, t, some, absent))


def seenObjects(p, b, j):
    return "%s %d %d | %s %r | %s" % (type(p).__name__, p.x, p.y, type(b).__name__, b.items, show(j))


def keep(xs, p, b):
    held["xs"], held["p"], held["box"] = xs, p, b


def kept():
    return "xs %r, p.x %d, box %r" % (held["xs"], held["p"].x, held["box"].items)


def sharedList():
    shared[:] = ["a", "b"]
    return shared


def touchShared():
    shared.append("c")


def fillInto(buf):
    buf[0:3] = b"abc"
    held["view"] = buf
    return 3


def tryWrite(data):
    try:
        data[0] = 1
    except TypeError as e:
        return "%s, %d bytes" % (e, len(data))
    return "written"


def useKept():
    try:
        held["view"][0]
    except ValueError as e:
        return str(e)
    return "still usable"


def nested(n):
    v = []
    for _ in range(n - 1):
        v = [v]
    return v


GIVE = {
    "none": None, "true": True, "int": 7, "2^31": 2 ** 31, "1.5": 1.5, "-1": -1, "255": 255,
    "2^64-1": 2 ** 64 - 1, "2^53": 2 ** 53, "2^53+1": 2 ** 53 + 1, "str": "s", "bytes": b"xy",
    "bytearray": bytearray(b"z"), "tuple": ("a",), "list": ["a", "b"], "holey": ["a", None],
    "ints": [1, 2], "map": {"a": 1}, "intkey": {1: 1}, "pair": ("a", 1), "pairlist": ["a", 1],
    "3": 3, "json": {"a": [1, 2.5, None, True, "s"]}, "set": {1}, "2^64": [2 ** 64],
    "512 deep": nested(512), "513 deep": nested(513), "intkeyjson": {1: "a"},
}


def give(k):
    return GIVE[k]


giveInt32 = giveUInt8 = giveSize = giveFloat = giveBool = giveStr = giveMaybe = give
giveList = giveBytes = giveMap = giveTuple = giveLevel = giveJson = giveVoid = give
