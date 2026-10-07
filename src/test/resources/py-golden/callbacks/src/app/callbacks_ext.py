# The sidecar of callbacks.kira: it calls the Fx it is handed at once, after its extern returned
# and on a thread of its own.
import threading

kept = []


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


def positive(f):
    v = f()
    if v < 0:
        raise ValueError("%d is not positive" % v)
    return v
