# The sidecar of callbacks.kira: it calls the Fx it is handed at once, after its extern returned,
# on a thread of its own, and inside a try that catches every Exception.
import threading

kept = []
held = []


def each(xs, f):
    return sum(f(x) for x in xs)


def callWith(how, f):
    if how == "ok":
        return f(1, "a")
    if how == "none":
        return f(None, "a")
    if how == "bool":
        return f(True, "a")
    return f(1)


def bytesOf(f):
    r = f()
    return type(r).__name__ + " " + repr(r)


def render(path, get):
    r = get(path)
    return "%d %s %r" % (r.status, type(r.body).__name__, bytes(r.body))


def keep(f):
    kept[:] = [f]


def callKept(x):
    return kept[0](x)


def onThread(f, x):
    out = []
    t = threading.Thread(target=lambda: out.append(f(x)))
    t.start()
    t.join()
    return out[0] if out else -1


def swallow(f):
    try:
        return "returned %d" % f()
    except Exception as e:
        return "swallowed " + type(e).__name__


def positive(f):
    v = f()
    if v < 0:
        raise ValueError("%d is not positive" % v)
    return v


def giveJson(f):
    held.append({"a": 1})
    f(held[0])


def scribble():
    held[0]["b"] = {1, 2}
    held[0][3] = "an int key"


def takeJson(f):
    j = f()
    j[9] = {1}
    return "%s of %d keys" % (type(j).__name__, len(j))


def kinds(f, g, h):
    return " ".join(type(x).__name__ for x in (f(), g()[0], h()[0]))


def pair(f, g):
    return f(1) + g(None)
