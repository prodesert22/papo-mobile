"""Testes dos cargos, das permissões e de quem reagiu em tools/dev/fake_papo.py. Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre, então nenhum depende do estado deixado por outro.
"""

import base64
import http.cookiejar
import json
import os
import queue
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta
from pathlib import Path

FAKE = Path(__file__).resolve().parent.parent / "dev" / "fake_papo.py"

# As senhas são as do próprio fake (só dados de desenvolvimento).
PASSWORDS = {"ana": "Senha@123", "bia": "Senha@456", "caio": "Senha@789", "dona": "Senha@000"}


def free_port() -> int:
    # O fake escuta em 0.0.0.0: a porta tem de estar livre nesse endereço, não só em 127.0.0.1.
    with socket.socket() as probe:
        probe.bind(("0.0.0.0", 0))
        return probe.getsockname()[1]


def moment(text):
    """O instante de um RFC 3339 com `Z`; a fração pode ter qualquer tamanho (o Go corta os zeros à direita)."""
    whole, _, fraction = text.rstrip("Z").partition(".")
    return datetime.strptime(whole, "%Y-%m-%dT%H:%M:%S") + timedelta(microseconds=int((fraction + "000000")[:6]))


def start_fake(extra_env):
    """Sobe o fake numa porta livre e espera o `/health`; devolve (processo, porta). Tenta outra porta se a escolhida foi tomada."""
    env = {k: v for k, v in os.environ.items() if k not in ("FAKE_PERMISSIONS", "FAKE_MANY_REACTORS")}
    env.update({"FAKE_EMOJI_SMALL": "1", **extra_env})
    for _ in range(5):
        port = free_port()
        process = subprocess.Popen([sys.executable, str(FAKE), str(port)], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        while process.poll() is None:
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.2).close()
                return process, port
            except OSError:
                threading.Event().wait(0.02)
    raise AssertionError("o fake terminou ao subir, em cinco portas")


class Session:
    """Um usuário logado no fake: cookie `Auth` e chamadas JSON."""

    def __init__(self, port: int, username: str):
        self.port = port
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        status, body = self.call("POST", "/auth/login", {"username": username, "password": PASSWORDS[username]})
        assert status == 200, (username, status, body)
        self.id = body["user"]["id"]

    def call(self, method, path, body=None, raw=None, content_type="application/json"):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data, method=method)
        if data is not None:
            request.add_header("Content-Type", content_type)
        try:
            with self.opener.open(request, timeout=5) as response:
                payload = response.read()
                return response.status, (json.loads(payload) if payload else None)
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read()
            return error.code, (json.loads(payload) if payload else None)

    def send(self, channel_id, content, file=False):
        """POST /messages como o app: multipart com `channel_id`, `content` e, se `file`, um anexo."""
        boundary = "b" + uuid.uuid4().hex
        parts = [("channel_id", None, channel_id), ("content", None, content)]
        if file:
            parts.append(("attachments", "nota.txt", "texto do anexo"))
        raw = b""
        for name, filename, value in parts:
            disposition = f'form-data; name="{name}"' + (f'; filename="{filename}"' if filename else "")
            raw += f"--{boundary}\r\nContent-Disposition: {disposition}\r\n".encode()
            raw += (b"Content-Type: text/plain\r\n" if filename else b"") + b"\r\n" + value.encode() + b"\r\n"
        raw += f"--{boundary}--\r\n".encode()
        return self.call("POST", "/messages", raw=raw, content_type=f"multipart/form-data; boundary={boundary}")

    def watch(self):
        return Watcher(self)


class Watcher:
    """Conexão WebSocket que junta os eventos recebidos numa fila (cliente mínimo, só leitura)."""

    def __init__(self, session: Session):
        cookie = "; ".join(f"{c.name}={c.value}" for c in session.jar)
        self.sock = socket.create_connection(("127.0.0.1", session.port), timeout=5)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nCookie: {cookie}\r\n\r\n").encode())
        self.buffer = b""
        while b"\r\n\r\n" not in self.buffer:
            self.buffer += self.sock.recv(4096)
        head, self.buffer = self.buffer.split(b"\r\n\r\n", 1)
        assert b" 101 " in head.split(b"\r\n")[0], head
        self.events = queue.Queue()
        threading.Thread(target=self.pump, daemon=True).start()
        self.wait(lambda e: e["type"] == "presence_update" and e["user_id"] == session.id)

    def need(self, count):
        while len(self.buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError
            self.buffer += chunk
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def pump(self):
        try:
            while True:
                b0, b1 = self.need(2)
                length = b1 & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self.need(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self.need(8))[0]
                data = self.need(length)
                if b0 & 0x0F == 1:
                    self.events.put(json.loads(data))
        except (OSError, EOFError):
            pass

    def send(self, event):
        data, mask = json.dumps(event).encode(), os.urandom(4)
        header = bytes([0x81, 0x80 | len(data)]) if len(data) < 126 else bytes([0x81, 0x80 | 126]) + struct.pack(">H", len(data))
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def wait(self, matches, seconds=5):
        """Os eventos até o primeiro que satisfaz `matches` (inclusive ele), na ordem em que chegaram."""
        seen = []
        while True:
            event = self.events.get(timeout=seconds)
            seen.append(event)
            if matches(event):
                return seen

    def close(self):
        self.sock.close()


class FakeTestCase(unittest.TestCase):
    permissions = None  # valor de FAKE_PERMISSIONS, ou None para o fake sem permissões
    env = {}  # o resto do ambiente do fake (FAKE_MANY_REACTORS, ...)

    def setUp(self):
        self.process, self.port = start_fake({**({"FAKE_PERMISSIONS": self.permissions} if self.permissions else {}), **self.env})
        self.addCleanup(self.stop)
        self.watchers = []
        self.addCleanup(lambda: [w.close() for w in self.watchers])

    def stop(self):
        self.process.kill()
        self.process.wait()

    def login(self, username) -> Session:
        return Session(self.port, username)

    def watch(self, session) -> Watcher:
        watcher = session.watch()
        self.watchers.append(watcher)
        return watcher

    def channel(self, name, session=None):
        status, body = (session or self.login("dona")).call("GET", "/channels")
        self.assertEqual(200, status)
        return next(c for c in body["channels"] if c["name"] == name)


class SemPermissoesTest(FakeTestCase):
    """Sem FAKE_PERMISSIONS o fake responde como sempre: tudo liberado, e nada de cargos."""

    def test_nao_expoe_cargos_nem_dono_nem_entradas(self):
        caio = self.login("caio")
        self.assertEqual(404, caio.call("GET", "/roles")[0])
        _, server = caio.call("GET", "/server")
        self.assertNotIn("owner_id", server)
        _, channels = caio.call("GET", "/channels")
        self.assertTrue(all("permissions" not in c for c in channels["channels"]))
        self.assertEqual([], caio.call("GET", "/auth/whoami")[1]["roles"])

    def test_qualquer_um_envia_anexa_fixa_e_reage_mas_so_o_autor_exclui(self):
        caio, ana = self.login("caio"), self.login("ana")
        papo, avisos = self.channel("papo", caio)["id"], self.channel("avisos", caio)["id"]
        status, sent = caio.send(avisos, "oi", file=True)
        self.assertEqual(201, status)
        self.assertEqual(200, caio.call("POST", f"/channels/{avisos}/messages/{sent['id']}/pin")[0])
        self.assertEqual(201, caio.call("POST", f"/channels/{avisos}/messages/{sent['id']}/reactions", {"unicode": "👍"})[0])
        self.assertEqual(403, ana.call("DELETE", f"/messages/{sent['id']}")[0])
        self.assertEqual(200, ana.call("GET", f"/channels/{papo}/messages")[0])

    def test_voice_join_nao_responde(self):
        ana = self.login("ana")
        watcher = self.watch(ana)
        palco_like = self.channel("Sala de voz", ana)["id"]
        watcher.send({"type": "voice_join", "channel_id": palco_like})
        watcher.send({"type": "heartbeat"})
        seen = watcher.wait(lambda e: e["type"] == "heartbeat_ack")
        self.assertFalse([e for e in seen if e["type"] == "error"])


class CenarioDemoTest(FakeTestCase):
    permissions = "demo"

    def role_id(self, name):
        _, body = self.login("dona").call("GET", "/roles")
        return next(r["id"] for r in body["roles"] if r["name"] == name)

    def test_cargos_com_as_oito_flags(self):
        status, body = self.login("caio").call("GET", "/roles")
        self.assertEqual(200, status)
        by_name = {r["name"]: r for r in body["roles"]}
        self.assertEqual({"Moderação", "Leitores"}, set(by_name))
        moderation = by_name["Moderação"]["permissions"]
        self.assertEqual(8, len(moderation))
        self.assertEqual({"pin_message", "everyone_message", "send_attachment"}, {k for k, v in moderation.items() if v})
        self.assertFalse(any(by_name["Leitores"]["permissions"].values()))
        self.assertEqual("#E86CA5", by_name["Moderação"]["color"])

    def test_dono_cargos_dos_usuarios_e_whoami(self):
        ana, bia = self.login("ana"), self.login("bia")
        _, server = ana.call("GET", "/server")
        _, users = ana.call("GET", "/users")
        by_name = {u["username"]: u for u in users["users"]}
        self.assertEqual(by_name["dona"]["id"], server["owner_id"])
        self.assertEqual("dona", server["owner_username"])
        self.assertEqual([self.role_id("Moderação")], [r["id"] for r in by_name["bia"]["roles"]])
        self.assertEqual([self.role_id("Leitores")], [r["id"] for r in by_name["ana"]["roles"]])
        self.assertEqual([], by_name["caio"]["roles"])
        self.assertEqual(["Moderação"], [r["name"] for r in bia.call("GET", "/auth/whoami")[1]["roles"]])

    def test_canais_trazem_as_entradas_por_cargo(self):
        ana = self.login("ana")
        avisos = self.channel("avisos", ana)
        self.assertEqual(["Leitores", "Moderação"], [e["role_name"] for e in avisos["permissions"]])
        readers = avisos["permissions"][0]["permissions"]
        self.assertEqual({"read_channel": True, "send_messages": False, "delete_messages": False, "connect_voice": False}, readers)
        self.assertEqual([], self.channel("papo", ana)["permissions"])
        self.assertEqual("voice", self.channel("Palco", ana)["type"])
        self.assertTrue(self.channel("Palco", ana)["permissions"][1]["permissions"]["connect_voice"])
        self.assertEqual([], self.channel("Geral", ana)["permissions"])

    def test_permissoes_do_canal_nao_existem_em_dm(self):
        ana = self.login("ana")
        avisos = self.channel("avisos", ana)
        status, body = ana.call("GET", f"/channels/{avisos['id']}/permissions")
        self.assertEqual(200, status)
        self.assertEqual(avisos["permissions"], body["permissions"])
        _, dms = ana.call("GET", "/dms")
        self.assertEqual(404, ana.call("GET", f"/channels/{dms['dms'][0]['id']}/permissions")[0])
        self.assertEqual(404, ana.call("GET", f"/channels/{uuid.uuid4()}/permissions")[0])

    def test_ler_exige_read_channel_e_canal_aberto_e_livre(self):
        ana, caio = self.login("ana"), self.login("caio")
        avisos, equipe, papo = (self.channel(n, ana)["id"] for n in ("avisos", "equipe", "papo"))
        self.assertEqual(200, ana.call("GET", f"/channels/{avisos}/messages")[0])
        self.assertEqual(403, ana.call("GET", f"/channels/{equipe}/messages")[0])
        self.assertEqual(403, caio.call("GET", f"/channels/{avisos}/messages")[0])
        self.assertEqual(403, caio.call("GET", f"/channels/{avisos}/pinned")[0])
        self.assertEqual(200, caio.call("GET", f"/channels/{papo}/messages")[0])

    def test_enviar_exige_send_messages(self):
        ana, bia, caio = self.login("ana"), self.login("bia"), self.login("caio")
        avisos, papo = self.channel("avisos", ana)["id"], self.channel("papo", ana)["id"]
        status, body = ana.send(avisos, "posso?")
        self.assertEqual(403, status)
        self.assertEqual("Acesso negado", body["title"])
        self.assertEqual(201, bia.send(avisos, "pode")[0])
        self.assertEqual(201, caio.send(papo, "canal aberto")[0])

    def test_anexo_exige_send_attachment_so_fora_de_dm(self):
        ana, bia, caio = self.login("ana"), self.login("bia"), self.login("caio")
        papo = self.channel("papo", ana)["id"]
        self.assertEqual(403, caio.send(papo, "com anexo", file=True)[0])
        self.assertEqual(201, caio.send(papo, "sem anexo")[0])
        self.assertEqual(201, bia.send(papo, "com anexo", file=True)[0])
        dm = ana.call("GET", "/dms")[1]["dms"][0]["id"]
        self.assertEqual(201, ana.send(dm, "anexo em DM", file=True)[0])

    def test_fixar_exige_pin_message_e_em_dm_basta_ser_membro(self):
        ana, bia, caio = self.login("ana"), self.login("bia"), self.login("caio")
        papo = self.channel("papo", ana)["id"]
        _, sent = caio.send(papo, "fixa essa")
        pin = f"/channels/{papo}/messages/{sent['id']}/pin"
        self.assertEqual(403, caio.call("POST", pin)[0])
        self.assertEqual(403, ana.call("POST", pin)[0])
        self.assertEqual(200, bia.call("POST", pin)[0])
        self.assertEqual(403, caio.call("DELETE", pin)[0])
        self.assertEqual(204, bia.call("DELETE", pin)[0])
        dm = ana.call("GET", "/dms")[1]["dms"][0]["id"]
        _, in_dm = ana.send(dm, "fixa na DM")
        self.assertEqual(200, ana.call("POST", f"/channels/{dm}/messages/{in_dm['id']}/pin")[0])
        self.assertEqual(204, ana.call("DELETE", f"/channels/{dm}/messages/{in_dm['id']}/pin")[0])
        self.assertEqual(403, caio.call("POST", f"/channels/{dm}/messages/{in_dm['id']}/pin")[0])

    def test_desafixar_em_canal_pede_tambem_leitura(self):
        bia, dona = self.login("bia"), self.login("dona")
        equipe = self.channel("equipe", dona)["id"]
        _, sent = dona.send(equipe, "pauta")
        pin = f"/channels/{equipe}/messages/{sent['id']}/pin"
        self.assertEqual(200, bia.call("POST", pin)[0])
        # Tira a leitura de Moderação no canal: o entry continua, mas sem read_channel.
        dona.call("PUT", f"/channels/{equipe}/permissions/{self.role_id('Moderação')}", {"permissions": {"send_messages": True}})
        self.assertEqual(403, bia.call("DELETE", pin)[0])

    def test_excluir_mensagem_alheia_exige_delete_messages_de_canal(self):
        ana, bia, caio, dona = (self.login(n) for n in ("ana", "bia", "caio", "dona"))
        papo, avisos = self.channel("papo", ana)["id"], self.channel("avisos", ana)["id"]
        _, open_msg = caio.send(papo, "em canal aberto")
        # Canal aberto não dá delete_messages a ninguém (só ao dono), nem a quem tem cargo.
        self.assertEqual(403, bia.call("DELETE", f"/messages/{open_msg['id']}")[0])
        self.assertEqual(403, ana.call("DELETE", f"/messages/{open_msg['id']}")[0])
        self.assertEqual(204, dona.call("DELETE", f"/messages/{open_msg['id']}")[0])
        _, restricted = dona.send(avisos, "aviso do dono")
        self.assertEqual(403, ana.call("DELETE", f"/messages/{restricted['id']}")[0])
        self.assertEqual(204, bia.call("DELETE", f"/messages/{restricted['id']}")[0])
        _, mine = ana.send(papo, "da ana")
        self.assertEqual(403, bia.call("PUT", f"/messages/{mine['id']}", {"content": "editei a sua"})[0])
        self.assertEqual(200, ana.call("PUT", f"/messages/{mine['id']}", {"content": "editei a minha"})[0])

    def test_excluir_a_propria_sempre_pode_e_em_dm_nem_o_dono_exclui_a_alheia(self):
        ana, bia, dona = self.login("ana"), self.login("bia"), self.login("dona")
        dm = ana.call("GET", "/dms")[1]["dms"][0]["id"]
        _, hers = bia.send(dm, "da bia")
        self.assertEqual(403, ana.call("DELETE", f"/messages/{hers['id']}")[0])
        _, mine = ana.send(dm, "da ana")
        self.assertEqual(204, ana.call("DELETE", f"/messages/{mine['id']}")[0])
        # A dona é dona do servidor e, numa DM só dela com a ana, ainda assim não exclui a mensagem da outra.
        own_dm = dona.call("POST", "/dms", {"user_id": ana.id})[1]["id"]
        _, other = ana.send(own_dm, "para a dona")
        self.assertEqual(403, dona.call("DELETE", f"/messages/{other['id']}")[0])

    def test_reagir_exige_send_messages_e_tirar_a_reacao_exige_leitura(self):
        ana, bia = self.login("ana"), self.login("bia")
        avisos = self.channel("avisos", ana)["id"]
        _, sent = bia.send(avisos, "reaja")
        path = f"/channels/{avisos}/messages/{sent['id']}/reactions"
        self.assertEqual(403, ana.call("POST", path, {"unicode": "👍"})[0])
        self.assertEqual(201, bia.call("POST", path, {"unicode": "👍"})[0])
        self.assertEqual(204, ana.call("DELETE", path, {"unicode": "👍"})[0])
        self.assertEqual(403, self.login("caio").call("DELETE", path, {"unicode": "👍"})[0])

    def test_ver_quem_reagiu_exige_read_channel_e_nao_send_messages(self):
        ana, bia, caio = self.login("ana"), self.login("bia"), self.login("caio")
        avisos = self.channel("avisos", ana)["id"]
        _, sent = bia.send(avisos, "reaja")
        bia.call("POST", f"/channels/{avisos}/messages/{sent['id']}/reactions", {"unicode": "👍"})
        path = f"/channels/{avisos}/messages/{sent['id']}/reactions"
        # A ana só lê o canal (não pode reagir) e vê quem reagiu; o caio, sem cargo, não lê e leva 403.
        status, body = ana.call("GET", path)
        self.assertEqual(200, status)
        self.assertEqual([bia.id], [u["user_id"] for g in body["reactions"] for u in g["users"]])
        self.assertEqual(403, caio.call("GET", path)[0])

    def test_busca_so_acha_canais_legiveis(self):
        bia, caio = self.login("bia"), self.login("caio")
        self.assertTrue(bia.call("POST", "/search", {"text": "Pauta"})[1]["results"])
        self.assertEqual([], caio.call("POST", "/search", {"text": "Pauta"})[1]["results"])

    def test_evento_de_mensagem_so_chega_a_quem_le_o_canal(self):
        bia, caio, dona = self.login("bia"), self.login("caio"), self.login("dona")
        equipe, papo = self.channel("equipe", dona)["id"], self.channel("papo", dona)["id"]
        for_bia, for_caio = self.watch(bia), self.watch(caio)
        dona.send(equipe, "só da equipe")
        dona.send(papo, "para todos")
        self.assertIn(equipe, [e.get("channel_id") for e in for_bia.wait(lambda e: e.get("channel_id") == papo)])
        self.assertNotIn(equipe, [e.get("channel_id") for e in for_caio.wait(lambda e: e.get("channel_id") == papo)])

    def test_entrar_na_voz_sem_connect_voice_responde_voice_forbidden(self):
        ana, bia = self.login("ana"), self.login("bia")
        palco = self.channel("Palco", ana)["id"]
        for_ana, for_bia = self.watch(ana), self.watch(bia)
        for_bia.send({"type": "voice_join", "channel_id": palco})
        for_bia.send({"type": "heartbeat"})
        self.assertFalse([e for e in for_bia.wait(lambda e: e["type"] == "heartbeat_ack") if e["type"] == "error"])
        for_ana.send({"type": "voice_join", "channel_id": palco})
        error = for_ana.wait(lambda e: e["type"] == "error")[-1]
        self.assertEqual("voice-forbidden", error["code"])
        # Canal de texto também é recusado, e canal inexistente é um erro sem código.
        for_ana.send({"type": "voice_join", "channel_id": self.channel("papo", ana)["id"]})
        self.assertEqual("voice-forbidden", for_ana.wait(lambda e: e["type"] == "error")[-1]["code"])
        for_ana.send({"type": "voice_join", "channel_id": str(uuid.uuid4())})
        self.assertNotIn("code", for_ana.wait(lambda e: e["type"] == "error")[-1])

    def test_so_quem_tem_manage_roles_ou_manage_channels_administra(self):
        bia = self.login("bia")
        self.assertEqual(403, bia.call("POST", "/roles", {"name": "Novo"})[0])
        self.assertEqual(403, bia.call("PUT", f"/roles/{self.role_id('Leitores')}", {"name": "Outro"})[0])
        self.assertEqual(403, bia.call("POST", f"/users/{bia.id}/roles", {"role_id": self.role_id("Leitores")})[0])
        avisos = self.channel("avisos", bia)["id"]
        self.assertEqual(403, bia.call("PUT", f"/channels/{avisos}/permissions/{self.role_id('Leitores')}", {"permissions": {}})[0])
        self.assertEqual(403, bia.call("DELETE", f"/channels/{avisos}/role/{self.role_id('Leitores')}")[0])

    def test_atribuir_e_remover_cargo_manda_role_add_e_role_remove_e_muda_o_acesso(self):
        dona, caio, ana = self.login("dona"), self.login("caio"), self.login("ana")
        papo = self.channel("papo", ana)["id"]
        _, sent = ana.send(papo, "fixa")
        pin = f"/channels/{papo}/messages/{sent['id']}/pin"
        self.assertEqual(403, caio.call("POST", pin)[0])
        watcher = self.watch(ana)
        moderation = self.role_id("Moderação")
        status, body = dona.call("POST", f"/users/{caio.id}/roles", {"role_id": moderation})
        self.assertEqual(201, status)
        self.assertEqual({"user_id": caio.id, "role_id": moderation}, {k: body[k] for k in ("user_id", "role_id")})
        added = watcher.wait(lambda e: e["type"] == "role_add")[-1]
        self.assertEqual({"user_id": caio.id, "role_id": moderation}, {k: added[k] for k in ("user_id", "role_id")})
        self.assertEqual([moderation], [r["id"] for r in caio.call("GET", "/auth/whoami")[1]["roles"]])
        self.assertEqual(200, caio.call("POST", pin)[0])
        # Atribuir de novo é idempotente e manda o evento outra vez.
        self.assertEqual(201, dona.call("POST", f"/users/{caio.id}/roles", {"role_id": moderation})[0])
        watcher.wait(lambda e: e["type"] == "role_add")
        self.assertEqual(204, dona.call("DELETE", f"/users/{caio.id}/roles/{moderation}")[0])
        removed = watcher.wait(lambda e: e["type"] == "role_remove")[-1]
        self.assertEqual(caio.id, removed["user_id"])
        self.assertEqual(404, dona.call("DELETE", f"/users/{caio.id}/roles/{moderation}")[0])
        self.assertEqual(403, caio.call("DELETE", pin)[0])

    def test_criar_cargo_com_membros_manda_um_role_add_por_membro(self):
        dona, ana, caio = self.login("dona"), self.login("ana"), self.login("caio")
        watcher = self.watch(ana)
        status, role = dona.call("POST", "/roles", {"name": "Auxiliar", "color": "#112233", "users": [caio.id, caio.id, ana.id],
                                                    "permissions": {"pin_message": True}})
        self.assertEqual(201, status)
        self.assertTrue(role["permissions"]["pin_message"])
        self.assertFalse(role["permissions"]["everyone_message"])
        added = [e for e in watcher.wait(lambda e: e["type"] == "role_add" and e["user_id"] == ana.id) if e["type"] == "role_add"]
        self.assertEqual({caio.id, ana.id}, {e["user_id"] for e in added})
        self.assertEqual(409, dona.call("POST", "/roles", {"name": "Auxiliar"})[0])
        self.assertEqual(400, dona.call("POST", "/roles", {"name": "x" * 33})[0])
        self.assertEqual(404, dona.call("POST", "/roles", {"name": "Outro", "users": [str(uuid.uuid4())]})[0])

    def test_alterar_e_apagar_cargo_nao_manda_evento_como_o_servidor(self):
        dona, ana = self.login("dona"), self.login("ana")
        watcher = self.watch(ana)
        readers, moderation = self.role_id("Leitores"), self.role_id("Moderação")
        status, role = dona.call("PUT", f"/roles/{moderation}", {"name": "Moderação", "color": "#E86CA5", "permissions": {"pin_message": True}})
        self.assertEqual(200, status)
        self.assertEqual({"pin_message"}, {k for k, v in role["permissions"].items() if v})
        self.assertEqual(204, dona.call("DELETE", f"/roles/{readers}")[0])
        # O marcador chega depois dos dois: entre eles não pode haver nenhum evento de cargo ou de canal.
        dona.call("POST", f"/users/{ana.id}/roles", {"role_id": moderation})
        seen = watcher.wait(lambda e: e["type"] == "role_add")
        self.assertEqual(["role_add"], [e["type"] for e in seen if e["type"] in ("role_add", "role_remove", "channel_update")])
        # O cargo apagado some do catálogo, dos usuários e das entradas dos canais (que deixam de ser entradas).
        self.assertEqual({"Moderação"}, {r["name"] for r in ana.call("GET", "/roles")[1]["roles"]})
        self.assertNotIn(readers, [r["id"] for u in ana.call("GET", "/users")[1]["users"] for r in u["roles"]])
        self.assertEqual(["Moderação"], [e["role_name"] for e in self.channel("avisos", ana)["permissions"]])
        self.assertEqual(404, dona.call("DELETE", f"/roles/{readers}")[0])

    def test_permissao_de_canal_manda_channel_update_sem_parent_id_e_muda_o_acesso(self):
        dona, ana = self.login("dona"), self.login("ana")
        avisos = self.channel("avisos", ana)
        self.assertIsNotNone(avisos["parent_id"])
        self.assertEqual(403, ana.send(avisos["id"], "ainda não")[0])
        watcher = self.watch(ana)
        readers = self.role_id("Leitores")
        status, body = dona.call("PUT", f"/channels/{avisos['id']}/permissions/{readers}",
                                 {"permissions": {"read_channel": True, "send_messages": True}})
        self.assertEqual(200, status)
        self.assertEqual({"read_channel": True, "send_messages": True, "delete_messages": False, "connect_voice": False}, body["permissions"])
        update = watcher.wait(lambda e: e["type"] == "channel_update")[-1]
        self.assertEqual(avisos["id"], update["channel_id"])
        self.assertIsNone(update["parent_id"])  # cópia do defeito do servidor
        self.assertEqual(201, ana.send(avisos["id"], "agora sim")[0])
        # Apagar a entrada de Leitores deixa só a de Moderação: ana, sem entrada, perde a leitura.
        self.assertEqual(204, dona.call("DELETE", f"/channels/{avisos['id']}/role/{readers}")[0])
        watcher.wait(lambda e: e["type"] == "channel_update")
        self.assertEqual(403, ana.call("GET", f"/channels/{avisos['id']}/messages")[0])
        # Apagar a última entrada abre o canal; apagar de novo é idempotente.
        self.assertEqual(204, dona.call("DELETE", f"/channels/{avisos['id']}/role/{self.role_id('Moderação')}")[0])
        self.assertEqual(204, dona.call("DELETE", f"/channels/{avisos['id']}/role/{self.role_id('Moderação')}")[0])
        self.assertEqual(200, ana.call("GET", f"/channels/{avisos['id']}/messages")[0])
        self.assertEqual(404, dona.call("PUT", f"/channels/{uuid.uuid4()}/permissions/{readers}", {"permissions": {}})[0])
        self.assertEqual(404, dona.call("PUT", f"/channels/{avisos['id']}/permissions/{uuid.uuid4()}", {"permissions": {}})[0])


class ArquivoJsonTest(FakeTestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "permissoes.json"
        path.write_text(json.dumps({
            "owner": "caio",
            "roles": [{"name": "Fiscal", "permissions": ["manage_roles"]}],
            "users": {"ana": ["Fiscal"], "novato": ["Fiscal"]},
            "channels": [{"name": "sala-fiscal", "entries": {"Fiscal": ["read_channel"]}}],
            "messages": [{"channel": "sala-fiscal", "author": "novato", "content": "primeira"}],
        }))
        self.permissions = str(path)
        super().setUp()

    def test_arquivo_json_semeia_dono_cargos_canal_e_usuario_novo(self):
        caio = self.login("caio")
        _, server = caio.call("GET", "/server")
        self.assertEqual(caio.id, server["owner_id"])
        self.assertEqual(["Fiscal"], [r["name"] for r in caio.call("GET", "/roles")[1]["roles"]])
        sala = self.channel("sala-fiscal", caio)
        self.assertEqual(["Fiscal"], [e["role_name"] for e in sala["permissions"]])
        self.assertEqual(200, caio.call("GET", f"/channels/{sala['id']}/messages")[0])
        self.assertEqual(403, self.login("bia").call("GET", f"/channels/{sala['id']}/messages")[0])
        _, users = caio.call("GET", "/users")
        self.assertIn("novato", [u["username"] for u in users["users"]])


class SemDonoTest(FakeTestCase):
    """Servidor sem dono: ninguém tem tudo, e só os cargos valem."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "sem-dono.json"
        path.write_text(json.dumps({"roles": [{"name": "Fixador", "permissions": ["pin_message"]}], "users": {"ana": ["Fixador"]}}))
        self.permissions = str(path)
        super().setUp()

    def test_sem_owner_id_so_os_cargos_decidem(self):
        ana, bia = self.login("ana"), self.login("bia")
        self.assertIsNone(ana.call("GET", "/server")[1]["owner_id"])
        papo = self.channel("papo", ana)["id"]
        _, sent = bia.send(papo, "fixa")
        pin = f"/channels/{papo}/messages/{sent['id']}/pin"
        self.assertEqual(200, ana.call("POST", pin)[0])
        self.assertEqual(403, bia.call("POST", pin)[0])
        # Sem dono, ninguém exclui a mensagem alheia em canal aberto.
        self.assertEqual(403, ana.call("DELETE", f"/messages/{sent['id']}")[0])


class QuemReagiuTest(FakeTestCase):
    """`GET .../reactions` do fake sem permissões: o que o app lê para mostrar quem reagiu."""

    def seeded(self, session):
        """A mensagem do `papo` com 🔥 de caio e o emoji do servidor de bia e caio, e o caminho das reações dela."""
        papo = self.channel("papo", session)["id"]
        _, body = session.call("GET", f"/channels/{papo}/messages?limit=100")
        message = next(m for m in body["messages"] if m["content"] == "reaja com um emoji do servidor")
        return papo, message, f"/channels/{papo}/messages/{message['id']}/reactions"

    def test_agrupa_por_emoji_da_reacao_mais_nova_para_a_mais_antiga(self):
        bia, caio = self.login("bia"), self.login("caio")
        _, message, path = self.seeded(bia)
        status, body = bia.call("GET", path)
        self.assertEqual(200, status)
        self.assertEqual(message["id"], body["message_id"])
        self.assertFalse(body["has_more"])
        # A mais nova é o 🔥 de caio: o primeiro grupo é o da primeira linha da página, e o emoji do servidor vem depois.
        self.assertEqual([None, message["reactions"][0]["emoji_id"]], [g["emoji_id"] for g in body["reactions"]])
        fire, parrot = body["reactions"]
        self.assertEqual("🔥", fire["unicode"])
        self.assertEqual([(1, caio.id)], [(fire["count"], u["user_id"]) for u in fire["users"]])
        self.assertEqual([caio.id, bia.id], [u["user_id"] for u in parrot["users"]])
        self.assertEqual(2, parrot["count"])
        # O horário vai como o Go o escreve: sem os zeros à direita da fração, e sem fração quando ela é zero.
        self.assertEqual(["2026-11-24T12:30:02.25Z"], [u["created_at"] for u in fire["users"]])
        self.assertEqual(["2026-11-24T12:30:01.5Z", "2026-11-24T12:30:00Z"], [u["created_at"] for u in parrot["users"]])

    def test_order_asc_inverte_a_ordem(self):
        bia = self.login("bia")
        _, _, path = self.seeded(bia)
        _, body = bia.call("GET", path + "?order=asc")
        self.assertEqual([None, "🔥"], [g["unicode"] for g in body["reactions"]])
        self.assertEqual(["2026-11-24T12:30:00Z", "2026-11-24T12:30:01.5Z"], [u["created_at"] for u in body["reactions"][0]["users"]])

    def test_cursor_since_e_last_id_pega_so_o_que_vem_depois_na_ordem(self):
        bia, caio = self.login("bia"), self.login("caio")
        _, _, path = self.seeded(bia)
        _, body = bia.call("GET", path)
        parrot = body["reactions"][1]["users"]
        newest_parrot = next(u for u in parrot if u["user_id"] == caio.id)
        _, older = bia.call("GET", f"{path}?order=desc&since={newest_parrot['created_at']}&last_id={newest_parrot['id']}")
        self.assertEqual([[bia.id]], [[u["user_id"] for u in g["users"]] for g in older["reactions"]])
        self.assertFalse(older["has_more"])
        # `since` sozinho, como no servidor, é "criado depois" mesmo em desc; `last_id` sozinho não filtra nada.
        _, alone = bia.call("GET", f"{path}?order=desc&since={newest_parrot['created_at']}")
        self.assertEqual(["🔥"], [g["unicode"] for g in alone["reactions"]])
        _, no_since = bia.call("GET", f"{path}?last_id={newest_parrot['id']}")
        self.assertEqual(body, no_since)

    def own_reactions(self, session, channel_id, message_id):
        _, body = session.call("GET", f"/channels/{channel_id}/messages?limit=100")
        return next(m for m in body["messages"] if m["id"] == message_id)["user_reactions"]

    def test_reagir_guarda_horario_e_id_que_a_mensagem_e_a_lista_mostram(self):
        ana = self.login("ana")
        papo, message, path = self.seeded(ana)
        self.assertEqual(201, ana.call("POST", path, {"unicode": "👍"})[0])
        _, body = ana.call("GET", path)
        mine = next(g for g in body["reactions"] if g["unicode"] == "👍")["users"][0]
        self.assertEqual(ana.id, mine["user_id"])
        self.assertRegex(mine["created_at"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d*[1-9])?Z$")
        self.assertEqual([mine["id"]], [r["id"] for r in self.own_reactions(ana, papo, message["id"]) if r["unicode"] == "👍"])
        # Reagir de novo (a rota é idempotente) não troca o id nem o horário; tirar a reação a tira da lista.
        ana.call("POST", path, {"unicode": "👍"})
        self.assertEqual(body, ana.call("GET", path)[1])
        self.assertEqual(204, ana.call("DELETE", path, {"unicode": "👍"})[0])
        self.assertNotIn("👍", [g["unicode"] for g in ana.call("GET", path)[1]["reactions"]])
        self.assertEqual([], [r for r in self.own_reactions(ana, papo, message["id"]) if r["unicode"] == "👍"])

    def test_pedidos_invalidos(self):
        bia = self.login("bia")
        papo, _, path = self.seeded(bia)
        self.assertEqual(400, bia.call("GET", path + "?order=sideways")[0])
        self.assertEqual(400, bia.call("GET", path + "?since=ontem&last_id=x")[0])
        outro = self.channel("avisos", bia)["id"]
        _, message = bia.send(papo, "outra")
        self.assertEqual(404, bia.call("GET", f"/channels/{outro}/messages/{message['id']}/reactions")[0])
        self.assertEqual(404, bia.call("GET", f"/channels/{papo}/messages/{uuid.uuid4()}/reactions")[0])
        self.assertEqual(404, bia.call("GET", f"/channels/{uuid.uuid4()}/messages/{message['id']}/reactions")[0])


class MuitosReagiramTest(FakeTestCase):
    """`FAKE_MANY_REACTORS=1`: 130 usuários, três emojis intercalados, duas páginas."""

    env = {"FAKE_MANY_REACTORS": "1"}

    def setUp(self):
        super().setUp()
        self.bia = self.login("bia")
        papo = self.channel("papo", self.bia)["id"]
        _, body = self.bia.call("GET", f"/channels/{papo}/messages?limit=500")
        message = next(m for m in body["messages"] if m["content"] == "muita gente reagiu a esta mensagem")
        self.path = f"/channels/{papo}/messages/{message['id']}/reactions"
        self.counts = {(r["emoji_id"], r["unicode"]): r["count"] for r in message["reactions"]}

    def rows(self, body):
        return [(u["created_at"], u["id"], u["user_id"], g["unicode"] or g["emoji_id"]) for g in body["reactions"] for u in g["users"]]

    def test_a_primeira_pagina_traz_cem_e_a_segunda_o_resto(self):
        _, first = self.bia.call("GET", self.path)
        self.assertTrue(first["has_more"])
        self.assertEqual(100, len(self.rows(first)))
        self.assertEqual(3, len(first["reactions"]))
        self.assertEqual(100, sum(g["count"] for g in first["reactions"]))
        self.assertEqual({"🔥", "❤", None}, {g["unicode"] for g in first["reactions"]})

    def test_o_cursor_da_pagina_e_a_linha_mais_antiga_de_todos_os_grupos(self):
        _, first = self.bia.call("GET", self.path)
        rows = self.rows(first)
        oldest = min(rows, key=lambda r: (moment(r[0]), r[1]))
        # Os dados obrigam o caso difícil: a reação mais antiga não é a última do último grupo da página.
        self.assertNotEqual(oldest[1], first["reactions"][-1]["users"][-1]["id"])
        _, second = self.bia.call("GET", f"{self.path}?order=desc&since={oldest[0]}&last_id={oldest[1]}")
        self.assertFalse(second["has_more"])
        self.assertEqual(30, len(self.rows(second)))
        self.assertTrue(all((moment(r[0]), r[1]) < (moment(oldest[0]), oldest[1]) for r in self.rows(second)))
        # Nada se repete entre as páginas, e as duas juntas são as 130 reações, todas de usuários diferentes.
        both = rows + self.rows(second)
        self.assertEqual(130, len({r[1] for r in both}))
        self.assertEqual(130, len({r[2] for r in both}))
        # Cada emoji continua na segunda página, e os totais batem com os da mensagem.
        self.assertEqual(3, len(second["reactions"]))
        totals = {}
        for row in both:
            totals[row[3]] = totals.get(row[3], 0) + 1
        self.assertEqual(sorted(self.counts.values()), sorted(totals.values()))

    def test_os_horarios_tem_fracoes_de_tamanhos_diferentes(self):
        _, first = self.bia.call("GET", self.path)
        stamps = [r[0] for r in self.rows(first)]
        self.assertTrue(any("." not in t for t in stamps))
        self.assertTrue(any(t.endswith(".5Z") for t in stamps))
        self.assertTrue(any(t.endswith(".25Z") for t in stamps))
        self.assertTrue(any(t.endswith(".75Z") for t in stamps))

    def test_os_usuarios_inventados_aparecem_na_lista_de_usuarios(self):
        _, users = self.bia.call("GET", "/users")
        nicknames = {u["nickname"] for u in users["users"]}
        self.assertTrue({"Pessoa 001", "Pessoa 130"} <= nicknames)


class ArquivoInvalidoTest(unittest.TestCase):
    def run_with(self, spec):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ruim.json"
            path.write_text(spec if isinstance(spec, str) else json.dumps(spec))
            return self.run_path(str(path))

    def run_path(self, path):
        env = {**os.environ, "FAKE_PERMISSIONS": path, "FAKE_EMOJI_SMALL": "1"}
        done = subprocess.run([sys.executable, str(FAKE), "0"], env=env, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, done.returncode)
        return done.stderr

    def test_flag_desconhecida_derruba_o_fake_com_mensagem(self):
        self.assertIn("flag de cargo desconhecida: voar", self.run_with({"roles": [{"name": "X", "permissions": ["voar"]}]}))
        self.assertIn("flag de canal desconhecida: voar", self.run_with({
            "roles": [{"name": "X"}], "channels": [{"name": "papo", "entries": {"X": ["voar"]}}]}))

    def test_nome_desconhecido_derruba_o_fake_com_mensagem(self):
        self.assertIn("cargo desconhecido em users.ana: Z", self.run_with({"users": {"ana": ["Z"]}}))
        self.assertIn("cargo desconhecido em channels.papo: Z", self.run_with({"channels": [{"name": "papo", "entries": {"Z": []}}]}))
        self.assertIn("canal desconhecido em messages: nada", self.run_with({"messages": [{"channel": "nada", "author": "ana", "content": "x"}]}))

    def test_arquivo_ilegivel_ou_malformado_derruba_o_fake_com_mensagem(self):
        self.assertIn("não consegui ler", self.run_path("/caminho/que/nao/existe.json"))
        self.assertIn("não consegui ler", self.run_with("{isto não é json"))


if __name__ == "__main__":
    unittest.main()
