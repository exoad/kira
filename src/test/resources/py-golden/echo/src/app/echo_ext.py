# The sidecar of echo.kira: a socketserver.ThreadingTCPServer on a loopback port whose handler
# answers each line with the Kira Fx, and a client that sends lines and reads their answers.
import socket
import socketserver
import threading

servers = []


def serveLines(reply):
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            for line in self.rfile:
                self.wfile.write((reply(line.decode().rstrip("\n")) + "\n").encode())

    server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    servers.append(server)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_address[1]


def talk(port, lines):
    with socket.create_connection(("127.0.0.1", port)) as s:
        f = s.makefile("rw", newline="\n")
        out = []
        for line in lines:
            f.write(line + "\n")
            f.flush()
            out.append(f.readline().rstrip("\n"))
        return out


def stopServing():
    for s in servers:
        s.shutdown()
        s.server_close()
