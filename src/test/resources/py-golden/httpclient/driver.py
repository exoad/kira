#!/usr/bin/env python3
# The httpclient golden (K7): main, then each reply, HTTPError, cut body, bad reply, timeout and bad URL this server's
# bytes give, against what a sidecar's urlopen and read gave on this interpreter (critique 9), and the value types.
#
#   python driver.py <the directory kira --target py --out wrote>
import http.client
import importlib.util
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

os.environ["no_proxy"] = "*"
spec = importlib.util.spec_from_file_location("client_kira", os.path.join(sys.argv[1], "src", "app", "client_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
print("client ->", m._k_run())

ANSWERS = {
    "/ok": b"HTTP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nhello",
    "/bytes": b"HTTP/1.0 200 OK\r\nContent-Length: 256\r\n\r\n" + bytes(range(256)),
    "/missing": b"HTTP/1.0 404 Not Found\r\nContent-Length: 4\r\n\r\nnope",
    "/teapot": b"HTTP/1.0 418 I'm a teapot\r\nContent-Length: 15\r\n\r\nshort and stout",
    "/unreadable": b"HTTP/1.0 500 Oops\r\nContent-Length: 100\r\n\r\n0123456789",
    "/short": b"HTTP/1.0 200 OK\r\nContent-Length: 100\r\n\r\n0123456789",
    "/drop": b"",
    "/garbage": b"HELLO\r\n\r\n",
    "/redirect": b"HTTP/1.0 302 Found\r\nLocation: /ok\r\nContent-Length: 0\r\n\r\n",
    "/unlimited": b"HTTP/1.0 200 OK\r\n\r\nall of it",
    "/nocontent": b"HTTP/1.0 204 No Content\r\n\r\n",
    "/notmodified": b"HTTP/1.0 304 Not Modified\r\n\r\n",
}


# One request read whole (its body by Content-Length), then the answer its path names; /slow answers after 1.5 s.
def serve(c):
    try:
        answer(c)
    except OSError:
        pass
    finally:
        c.close()


def answer(c):
    data = b""
    while b"\r\n\r\n" not in data:
        piece = c.recv(65536)
        if not piece:
            return
        data += piece
    head, _, body = data.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    fields = dict((k.lower(), v.strip()) for k, _, v in (h.partition(":") for h in lines[1:]))
    while len(body) < int(fields.get("content-length", "0")):
        body += c.recv(65536)
    method, path = lines[0].split(" ")[:2]
    if path == "/slow":
        time.sleep(1.5)
        c.sendall(ANSWERS["/ok"])
    elif path == "/echo":
        said = ("%s %s %s " % (method, fields.get("content-type"), fields.get("content-length"))).encode() + body
        c.sendall(b"HTTP/1.0 201 Created\r\nContent-Length: %d\r\n\r\n" % len(said) + said)
    else:
        c.sendall(ANSWERS[path])


listener = socket.socket()
listener.bind(("127.0.0.1", 0))
listener.listen(32)


def accepting():
    while True:
        threading.Thread(target=serve, args=(listener.accept()[0],), daemon=True).start()


threading.Thread(target=accepting, daemon=True).start()
base = "http://127.0.0.1:%d" % listener.getsockname()[1]
closed = socket.socket()
closed.bind(("127.0.0.1", 0))
refused = "http://127.0.0.1:%d/ok" % closed.getsockname()[1]
closed.close()


# What a sidecar did (powerpage's httpText, links_test's _call): urlopen and read, an HTTPError's code and its body.
def sidecar(url, data, ctype, ms):
    try:
        req = urllib.request.Request(url, data, {} if ctype is None else {"Content-Type": ctype})
        try:
            with urllib.request.urlopen(req, timeout=ms / 1000) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.read()
    except (OSError, ValueError, http.client.HTTPException) as e:
        return None, str(e)


def kira(url, data, ctype, ms):
    r = m.get(url, ms) if data is None else m.post(url, bytearray(data), ctype, ms)
    return (None, m.why()) if r is None else (r.status, bytes(r.body))


cases = [
    ("a 200", "/ok", None, None, 2000),
    ("its bytes", "/bytes", None, None, 2000),
    ("a 404, its body", "/missing", None, None, 2000),
    ("an HTTPError's body cut short", "/unreadable", None, None, 2000),
    ("a 200's body cut short", "/short", None, None, 2000),
    ("a connection closed unanswered", "/drop", None, None, 2000),
    ("no HTTP reply", "/garbage", None, None, 2000),
    ("a reply slower than the timeout", "/slow", None, None, 300),
    ("a redirect, followed", "/redirect", None, None, 2000),
    ("a body to the connection's end", "/unlimited", None, None, 2000),
    ("a 204", "/nocontent", None, None, 2000),
    ("a 304", "/notmodified", None, None, 2000),
    ("a POST", "/echo", b"hello", "text/plain", 2000),
    ("an empty POST", "/echo", b"", "application/json", 2000),
    ("a POST's 418", "/teapot", b"{}", "application/json", 2000),
    ("a Content-Type with a line break", "/echo", b"x", "text/plain\nX-Injected: 1", 2000),
    ("a path past ASCII", "/é", None, None, 2000),
]
for what, path, data, ctype, ms in cases:
    got, want = kira(base + path, data, ctype, ms), sidecar(base + path, data, ctype, ms)
    shown = "none: %r" % got[1] if got[0] is None else "%d %r" % (got[0], got[1][:24])
    print("%s: %s, %s" % (what, "as a sidecar's" if got == want else "DIFFERS from %r" % (want,), shown))
got, want = kira(refused, None, None, 5000), sidecar(refused, None, None, 5000)
print("a closed port: none %s, as a sidecar's %s" % (got[0] is None, got == want))
print("no limit at 0, -1 and 2^62 ms:", [kira(base + "/ok", None, None, ms) for ms in (0, -1, 1 << 62)] == [(200, b"hello")] * 3)
m.get(base + "/drop", 2000)
cleared = m.get(base + "/ok", 2000) is not None and m.why() == ""
print("a call that works clears the reason:", cleared)
m.get(base + "/ok", 2000)
seen = []
t = threading.Thread(target=lambda: seen.append((m.get(base + "/drop", 2000), m.why() != "")))
t.start()
t.join()
print("a reason is its thread's:", seen, repr(m.why()))
r = m.get(base + "/bytes", 2000)
print("of the types:", type(r).__name__, type(r.status).__name__, type(r.body).__name__, m.get("nope", 1), type(m.why()).__name__)
texts = [b"", b"{}", b"\xef\xbb\xbf{}", b"\xff\xfe{\x00}\x00", b"\xfe\xff\x00{\x00}", b"{\x00\x00\x00}\x00\x00\x00", b"\x00\x00\x00{",
         b"\xed\xa0\x80", b"\"\xff\"", b"caf\xc3\xa9", b"\x00{"]


def decoded(b):
    try:
        return b.decode(json.detect_encoding(b), "surrogatepass")
    except ValueError as e:
        return str(e)


print("jsonText decodes as json.loads would:", sum((m.text(bytearray(b)) or m.why()) == decoded(b) for b in texts), "of", len(texts))
targets = ["/", "/power?s=60", "/a/b;p?q=1#f", "http://h:1/x?y", "//h/p", "", "?only", "/%7e?x=%20", "http://[::1]:80/p?q", "http://[x/", "/é?é=1"]


def split(t):
    try:
        u = urllib.parse.urlparse(t)
        return u.path, u.query
    except ValueError as e:
        return str(e), str(e)


print("urlPath and urlQuery split as urlparse:", sum((m.path(t) or m.why(), m.query(t) or m.why()) == split(t) for t in targets), "of", len(targets))
queries = [("a=1&a=2", "a"), ("a=&b=2", "a"), ("a+b=c+d", "a b"), ("a=%41%zz", "a"), ("a=1;b=2", "b"), ("x", "x"), ("", "a"), ("k=é", "k")]
parsed = urllib.parse.parse_qs
print("queryFirst is parse_qs's first:", sum(m.first(q, k) == (parsed(q).get(k) or [None])[0] for q, k in queries), "of", len(queries))
