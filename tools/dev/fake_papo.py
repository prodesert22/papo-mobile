#!/usr/bin/env python3
"""Backend Papo falso, só para exercitar o app no emulador.

Implementa o subconjunto de auth/servidor da OpenAPI com sessão por cookie
`Auth`. Não substitui o backend real: serve para ver as telas funcionando.

Uso: fake_papo.py <porta> [senha_do_servidor]
"""
import base64
import hashlib
import json
import re
import struct
import sys
import threading
import unicodedata
import uuid
import zlib
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from urllib.parse import parse_qs, urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
SERVER_PASSWORD = sys.argv[2] if len(sys.argv) > 2 else None
NAME = f"Boteco {PORT}" if SERVER_PASSWORD is None else f"Clube Fechado {PORT}"

USERS = {}      # username -> {id, password}
SESSIONS = {}   # token -> {user, id, created}


CHANNELS = [
    {"id": "c0000000-0000-4000-8000-000000000001", "name": "Geral", "type": "category", "position": 1},
    {"id": "c0000000-0000-4000-8000-000000000002", "name": "papo", "type": "text", "position": 2, "topic": "Conversa fiada",
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
    {"id": "c0000000-0000-4000-8000-000000000003", "name": "avisos", "type": "text", "position": 3,
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
    {"id": "c0000000-0000-4000-8000-000000000004", "name": "Sala de voz", "type": "voice", "position": 4,
     "parent_id": "c0000000-0000-4000-8000-000000000001"},
]
PAPO = CHANNELS[1]["id"]
MESSAGES = {}    # channel_id -> [message]
READS = {}       # (user_id, channel_id) -> message_id
DMS = {}         # dm_id -> (user_a, user_b)
PINS = {}        # channel_id -> [message_id]
FILES = {}       # attachment_id -> (mime, bytes)
SOCKETS = []     # [(user_id, wfile, lock)]
LOCK = threading.RLock()
SEQ = [0]


def png(r, g, b, w=96, h=64):
    raw = b"".join(b"\x00" + bytes([r, g, b]) * w for _ in range(h))
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def stamp():
    SEQ[0] += 1
    return (datetime(2026, 11, 24, 12, 0, 0, tzinfo=timezone.utc) + timedelta(seconds=SEQ[0])).isoformat().replace("+00:00", "Z")


def uid(name):
    return USERS[name]["id"]


def name_of(user_id):
    return next((n for n, u in USERS.items() if u["id"] == user_id), None)


def new_message(channel_id, author_id, content, reply_to=None, attachments=None, created_at=None, external_author=None):
    m = {"id": str(uuid.uuid4()), "channel_id": channel_id, "author_id": author_id, "content": content,
         "created_at": created_at or stamp(), "edited_at": None, "reply_to": reply_to,
         "webhook_id": str(uuid.uuid4()) if external_author else None, "external_author": external_author,
         "attachments": attachments or [], "embeds": [], "reactions": {}, }
    MESSAGES.setdefault(channel_id, []).append(m)
    return m


def fold(text):
    return "".join(c for c in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(c) != "Mn")


def search_messages(data, query, me):
    """POST /search como na OpenAPI. Devolve o corpo da resposta, ou None se o pedido é inválido.

    O backend real usa busca textual do Postgres em português (com radicais);
    aqui um termo casa quando aparece em qualquer lugar do texto, sem acento.
    """
    text = (data.get("text") or "").strip()
    author, channel_id, mention = data.get("author") or "", data.get("channel_id") or "", data.get("mention") or ""
    has, order, attached = data.get("has") or "", data.get("order") or "desc", data.get("contains_attachment")
    start, end = data.get("date_start") or "", data.get("date_end") or ""
    since, last_id = query.get("since", [""])[0], query.get("last_id", [""])[0]
    if not (text or author or channel_id or mention or has or start or end or attached is not None):
        return None
    if has not in ("", "link") or order not in ("asc", "desc") or bool(since) != bool(last_id):
        return None
    try:
        for value in (channel_id, mention):
            if value:
                uuid.UUID(value)
        first = datetime.strptime(start, "%Y-%m-%d").replace(tzinfo=timezone.utc) if start else None
        # date_end é inclusivo: o limite é o começo do dia seguinte, em UTC.
        limit = datetime.strptime(end, "%Y-%m-%d").replace(tzinfo=timezone.utc) + timedelta(days=1) if end else None
    except ValueError:
        return None
    if first and limit and first >= limit:
        return None

    # Sem channel_id, as conversas diretas ficam de fora; com ele, uma DM só entra se o usuário participa dela.
    if channel_id:
        channels = [channel_id] if channel_id not in DMS or me in DMS[channel_id] else []
    else:
        channels = [c["id"] for c in CHANNELS]
    names = {c["id"]: c["name"] for c in CHANNELS}
    terms = [fold(t) for t in text.split()]
    token = "@mention(<@%s>)" % mention.lower()
    found = []
    for cid in channels:
        for m in MESSAGES.get(cid, []):
            content = m["content"] or ""
            when = datetime.fromisoformat(m["created_at"].replace("Z", "+00:00"))
            hits = sum(1 for t in terms if t in fold(content))
            if (terms and not hits) or (author and m["author_id"] != author) or (mention and token not in content.lower()):
                continue
            if (has and not re.search(r"https?://", content, re.I)) or (first and when < first) or (limit and when >= limit):
                continue
            if attached is not None and bool(m["attachments"]) != attached:
                continue
            external = m["external_author"]
            found.append({"type": "message", "id": m["id"], "content": m["content"], "channel_id": cid,
                          "channel_name": names.get(cid, ""), "author_id": m["author_id"],
                          "author_username": name_of(m["author_id"]) or (external or {}).get("name"),
                          "webhook_id": m["webhook_id"], "external_author": external, "created_at": m["created_at"],
                          **({"score": round(hits / len(terms), 3)} if terms else {})})
    desc = order == "desc"
    found.sort(key=lambda r: (r["created_at"], r["id"]), reverse=desc)
    if since:
        found = [r for r in found if ((r["created_at"], r["id"]) < (since, last_id) if desc else (r["created_at"], r["id"]) > (since, last_id))]
    return {"results": found[:100], "has_more": len(found) > 100}


def view(m, user_id=None):
    out = {k: v for k, v in m.items() if k != "reactions"}
    out["reactions"] = [{"emoji_id": None, "unicode": e, "count": len(us)} for e, us in m["reactions"].items() if us] or None
    out["user_reactions"] = [{"id": str(uuid.uuid4()), "emoji_id": None, "unicode": e} for e, us in m["reactions"].items() if user_id in us]
    return out


def audience(channel_id):
    return set(DMS[channel_id]) if channel_id in DMS else None  # None = todos


def frame(text):
    data = text.encode()
    head = b"\x81"
    if len(data) < 126:
        head += bytes([len(data)])
    elif len(data) < 65536:
        head += b"\x7e" + struct.pack(">H", len(data))
    else:
        head += b"\x7f" + struct.pack(">Q", len(data))
    return head + data


def emit(event, only=None):
    payload = frame(json.dumps(event))
    with LOCK:
        targets = [s for s in SOCKETS if only is None or s[0] in only]
    for user_id, wfile, lock in targets:
        try:
            with lock:
                wfile.write(payload)
                wfile.flush()
        except OSError:
            pass


def last_message(channel_id):
    ms = MESSAGES.get(channel_id) or []
    if not ms:
        return None
    m = ms[-1]
    return {"id": m["id"], "content": m["content"], "author_id": m["author_id"], "author_username": name_of(m["author_id"]), "created_at": m["created_at"]}


def summary(name):
    return {"id": uid(name), "username": name, "nickname": USERS[name].get("nickname"), "banned": False,
            "created_at": USERS[name]["created"], "roles": USERS[name].get("roles", [])}


def dm_view(dm_id, user_id):
    other = next(u for u in DMS[dm_id] if u != user_id) if DMS[dm_id][0] != DMS[dm_id][1] else user_id
    ms = MESSAGES.get(dm_id) or []
    read = READS.get((user_id, dm_id))
    ids = [m["id"] for m in ms]
    start = ids.index(read) + 1 if read in ids else 0
    unread = sum(1 for m in ms[start:] if m["author_id"] != user_id)
    return {"id": dm_id, "user": summary(name_of(other)), "created_at": "2026-11-24T11:00:00Z",
            "last_message": last_message(dm_id), "last_read_message": read, "unread_count": unread}


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def cookie(self):
        for part in self.headers.get("Cookie", "").split(";"):
            name, _, value = part.strip().partition("=")
            if name == "Auth":
                return value
        return None

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def send(self, status, payload=None, cookie=None):
        data = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("X-Request-ID", str(uuid.uuid4()))
        if payload is not None:
            kind = "application/problem+json" if status >= 400 else "application/json"
            self.send_header("Content-Type", kind)
        if cookie is not None:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def problem(self, status, title, detail):
        self.send(status, {"type": "https://papo.com/errors/x", "title": title, "status": status, "detail": detail, "instance": self.path})

    def session(self):
        s = SESSIONS.get(self.cookie())
        if s is None:
            self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        return s

    def raw(self, status, mime, data):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def websocket(self):
        s = SESSIONS.get(self.cookie())
        if s is None:
            return self.problem(401, "Não autorizado", "Token ausente, inválido ou expirado")
        me = uid(s["user"])
        accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        entry = (me, self.wfile, threading.Lock())
        with LOCK:
            SOCKETS.append(entry)
            online = {s[0] for s in SOCKETS}
        members = [{"user_id": u["id"], "status": "online" if u["id"] in online else "offline", "status_message": None, "user_voice": []} for u in USERS.values()]
        emit({"type": "presence_sync", "members": members}, only={me})
        emit({"type": "presence_update", "user_id": me, "status": "online", "status_message": None, "typing": None, "nickname": None})
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) < 2:
                    break
                opcode, length = head[0] & 0x0F, head[1] & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self.rfile.read(8))[0]
                mask = self.rfile.read(4)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(self.rfile.read(length)))
                if opcode == 8:
                    break
                if opcode != 1:
                    continue
                msg = json.loads(data)
                sys.stderr.write("WS %s %s\n" % (s["user"], msg.get("type")))
                if msg.get("type") == "heartbeat":
                    emit({"type": "heartbeat_ack"}, only={me})
                elif msg.get("type") == "typing":
                    emit({"type": "typing", "channel_id": msg["channel_id"], "user_id": me, "is_typing": True}, only=audience(msg["channel_id"]))
        except (OSError, ValueError):
            pass
        finally:
            with LOCK:
                if entry in SOCKETS:
                    SOCKETS.remove(entry)
                gone = me not in {s[0] for s in SOCKETS}
            if gone:
                emit({"type": "presence_update", "user_id": me, "status": "offline", "status_message": None, "typing": None, "nickname": None})
            self.close_connection = True

    def chat_get(self, url, query):
        path = url.path
        if path == "/ws":
            self.websocket()
            return True
        m = re.fullmatch(r"/users/([^/]+)/avatar", path)
        if m:
            self.problem(404, "Não encontrado", "Sem avatar")
            return True
        m = re.fullmatch(r"/attachments/([^/]+)(/thumbnail)?", path)
        if m and self.cookie() in SESSIONS and m.group(1) in FILES:
            self.raw(200, *FILES[m.group(1)])
            return True
        s = SESSIONS.get(self.cookie())
        if s is None:
            return False
        me = uid(s["user"])
        if path == "/channels":
            out = []
            for c in CHANNELS:
                item = dict(c)
                item["last_message"] = last_message(c["id"])
                item["last_read_message"] = READS.get((me, c["id"]))
                item["notification_settings"] = "only_mentions"
                out.append(item)
            self.send(200, {"channels": out})
        elif path == "/dms":
            self.send(200, {"dms": [dm_view(d, me) for d, pair in DMS.items() if me in pair]})
        elif path == "/users":
            self.send(200, {"users": [summary(n) for n in USERS], "has_more": False})
        elif path == "/emojis":
            self.send(200, {"emojis": [], "has_more": False})
        elif re.fullmatch(r"/channels/[^/]+/pinned", path):
            cid = path.split("/")[2]
            byid = {x["id"]: x for x in MESSAGES.get(cid, [])}
            self.send(200, {"channel_id": cid, "pinned": [view(byid[i], me) for i in PINS.get(cid, []) if i in byid]})
        elif re.fullmatch(r"/channels/[^/]+/messages", path):
            cid = path.split("/")[2]
            asc = query.get("order", ["desc"])[0] == "asc"
            since, last_id = query.get("since", [None])[0], query.get("last_id", [None])[0]
            ms = sorted(MESSAGES.get(cid, []), key=lambda x: (x["created_at"], x["id"]))
            # Cursor (created_at, id), como no backend: o que vem depois dele (asc) ou antes dele (desc).
            if since and last_id:
                cursor = (since, last_id)
                ms = [x for x in ms if ((x["created_at"], x["id"]) > cursor if asc else (x["created_at"], x["id"]) < cursor)]
            if not asc:
                ms.reverse()
            limit = int(query.get("limit", ["30"])[0])
            self.send(200, {"channel_id": cid, "messages": [view(x, me) for x in ms[:limit]], "has_more": len(ms) > limit})
        else:
            return False
        return True

    def find(self, message_id):
        for cid, ms in MESSAGES.items():
            for m in ms:
                if m["id"] == message_id:
                    return m
        return None

    def chat_write(self, method, url, me):
        path = url.path
        if method == "POST" and path == "/messages":
            ctype = self.headers.get("Content-Type", "")
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length)
            parsed = BytesParser().parsebytes(b"Content-Type: " + ctype.encode() + b"\r\n\r\n" + body)
            fields, attachments = {}, []
            for part in parsed.get_payload():
                name = part.get_param("name", header="content-disposition")
                data = part.get_payload(decode=True)
                if name == "attachments":
                    aid = str(uuid.uuid4())
                    mime = part.get_content_type()
                    FILES[aid] = (mime, data)
                    image = mime.startswith("image/")
                    attachments.append({"id": aid, "mime_type": mime, "original_file_name": part.get_filename() or "arquivo", "size_bytes": len(data),
                                        "thumbnail_id": aid if image else None, "thumbnail_width": 96 if image else None,
                                        "thumbnail_height": 64 if image else None, "moderation_status": "clean"})
                else:
                    fields[name] = data.decode()
            m = new_message(fields["channel_id"], me, fields.get("content", ""), fields.get("reply_to"), attachments)
            READS[(me, m["channel_id"])] = m["id"]
            event = {k: m[k] for k in ("id", "channel_id", "author_id", "content", "reply_to", "attachments", "created_at")}
            emit(dict(event, type="message"), only=audience(m["channel_id"]))
            if m["channel_id"] in DMS:
                for u in set(DMS[m["channel_id"]]):
                    emit({"type": "dm_update", "dm": dm_view(m["channel_id"], u)}, only={u})
            return self.send(201, view(m, me))
        data = self.body()
        if method == "POST" and path == "/search":
            found = search_messages(data, parse_qs(url.query), me)
            if found is None:
                return self.problem(400, "Parâmetro inválido", "pelo menos 1 filtro é obrigatório; since e last_id devem ser informados juntos")
            return self.send(200, found)
        if method == "POST" and path == "/dms":
            other = data["user_id"]
            dm_id = next((d for d, pair in DMS.items() if set(pair) == {me, other}), None)
            if dm_id is None:
                dm_id = str(uuid.uuid4())
                DMS[dm_id] = (me, other)
            return self.send(200, dm_view(dm_id, me))
        m = re.fullmatch(r"/messages/([^/]+)", path)
        if m:
            msg = self.find(m.group(1))
            if msg is None:
                return self.problem(404, "Não encontrado", "Mensagem inexistente")
            if msg["author_id"] != me:
                return self.problem(403, "Proibido", "Só o autor pode alterar a mensagem")
            if method == "PUT":
                msg["content"], msg["edited_at"] = data["content"], stamp()
                emit({"type": "message_edit", "id": msg["id"], "channel_id": msg["channel_id"], "content": msg["content"], "edited_at": msg["edited_at"]}, only=audience(msg["channel_id"]))
                return self.send(200, view(msg, me))
            MESSAGES[msg["channel_id"]].remove(msg)
            emit({"type": "message_delete", "id": msg["id"], "channel_id": msg["channel_id"]}, only=audience(msg["channel_id"]))
            return self.send(204)
        m = re.fullmatch(r"/channels/([^/]+)/read", path)
        if m:
            READS[(me, m.group(1))] = data["message_id"]
            return self.send(204)
        m = re.fullmatch(r"/channels/([^/]+)/messages/([^/]+)/pin", path)
        if m:
            pins = PINS.setdefault(m.group(1), [])
            pinned = method == "POST"
            if pinned and m.group(2) not in pins:
                pins.append(m.group(2))
            if not pinned and m.group(2) in pins:
                pins.remove(m.group(2))
            emit({"type": "message_pin", "message_id": m.group(2), "is_pinned": pinned})
            return self.send(200 if pinned else 204, {"channel_id": m.group(1), "message_id": m.group(2)} if pinned else None)
        m = re.fullmatch(r"/channels/([^/]+)/messages/([^/]+)/reactions", path)
        if m:
            msg = self.find(m.group(2))
            emoji = data.get("unicode")
            users = msg["reactions"].setdefault(emoji, set())
            users.add(me) if method == "POST" else users.discard(me)
            emit({"type": "react_update", "message_id": msg["id"], "emoji_id": None, "unicode": emoji, "count": len(users)}, only=audience(msg["channel_id"]))
            return self.send(201 if method == "POST" else 204, {"message_id": msg["id"]} if method == "POST" else None)
        self.problem(404, "Não encontrado", "Rota inexistente")

    def do_PUT(self):
        self.write("PUT")

    def do_DELETE(self):
        self.write("DELETE")

    def write(self, method):
        s = self.session()
        if s:
            self.chat_write(method, urlparse(self.path), uid(s["user"]))

    def do_GET(self):
        url = urlparse(self.path)
        if self.chat_get(url, parse_qs(url.query)):
            return
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"OK")
        elif self.path == "/server":
            self.send(200, {"id": str(uuid.uuid4()), "name": NAME, "public": SERVER_PASSWORD is None,
                            "owner_username": "dono", "member_count": len(USERS), "channel_count": 3})
        elif self.path == "/auth/whoami":
            s = self.session()
            if s:
                self.send(200, {"id": USERS[s["user"]]["id"], "username": s["user"], "nickname": None,
                                "roles": [], "settings": {"version": 1, "config": {}}})
        elif self.path == "/auth/connected_devices":
            s = self.session()
            if s:
                mine = [{"id": v["id"], "created_at": v["created"], "expires_at": v["created"]}
                        for v in SESSIONS.values() if v["user"] == s["user"]]
                self.send(200, {"connections": mine})
        else:
            self.problem(404, "Não encontrado", "Rota inexistente")

    def do_POST(self):
        if not self.path.startswith("/auth/"):
            return self.write("POST")
        data = self.body()
        if self.path == "/auth/login_server":
            if SERVER_PASSWORD is not None and data.get("server_password") == SERVER_PASSWORD:
                self.send(204, cookie="Auth=temp-token; Path=/; HttpOnly; Max-Age=1800; SameSite=Strict")
            else:
                self.problem(401, "Não autorizado", "Senha do servidor incorreta")
        elif self.path in ("/auth/login", "/auth/register"):
            if SERVER_PASSWORD is not None and self.cookie() != "temp-token" and self.cookie() not in SESSIONS:
                return self.problem(401, "Não autorizado", "Servidor não público: valide a senha do servidor antes")
            username, password = data.get("username", ""), data.get("password", "")
            if self.path == "/auth/register":
                if username in USERS:
                    return self.problem(409, "Conflito", "Username já existe")
                if len(password) < 8 or password.lower() == password or password.isalnum():
                    return self.problem(400, "Parâmetro inválido", "A senha precisa de 8 caracteres, 1 maiúscula e 1 caractere especial")
                USERS[username] = {"id": str(uuid.uuid4()), "password": password, "created": now()}
                return self.send(201, {"id": USERS[username]["id"], "username": username, "created_at": now()})
            user = USERS.get(username)
            if user is None or user["password"] != password:
                return self.problem(401, "Não autorizado", "Usuário ou senha incorretos")
            token = "jwt-" + uuid.uuid4().hex
            SESSIONS[token] = {"user": username, "id": str(uuid.uuid4()), "created": now()}
            self.send(200, {"user": {"id": user["id"], "username": username}, "connection_violation": False},
                      cookie=f"Auth={token}; Path=/; HttpOnly; SameSite=Strict")
        elif self.path == "/auth/logout":
            SESSIONS.pop(self.cookie(), None)
            self.send(204, cookie="Auth=; Path=/; Max-Age=0")
        elif self.path == "/auth/refresh":
            s = self.session()
            if s:
                self.send(200, {"connection": {"id": s["id"], "created_at": s["created"]}})
        elif self.path == "/auth/drop_connection":
            s = self.session()
            if s:
                target = data.get("connection_id")
                doomed = [t for t, v in SESSIONS.items() if v["user"] == s["user"] and (target == "ALL" or v["id"] == target)]
                for t in doomed:
                    del SESSIONS[t]
                self.send(200, {"dropped": len(doomed)})
        else:
            self.problem(404, "Não encontrado", "Rota inexistente")


if __name__ == "__main__":
    USERS["ana"] = {"id": str(uuid.uuid4()), "password": "Senha@123", "created": "2026-01-01T00:00:00Z"}
    USERS["bia"] = {"id": str(uuid.uuid4()), "password": "Senha@456", "created": "2026-01-02T00:00:00Z", "nickname": "Bia Borges",
                    "roles": [{"id": str(uuid.uuid4()), "name": "Admin", "color": "#E86CA5"}]}
    USERS["caio"] = {"id": str(uuid.uuid4()), "password": "Senha@789", "created": "2026-01-03T00:00:00Z"}
    # Histórico longo e espalhado por vários dias: dá para paginar a conversa, a busca e os filtros de data.
    old = datetime(2026, 11, 10, 9, 0, 0, tzinfo=timezone.utc)
    for i in range(1, 261):
        when = (old + timedelta(hours=i)).isoformat().replace("+00:00", "Z")
        new_message(PAPO, uid("bia" if i % 3 else "caio"), "mensagem antiga número %d" % i, created_at=when)
    new_message(PAPO, None, "Deploy da versão 1.4 concluído em produção.", external_author={"name": "Robô de Deploy"})
    first = new_message(PAPO, uid("caio"), "Alguém viu o deploy de ontem? https://papo.example.com/status")
    aid = str(uuid.uuid4())
    FILES[aid] = ("image/png", png(255, 178, 56))
    new_message(PAPO, uid("bia"), "Vi sim, @mention(<@%s>) acompanhou comigo.\nSegue o print:" % uid("ana"), reply_to=first["id"],
                attachments=[{"id": aid, "mime_type": "image/png", "original_file_name": "print.png", "size_bytes": len(FILES[aid][1]),
                              "thumbnail_id": aid, "thumbnail_width": 96, "thumbnail_height": 64, "moderation_status": "clean"}])
    first["reactions"]["👍"] = {uid("bia")}
    new_message(CHANNELS[2]["id"], uid("bia"), "Manutenção no sábado às 10h.")
    # Uma conversa direta já existente: a busca geral não a alcança, só a busca com channel_id.
    dm = str(uuid.uuid4())
    DMS[dm] = (uid("ana"), uid("bia"))
    new_message(dm, uid("bia"), "Ana, o deploy de ontem passou nos testes?")
    new_message(dm, uid("ana"), "Passou! Só faltou atualizar a documentação.")
    print(f"{NAME} em http://0.0.0.0:{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
