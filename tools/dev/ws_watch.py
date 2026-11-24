#!/usr/bin/env python3
"""Cliente WebSocket mínimo (stdlib) para observar os eventos do Papo como outro usuário.
Uso: ws_watch.py <host:porta> <usuario> <senha> <arquivo_de_saida> [segundos]"""
import base64, json, os, socket, struct, sys, time, urllib.request, http.cookiejar

host, user, password, out = sys.argv[1:5]
seconds = float(sys.argv[5]) if len(sys.argv) > 5 else 600
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
req = urllib.request.Request(f"http://{host}/auth/login", json.dumps({"username": user, "password": password}).encode(), {"Content-Type": "application/json"})
me = json.load(opener.open(req))["user"]["id"]
cookie = "; ".join(f"{c.name}={c.value}" for c in jar)
h, p = host.split(":")
s = socket.create_connection((h, int(p)))
key = base64.b64encode(os.urandom(16)).decode()
s.sendall((f"GET /ws HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nCookie: {cookie}\r\n\r\n").encode())
buf = b""
while b"\r\n\r\n" not in buf:
    buf += s.recv(4096)
head, buf = buf.split(b"\r\n\r\n", 1)
assert b"101" in head.split(b"\r\n")[0], head

def send(op, data=b""):
    mask = os.urandom(4)
    n = len(data)
    hdr = bytes([0x80 | op]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n))
    s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

def need(n):
    global buf
    while len(buf) < n:
        chunk = s.recv(65536)
        if not chunk: raise EOFError
        buf += chunk
    data, buf = buf[:n], buf[n:]
    return data

log = open(out, "w")
log.write(json.dumps({"me": me}) + "\n"); log.flush()
end = time.time() + seconds
s.settimeout(1)
frag = b""
while time.time() < end:
    try:
        b0, b1 = need(2)
    except socket.timeout:
        continue
    except EOFError:
        break
    s.settimeout(10)
    op, n = b0 & 0x0F, b1 & 0x7F
    if n == 126: n = struct.unpack(">H", need(2))[0]
    elif n == 127: n = struct.unpack(">Q", need(8))[0]
    data = need(n)
    s.settimeout(1)
    if op == 9: send(10, data); continue
    if op == 8: break
    if op in (0, 1):
        frag += data
        if b0 & 0x80:
            try:
                ev = json.loads(frag)
                if ev.get("type") not in ("presence_sync",):
                    if "sdp" in ev: ev["sdp"] = "<%d bytes>" % len(ev["sdp"])
                    log.write(json.dumps(ev, ensure_ascii=False)[:600] + "\n"); log.flush()
            except ValueError:
                pass
            frag = b""
