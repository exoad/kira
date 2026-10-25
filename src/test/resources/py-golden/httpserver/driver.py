#!/usr/bin/env python3
# The httpserver golden (K7): main, then each raw request's reply against a BaseHTTPRequestHandler answering as the
# sidecars did, byte for byte but Date; bodies admitted past the reference's reach, the request's types, a serve a
# signal ends, bind failures, a request waiting for none, a handler ending the process (D76).
#
#   python driver.py <the directory kira --target py --out wrote> [child]
import errno
import importlib.util
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ["no_proxy"] = "*"
out = sys.argv[1]
spec = importlib.util.spec_from_file_location("server_kira", os.path.join(out, "src", "app", "server_kira.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
if sys.argv[2:] == ["child"]:
    s = m.bind("127.0.0.1", 0, True)
    print(s.port(), flush=True)
    m.serveOn(s)
    sys.exit(0)
print("server ->", m._k_run())


# The routes of server.kira as a sidecar's handler answered them (links' bind, powerpage's and powerfeed's serveGet).
def route(method, path, host, body):
    if path == "/text":
        return 200, "text/plain; charset=utf-8", b"hello", []
    if path == "/json":
        return 200, "application/json", b'{"ok": true}', [("Cache-Control", "no-store"), ("Location", "/text")]
    if path == "/missing":
        return 404, "application/json", b"", []
    if path == "/refused":
        return 409, "application/json", b'{"ok": false}', []
    if path == "/drop":
        return 0, "application/json", b"", []
    if path == "/s100":
        return 100, "text/plain", b"low", []
    if path == "/s999":
        return 999, "text/plain", b"high", []
    if path == "/latin":
        return 200, "application/json", b"", [("X-Name", "café")]
    said = "%s %s '%s' %d: %s" % (method, path, host, len(body), body.decode("utf-8", "replace"))
    return 200, "text/plain", said.encode("utf-8", "surrogatepass"), []


def admit(path, n):
    if path != "/open" and (n < 0 or n > 64):
        return 400 if n < 0 else 413, "text/plain", b"length %d" % n, []
    return 0, "", b"", []


class Get(BaseHTTPRequestHandler):
    def do_GET(self):
        self.reply(route("GET", self.path, self.headers.get("Host") or "", b""))

    def reply(self, r):
        status, ctype, body, headers = r
        if status == 0:
            self.close_connection = True
            return
        if status >= 400 and not body:
            self.send_error(status)
            return
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class Post(Get):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        r = admit(self.path, max(-(1 << 62), min(n, 1 << 62)))
        if r[0]:
            self.close_connection = True
            return self.reply(r)
        self.reply(route("POST", self.path, self.headers.get("Host") or "", self.rfile.read(n)))


class Reference(ThreadingHTTPServer):
    allow_reuse_address = os.name != "nt"


def started(handler):
    server = Reference(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_address[1]


def kira(post):
    s = m.bind("127.0.0.1", 0, post)
    threading.Thread(target=m.serveOn, args=(s,), daemon=True).start()
    return s.port()


# One raw exchange: the request, the write side shut when half, then all that comes back until the server closes.
def exchange(port, raw, half=False):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as c:
        c.sendall(raw)
        if half:
            c.shutdown(socket.SHUT_WR)
        got = b""
        while True:
            try:
                piece = c.recv(65536)
            except ConnectionResetError:
                piece = b"" if got.endswith(b"<reset>") else b"<reset>"
            if not piece:
                return re.sub(rb"\r\nDate: [^\r]*", b"", got)
            got += piece


ports = {True: (kira(True), started(Post)), False: (kira(False), started(Get))}
requests = [
    ("a 200", b"GET /text HTTP/1.0\r\nHost: here\r\n\r\n"),
    ("headers in order", b"GET /json HTTP/1.0\r\n\r\n"),
    ("a 404 without a body, Python's page", b"GET /missing HTTP/1.0\r\n\r\n"),
    ("a 409 with one", b"GET /refused HTTP/1.0\r\n\r\n"),
    ("a status of 0, nothing", b"GET /drop HTTP/1.0\r\n\r\n"),
    ("an HTTP/1.1 request, its query and Host", b"GET /echo?a=1&b=2 HTTP/1.1\r\nHost: h:1\r\n\r\n"),
    ("a Host past ASCII", b"GET /echo HTTP/1.0\r\nHost: \xc3\xa9\r\n\r\n"),
    ("a POST's body", b"POST /echo HTTP/1.0\r\nContent-Length: 5\r\n\r\nhello"),
    ("a POST without a length", b"POST /echo HTTP/1.0\r\n\r\n"),
    ("a length admit refuses, unread", b"POST /echo HTTP/1.0\r\nContent-Length: 99999\r\n\r\n"),
    ("an unreadable length, -1", b"POST /echo HTTP/1.0\r\nContent-Length: abc\r\n\r\n"),
    ("a negative one", b"POST /echo HTTP/1.0\r\nContent-Length: -5\r\n\r\n"),
    ("one past Int64, clamped", b"POST /echo HTTP/1.0\r\nContent-Length: 1" + b"0" * 30 + b"\r\n\r\n"),
    ("int()'s length, spaces and _", b"POST /echo HTTP/1.0\r\nContent-Length:  1_2 \r\n\r\nhello world!"),
    ("a POST whose answer is 0", b"POST /drop HTTP/1.0\r\nContent-Length: 2\r\n\r\nhi"),
    ("PUT, Python's 501", b"PUT /echo HTTP/1.0\r\n\r\n"),
    ("HEAD too", b"HEAD /text HTTP/1.0\r\n\r\n"),
    ("a bad request line, Python's 400", b"garbage\r\n\r\n"),
    ("status 100, the lowest", b"GET /s100 HTTP/1.0\r\n\r\n"),
    ("status 999, the highest", b"GET /s999 HTTP/1.0\r\n\r\n"),
    ("a header in latin-1", b"GET /latin HTTP/1.0\r\n\r\n"),
]
for post in (True, False):
    k, r = ports[post]
    same = [what for what, raw in requests if exchange(k, raw) == exchange(r, raw)]
    print("GET and POST" if post else "GET only", "answers as the handler does:", len(same), "of", len(requests), [w for w, _ in requests if w not in same])
print(exchange(ports[True][0], requests[0][1]).split(b"\r\n")[0], exchange(ports[False][0], requests[7][1]).split(b"\r\n")[0])
cut = b"POST /echo HTTP/1.0\r\nContent-Length: 10\r\n\r\nabcd"
print("a body the client stops short:", exchange(ports[True][0], cut, True) == exchange(ports[True][1], cut, True), exchange(ports[True][0], cut, True).split(b"\r\n\r\n")[1])
# The reference's rfile.read(n) raised MemoryError for the first and read to the end for the others.
for what, length in [("a length past memory", b"%d" % (1 << 62)), ("an unreadable one", b"abc"), ("a negative one", b"-5")]:
    raw = b"POST /open HTTP/1.0\r\nContent-Length: " + length + b"\r\n\r\nhello"
    print("%s, admitted: %r" % (what, exchange(ports[True][0], raw, True).split(b"\r\n\r\n")[-1]))

# The request a handler gets, its body a bytearray for a GET and a POST alike.
seen = []


def spy(r):
    seen.append(type(r.body).__name__)
    return m.route(r)


spied = m._k_httpbind("127.0.0.1", 0, spy, m.admit)
threading.Thread(target=spied.serve, daemon=True).start()
exchange(spied.port(), b"GET /echo HTTP/1.0\r\n\r\n")
exchange(spied.port(), b"POST /echo HTTP/1.0\r\nContent-Length: 2\r\n\r\nhi")
spied.shutdown()
print("a request's body:", seen)

before = signal.getsignal(signal.SIGINT)
t = threading.Thread(target=lambda: (time.sleep(0.3), signal.raise_signal(signal.SIGINT)), daemon=True)
t.start()
try:
    print(m.serveOnSignal().replace(str(InterruptedError(errno.EINTR, os.strerror(errno.EINTR))), "EINTR's text"))
finally:
    t.join()
    signal.signal(signal.SIGINT, before)

took = {}


def timed(path):
    begin = time.monotonic()
    exchange(ports[True][0], b"GET " + path + b" HTTP/1.0\r\n\r\n")
    took[path] = time.monotonic() - begin


slow = threading.Thread(target=timed, args=(b"/slow",))
slow.start()
time.sleep(0.1)
timed(b"/text")
slow.join()
print("a request waits for no other:", took[b"/text"] < 0.5 < took[b"/slow"])


def refused(host, port):
    try:
        Reference((host, port), Get).server_close()
        return None
    except (OSError, ValueError, OverflowError, TypeError) as e:
        return str(e)


for what, host, port in [("a port in use", "127.0.0.1", ports[True][0]), ("a port past 65535", "127.0.0.1", 70000), ("a negative one", "127.0.0.1", -1),
                         ("a host no name", "256.0.0.1", 0), ("a NUL in the host", "a\x00b", 0)]:
    s = m.bind(host, port, True)
    print("%s: none %s, httpError as the bind's own %s" % (what, s is None, m.why() == refused(host, port)))
answer = m.route(m._k_HttpRequest("GET", "/json", "", bytearray()))
print("of the types:", type(answer).__name__, type(answer.status).__name__, type(answer.body).__name__, type(answer.headers).__name__, type(m.bind("127.0.0.1", 0, False).port()).__name__)

windows = os.name == "nt"
ends = [(b"/throw", "70"), (b"/panic", "aborted"), (b"/exit", "9")]
for path in (b"/length", b"/ctype", b"/break", b"/lf", b"/ctypebreak", b"/notlatin", b"/s1000", b"/s99"):
    ends.append((path, "aborted"))
for path, want in ends:
    child = subprocess.Popen([sys.executable, __file__, out, "child"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(os.environ, PYTHONUNBUFFERED="1"))
    try:
        port = int(child.stdout.readline())
        got = exchange(port, b"GET " + path + b" HTTP/1.0\r\n\r\n")
        err = child.communicate(timeout=30)[1].decode("utf-8", "replace")
        code = child.returncode
    except (OSError, ValueError, subprocess.TimeoutExpired) as e:
        got, err, code = type(e).__name__, "", "still serving"
    finally:
        child.kill()
        child.wait()
    shown = "aborted" if code in ((3,) if windows else (-6, 134)) else str(code)
    said = [w for w in ("from the handler", "a handler's panic", "sets content-length", "sets Content-Type", "holds a line break", "past latin-1", "is no HTTP status") if w in err]
    print("%s ends the process: %s, no reply %s, %s" % (path.decode(), shown if shown != want else "as it should (%s)" % want, got if got not in (b"", b"<reset>") else True, said))
