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
import time
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


def clean_env():
    """O ambiente de quem roda os testes, sem as variáveis FAKE_* (que ligariam cenários que o teste não pediu)."""
    return {k: v for k, v in os.environ.items() if not k.startswith("FAKE_")}


def start_fake(extra_env, stderr=subprocess.DEVNULL):
    """Sobe o fake numa porta livre e espera o `/health`; devolve (processo, porta). Tenta outra porta se a escolhida foi tomada."""
    env = clean_env()
    env.update({"FAKE_EMOJI_SMALL": "1", **extra_env})
    for _ in range(5):
        port = free_port()
        process = subprocess.Popen([sys.executable, str(FAKE), str(port)], env=env,
                                   stdout=subprocess.DEVNULL, stderr=stderr)
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
        """POST /messages como o app: multipart com `channel_id`, `content` e, se `file`, um anexo.

        `file` é True (uma nota de texto) ou (nome, tipo declarado, conteúdo).
        """
        boundary = "b" + uuid.uuid4().hex
        parts = [("channel_id", None, None, channel_id), ("content", None, None, content)]
        if file:
            filename, declared, body = file if isinstance(file, tuple) else ("nota.txt", "text/plain", "texto do anexo")
            parts.append(("attachments", filename, declared, body))
        raw = b""
        for name, filename, declared, value in parts:
            disposition = f'form-data; name="{name}"' + (f'; filename="{filename}"' if filename else "")
            raw += f"--{boundary}\r\nContent-Disposition: {disposition}\r\n".encode()
            raw += (f"Content-Type: {declared}\r\n".encode() if filename else b"") + b"\r\n" + value.encode() + b"\r\n"
        raw += f"--{boundary}--\r\n".encode()
        return self.call("POST", "/messages", raw=raw, content_type=f"multipart/form-data; boundary={boundary}")

    def fetch(self, path, headers=None):
        """GET sem ler o corpo como JSON: (status, cabeçalhos, bytes). Erro HTTP também devolve, não levanta."""
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", headers=headers or {})
        try:
            with self.opener.open(request, timeout=5) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.headers, error.read()

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
    env = {}            # outras variáveis FAKE_* do cenário
    stderr = subprocess.DEVNULL   # subprocess.PIPE nos testes que leem o log do fake

    def setUp(self):
        self.process, self.port = start_fake(
            {**self.env, **({"FAKE_PERMISSIONS": self.permissions} if self.permissions else {})}, self.stderr)
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


class EmojisAnimadosTest(FakeTestCase):
    """Os emojis do servidor falso incluem um GIF e um WebP animados, para ver a animação do app."""

    def emojis(self):
        ana, found, last_id = self.login("ana"), [], None
        while True:
            status, page = ana.call("GET", "/emojis" + (f"?last_id={last_id}" if last_id else ""))
            self.assertEqual(200, status)
            found += page["emojis"]
            if not page["has_more"]:
                return found
            last_id = page["emojis"][-1]["id"]

    def test_o_total_continua_duzentos_e_so_dois_nao_sao_png(self):
        emojis = self.emojis()
        self.assertEqual(200, len(emojis))
        self.assertEqual({"party_gif": "GIF", "wave_webp": "WEBP"}, {e["name"]: e["format"] for e in emojis if e["format"] != "PNG"})

    def test_o_gif_tem_tres_quadros_e_cabe_em_quatro_kb(self):
        blob = base64.b64decode(next(e for e in self.emojis() if e["name"] == "party_gif")["image_blob"])
        self.assertTrue(blob.startswith(b"GIF89a"))
        self.assertLessEqual(len(blob), 4096)
        # Cada quadro tem um bloco de controle gráfico (extensão 0xF9) ligado ao descritor de imagem (0x2C).
        self.assertEqual(3, blob.count(b"\x21\xf9\x04"))

    def test_o_webp_tem_animacao_ligada_e_tres_quadros_e_cabe_em_quatro_kb(self):
        blob = base64.b64decode(next(e for e in self.emojis() if e["name"] == "wave_webp")["image_blob"])
        self.assertEqual((b"RIFF", b"WEBP", b"VP8X"), (blob[:4], blob[8:12], blob[12:16]))
        self.assertTrue(blob[20] & 0x02, "bit de animação do VP8X")
        self.assertLessEqual(len(blob), 4096)
        self.assertEqual(3, blob.count(b"ANMF"))

    def test_as_mensagens_de_exemplo_usam_os_dois(self):
        ana = self.login("ana")
        papo = self.channel("papo", ana)["id"]
        _, body = ana.call("GET", f"/channels/{papo}/messages")
        texts = [m["content"] for m in body["messages"]]
        self.assertTrue(any(":party_gif:" in t and ":wave_webp:" in t for t in texts))
        self.assertIn(":party_gif:", texts)


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


# Dois "vídeos" sintéticos: o fake não decodifica nada, só serve bytes. O conteúdo é o índice do byte, para ver que o trecho certo veio.
CLIP = bytes(range(256)) * 250       # 64000 bytes
OTHER = bytes(range(255, -1, -1)) * 274   # 70144 bytes


class VideoTestCase(FakeTestCase):
    """Fake com uma pasta de vídeos (FAKE_VIDEO_DIR): `clip.mp4` e `outro.webm`."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        Path(folder.name, "clip.mp4").write_bytes(CLIP)
        Path(folder.name, "outro.webm").write_bytes(OTHER)
        Path(folder.name, "leia-me.txt").write_text("não é vídeo")
        self.env = {**self.env, "FAKE_VIDEO_DIR": folder.name}
        super().setUp()

    def attachments(self, session=None):
        """Os anexos de vídeo do `papo`, do mais novo ao mais antigo, com a legenda da mensagem."""
        session = session or self.login("ana")
        _, body = session.call("GET", f"/channels/{self.channel('papo', session)['id']}/messages?limit=30")
        return [(m["content"], a) for m in body["messages"] for a in m["attachments"] if a["mime_type"].startswith("video/")]

    def clip(self):
        return next(a for content, a in self.attachments() if content == "vídeo clip.mp4")


class VideoSementesTest(VideoTestCase):
    def test_uma_mensagem_por_video_da_pasta_e_tres_variacoes_do_menor(self):
        found = {content: a for content, a in self.attachments()}
        self.assertEqual({"vídeo clip.mp4", "vídeo outro.webm", "vídeo clip.mp4 sensível",
                          "vídeo clip.mp4 em retrato (360x640)", "vídeo clip.mp4 sem dimensões"}, set(found))
        self.assertEqual(("video/mp4", len(CLIP)), (found["vídeo clip.mp4"]["mime_type"], found["vídeo clip.mp4"]["size_bytes"]))
        self.assertEqual(("video/webm", len(OTHER)), (found["vídeo outro.webm"]["mime_type"], found["vídeo outro.webm"]["size_bytes"]))
        self.assertEqual("sensitive", found["vídeo clip.mp4 sensível"]["moderation_status"])
        self.assertEqual((360, 640), (found["vídeo clip.mp4 em retrato (360x640)"]["width"], found["vídeo clip.mp4 em retrato (360x640)"]["height"]))
        self.assertEqual((None, None), (found["vídeo clip.mp4 sem dimensões"]["width"], found["vídeo clip.mp4 sem dimensões"]["height"]))
        # Vídeo não tem miniatura (o servidor só gera para imagem).
        self.assertTrue(all(a["thumbnail_id"] is None for a in found.values()))

    def test_cada_semente_tem_o_proprio_arquivo(self):
        ids = [a["id"] for _, a in self.attachments()]
        self.assertEqual(len(ids), len(set(ids)))
        ana = self.login("ana")
        self.assertEqual(OTHER, ana.fetch(f"/attachments/{next(a['id'] for c, a in self.attachments() if c == 'vídeo outro.webm')}")[2])


class RangeTest(VideoTestCase):
    def get(self, range_header=None):
        headers = {"Range": range_header} if range_header is not None else {}
        return self.login("ana").fetch(f"/attachments/{self.clip()['id']}", headers)

    def test_sem_range_serve_o_arquivo_inteiro_e_diz_que_aceita_trechos(self):
        status, headers, body = self.get()
        self.assertEqual(200, status)
        self.assertEqual(CLIP, body)
        self.assertEqual("video/mp4", headers["Content-Type"])
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual(str(len(CLIP)), headers["Content-Length"])
        self.assertIsNone(headers["Content-Range"])

    def test_trecho_fechado_devolve_206_com_content_range(self):
        status, headers, body = self.get("bytes=10-19")
        self.assertEqual(206, status)
        self.assertEqual(CLIP[10:20], body)
        self.assertEqual("bytes 10-19/64000", headers["Content-Range"])
        self.assertEqual("10", headers["Content-Length"])
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual("video/mp4", headers["Content-Type"])

    def test_trecho_aberto_vai_ate_o_fim(self):
        status, headers, body = self.get("bytes=63990-")
        self.assertEqual((206, CLIP[63990:], "bytes 63990-63999/64000"), (status, body, headers["Content-Range"]))

    def test_sufixo_pede_os_ultimos_bytes_e_passar_do_tamanho_devolve_tudo(self):
        status, headers, body = self.get("bytes=-5")
        self.assertEqual((206, CLIP[-5:], "bytes 63995-63999/64000"), (status, body, headers["Content-Range"]))
        status, headers, body = self.get("bytes=-999999")
        self.assertEqual((206, CLIP, "bytes 0-63999/64000"), (status, body, headers["Content-Range"]))

    def test_fim_alem_do_arquivo_e_cortado_no_ultimo_byte(self):
        status, headers, body = self.get("bytes=63990-99999")
        self.assertEqual((206, CLIP[63990:], "bytes 63990-63999/64000"), (status, body, headers["Content-Range"]))

    def test_ultimo_byte_sozinho(self):
        status, headers, body = self.get("bytes=63999-63999")
        self.assertEqual((206, CLIP[63999:], "bytes 63999-63999/64000"), (status, body, headers["Content-Range"]))

    def test_comeco_depois_do_fim_e_416_com_o_tamanho_no_content_range(self):
        for wanted in ("bytes=64000-", "bytes=100000-200000", "bytes=-0"):
            status, headers, body = self.get(wanted)
            self.assertEqual((416, "bytes */64000"), (status, headers["Content-Range"]), wanted)
            self.assertNotEqual(CLIP, body)

    def test_range_ilegivel_e_416_sem_content_range(self):
        for wanted in ("bytes=abc", "items=0-1", "bytes=5-2", "bytes=-", "bytes=1", "bytes=--5"):
            status, headers, _ = self.get(wanted)
            self.assertEqual(416, status, wanted)
            self.assertIsNone(headers["Content-Range"], wanted)

    def test_varios_trechos_servem_o_arquivo_inteiro(self):
        status, headers, body = self.get("bytes=0-1,10-11")
        self.assertEqual((200, CLIP), (status, body))
        self.assertIsNone(headers["Content-Range"])

    def test_trechos_vazios_servem_o_arquivo_inteiro(self):
        status, _, body = self.get("bytes=,")
        self.assertEqual((200, CLIP), (status, body))

    def test_trecho_alem_do_fim_junto_de_um_dentro_serve_so_o_de_dentro(self):
        status, headers, body = self.get("bytes=100000-,5-9")
        self.assertEqual((206, CLIP[5:10], "bytes 5-9/64000"), (status, body, headers["Content-Range"]))

    def test_sem_sessao_e_401_como_no_servidor_e_nao_404(self):
        attachment = self.clip()["id"]
        stranger = urllib.request.Request(f"http://127.0.0.1:{self.port}/attachments/{attachment}", headers={"Range": "bytes=0-9"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(stranger, timeout=5)
        caught.exception.close()
        self.assertEqual(401, caught.exception.code)

    def test_anexo_que_nao_existe_e_404(self):
        self.assertEqual(404, self.login("ana").fetch(f"/attachments/{uuid.uuid4()}", {"Range": "bytes=0-9"})[0])

    def test_imagem_tambem_aceita_trecho(self):
        ana = self.login("ana")
        _, body = ana.call("GET", f"/channels/{self.channel('papo', ana)['id']}/messages?limit=30")
        image = next(a for m in body["messages"] for a in m["attachments"] if a["mime_type"] == "image/png")
        status, headers, part = ana.fetch(f"/attachments/{image['id']}", {"Range": "bytes=0-7"})
        self.assertEqual((206, b"\x89PNG\r\n\x1a\n"), (status, part))
        self.assertEqual(f"bytes 0-7/{image['size_bytes']}", headers["Content-Range"])


class MimeDoUploadTest(FakeTestCase):
    def test_tipo_generico_vira_o_do_video_pela_extensao_e_o_declarado_vale_quando_nao_e_generico(self):
        ana = self.login("ana")
        papo = self.channel("papo", ana)["id"]
        for name, declared, expected in (("a.mp4", "application/octet-stream", "video/mp4"), ("b.webm", "application/octet-stream", "video/webm"),
                                         ("c.OGV", "application/octet-stream", "video/ogg"), ("d.mp4", "video/webm", "video/webm"),
                                         ("e.bin", "application/octet-stream", "application/octet-stream"), ("f.txt", "text/plain", "text/plain")):
            status, sent = ana.send(papo, name, file=(name, declared, "conteudo"))
            self.assertEqual(201, status)
            self.assertEqual(expected, sent["attachments"][0]["mime_type"], name)
            self.assertEqual(expected, ana.fetch(f"/attachments/{sent['attachments'][0]['id']}")[1]["Content-Type"], name)


class VelocidadeTest(VideoTestCase):
    env = {"FAKE_VIDEO_KBPS": "128"}

    def test_video_chega_no_ritmo_pedido(self):
        ana, attachment = self.login("ana"), self.clip()["id"]
        started = time.monotonic()
        status, _, body = ana.fetch(f"/attachments/{attachment}")
        elapsed = time.monotonic() - started
        self.assertEqual((200, CLIP), (status, body))
        # 64000 bytes a 128 KB/s levam 0,49 s; o último pedaço sai uma fração antes disso. Só se confere o piso: dormir nunca adianta.
        self.assertGreaterEqual(elapsed, 0.4)

    def test_trecho_tambem_e_entregue_devagar(self):
        ana, attachment = self.login("ana"), self.clip()["id"]
        started = time.monotonic()
        status, _, body = ana.fetch(f"/attachments/{attachment}", {"Range": "bytes=0-39999"})
        elapsed = time.monotonic() - started
        self.assertEqual((206, CLIP[:40000]), (status, body))
        self.assertGreaterEqual(elapsed, 0.2)  # 40000 bytes a 128 KB/s: 0,31 s; o último pedaço sai antes disso


class DesistenciaNoMeioTest(VideoTestCase):
    env = {"FAKE_VIDEO_KBPS": "128"}
    stderr = subprocess.PIPE

    def test_quem_desiste_no_meio_do_video_nao_enche_o_log_de_erros(self):
        ana, clip = self.login("ana"), self.clip()["id"]
        cookie = "; ".join(f"{c.name}={c.value}" for c in ana.jar)
        # Buscar noutro ponto fecha a conexão com o corpo pela metade; é o que o player faz a cada busca.
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as sock:
            sock.sendall(f"GET /attachments/{clip} HTTP/1.1\r\nHost: x\r\nCookie: {cookie}\r\n\r\n".encode())
            self.assertIn(b"200 OK", sock.recv(1024))
        # Um pedido inteiro leva mais de 0,4 s no ritmo do fake: o primeiro já notou a desistência (a escrita seguinte falha).
        self.assertEqual(CLIP, ana.fetch(f"/attachments/{clip}")[2])
        self.process.kill()
        log = self.process.communicate()[1].decode()
        self.assertIn(f"GET /attachments/{clip}", log)
        self.assertNotIn("Traceback", log)


class VelocidadeLentaTest(FakeTestCase):
    env = {"FAKE_VIDEO_KBPS": "8"}

    def test_anexo_que_nao_e_video_nao_e_limitado(self):
        # A 8 KB/s um anexo de 64 KB que fosse limitado levaria 8 s e estouraria o prazo de 5 s do cliente.
        ana = self.login("ana")
        papo = self.channel("papo", ana)["id"]
        status, sent = ana.send(papo, "texto grande", file=("grande.txt", "text/plain", "x" * 64000))
        self.assertEqual(201, status)
        self.assertEqual(64000, len(ana.fetch(f"/attachments/{sent['attachments'][0]['id']}")[2]))


class SessaoVencidaTest(VideoTestCase):
    env = {"FAKE_VIDEO_401_ONCE": "2"}

    def test_responde_401_so_no_pedido_escolhido_e_depois_volta_ao_normal(self):
        ana = self.login("ana")
        clip = self.clip()["id"]
        image = next(a for m in ana.call("GET", f"/channels/{self.channel('papo', ana)['id']}/messages?limit=30")[1]["messages"]
                     for a in m["attachments"] if a["mime_type"] == "image/png")
        # Imagem não conta como pedido de vídeo.
        self.assertEqual(200, ana.fetch(f"/attachments/{image['id']}")[0])
        self.assertEqual(206, ana.fetch(f"/attachments/{clip}", {"Range": "bytes=0-9"})[0])
        status, _, _ = ana.fetch(f"/attachments/{clip}", {"Range": "bytes=10-19"})
        self.assertEqual(401, status)
        # Depois do 401 o cliente renova (o fake mantém a sessão) e repete: o mesmo pedido agora passa.
        self.assertEqual(200, ana.call("POST", "/auth/refresh")[0])
        status, headers, body = ana.fetch(f"/attachments/{clip}", {"Range": "bytes=10-19"})
        self.assertEqual((206, CLIP[10:20]), (status, body))
        self.assertEqual(206, ana.fetch(f"/attachments/{clip}", {"Range": "bytes=20-29"})[0])


class PrimeiroPedido401Test(VideoTestCase):
    env = {"FAKE_VIDEO_401_ONCE": "1"}

    def test_o_primeiro_pedido_de_video_e_401_e_o_segundo_passa(self):
        ana = self.login("ana")
        clip = self.clip()["id"]
        self.assertEqual(401, ana.fetch(f"/attachments/{clip}")[0])
        status, _, body = ana.fetch(f"/attachments/{clip}")
        self.assertEqual((200, CLIP), (status, body))


class SemVideosTest(FakeTestCase):
    def test_sem_as_variaveis_nada_muda_no_papo(self):
        ana = self.login("ana")
        _, body = ana.call("GET", f"/channels/{self.channel('papo', ana)['id']}/messages?limit=100")
        self.assertFalse([a for m in body["messages"] for a in m["attachments"] if a["mime_type"].startswith("video/")])

    def test_sem_a_velocidade_o_anexo_vem_de_uma_vez(self):
        ana = self.login("ana")
        papo = self.channel("papo", ana)["id"]
        _, sent = ana.send(papo, "grande", file=("grande.txt", "text/plain", "x" * 64000))
        self.assertEqual(64000, len(ana.fetch(f"/attachments/{sent['attachments'][0]['id']}")[2]))


class EmbedTestCase(VideoTestCase):
    """Fake com os vídeos semeados (`clip.mp4` e `outro.webm`) e `bia` mandando links no `papo`; `ana` observa."""

    CLIP_LINK = "https://videos.example.com/v/clip.mp4"

    def updates_of(self, watcher, message_id):
        """Espera o `message_embeds_update` da mensagem; devolve (os eventos vistos até ele, o evento)."""
        seen = watcher.wait(lambda e: e["type"] == "message_embeds_update" and e["message_id"] == message_id)
        return seen, seen[-1]

    def send_link(self, content=None, author="bia", channel="papo"):
        """Manda a mensagem e espera o embed dela chegar a `ana`; devolve (a mensagem enviada, o primeiro embed)."""
        watcher = self.watch(self.login("ana"))
        sender = self.login(author)
        status, sent = sender.send(self.channel(channel, sender)["id"], content or self.CLIP_LINK)
        self.assertEqual(201, status)
        _, event = self.updates_of(watcher, sent["id"])
        return sent, event["embeds"][0]

    def relay(self, embed, headers=None, who="ana"):
        return self.login(who).fetch(f"/embeds/{embed['id']}/video", headers)


class EmbedDeVideoTest(EmbedTestCase):
    def test_link_vira_embed_depois_da_mensagem_e_a_resposta_do_envio_ainda_nao_o_traz(self):
        watcher = self.watch(self.login("ana"))
        bia = self.login("bia")
        papo = self.channel("papo", bia)["id"]
        status, sent = bia.send(papo, f"olha {self.CLIP_LINK} que legal")
        self.assertEqual((201, []), (status, sent["embeds"]))
        started = time.monotonic()
        seen, event = self.updates_of(watcher, sent["id"])
        # O crawl do servidor roda em segundo plano: a mensagem chega primeiro e o embed ~1 s depois.
        self.assertGreaterEqual(time.monotonic() - started, 0.5)
        self.assertEqual(["message", "message_embeds_update"], [e["type"] for e in seen if e["type"].startswith("message")])
        self.assertEqual(papo, event["channel_id"])
        self.assertEqual(1, len(event["embeds"]))
        embed = event["embeds"][0]
        self.assertEqual(("link", "opengraph", self.CLIP_LINK), (embed["source_type"], embed["fetch_method"], embed["url"]))
        self.assertEqual("clip.mp4", embed["title"])
        self.assertEqual(("video/mp4", self.CLIP_LINK), (embed["video"]["mime_type"], embed["video"]["url"]))
        self.assertEqual("image/png", embed["thumbnail"]["mime_type"])
        # O evento leva só metadados: a imagem se busca à parte.
        self.assertNotIn("image_data", embed)
        # A mesma lista passa a vir no histórico.
        _, body = bia.call("GET", f"/channels/{papo}/messages?limit=30")
        self.assertEqual([embed], next(m for m in body["messages"] if m["id"] == sent["id"])["embeds"])

    def test_mime_do_embed_segue_a_extensao_do_link(self):
        for link, mime in (("https://videos.example.com/outro.webm", "video/webm"), ("https://videos.example.com/x.OGV?t=1", "video/ogg"),
                           ("https://videos.example.com/pagina", "video/mp4")):
            _, embed = self.send_link(link)
            self.assertEqual(mime, embed["video"]["mime_type"], link)

    def test_so_link_https_do_videos_example_com_gera_embed(self):
        watcher = self.watch(self.login("ana"))
        bia = self.login("bia")
        papo = self.channel("papo", bia)["id"]
        for ignored in ("https://outro.example.com/clip.mp4", "http://videos.example.com/clip.mp4",
                        "https://videos.example.com.evil.test/clip.mp4", "sem link"):
            self.assertEqual(201, bia.send(papo, ignored)[0])
        # Um embed de verdade logo depois: se algum dos anteriores gerasse evento, ele chegaria antes.
        _, sent = bia.send(papo, self.CLIP_LINK)
        seen, _ = self.updates_of(watcher, sent["id"])
        self.assertEqual(1, len([e for e in seen if e["type"] == "message_embeds_update"]))

    def test_links_repetidos_viram_um_embed_e_distintos_seguem_a_ordem_do_texto(self):
        watcher = self.watch(self.login("ana"))
        bia = self.login("bia")
        content = "https://videos.example.com/outro.webm e https://videos.example.com/clip.mp4 e https://videos.example.com/outro.webm"
        status, sent = bia.send(self.channel("papo", bia)["id"], content)
        self.assertEqual(201, status)
        _, event = self.updates_of(watcher, sent["id"])
        self.assertEqual(["outro.webm", "clip.mp4"], [e["title"] for e in event["embeds"]])
        self.assertEqual(2, len({e["id"] for e in event["embeds"]}))

    def test_mensagem_apagada_antes_do_fim_do_crawl_nao_gera_evento(self):
        watcher = self.watch(self.login("ana"))
        bia = self.login("bia")
        papo = self.channel("papo", bia)["id"]
        _, gone = bia.send(papo, self.CLIP_LINK)
        self.assertEqual(204, bia.call("DELETE", f"/messages/{gone['id']}")[0])
        _, kept = bia.send(papo, self.CLIP_LINK)
        seen, _ = self.updates_of(watcher, kept["id"])
        self.assertEqual([kept["id"]], [e["message_id"] for e in seen if e["type"] == "message_embeds_update"])


class EmbedDimensoesTest(EmbedTestCase):
    def setUp(self):
        # Um `ffprobe` de mentira na frente do PATH: o fake o chama para ler a largura e a altura de cada vídeo semeado.
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        probe = Path(folder.name, "ffprobe")
        probe.write_text("#!/bin/sh\necho 640,360\n")
        probe.chmod(0o755)
        self.env = {**self.env, "PATH": folder.name + os.pathsep + os.environ.get("PATH", "")}
        super().setUp()

    def test_o_embed_traz_a_largura_e_a_altura_do_video_semeado(self):
        _, embed = self.send_link()
        self.assertEqual((640, 360), (embed["video"]["width"], embed["video"]["height"]))


class EmbedImagemTest(EmbedTestCase):
    def test_embed_vem_com_a_imagem_em_base64(self):
        _, embed = self.send_link()
        status, body = self.login("ana").call("GET", f"/embeds/{embed['id']}")
        self.assertEqual(200, status)
        self.assertEqual(embed["id"], body["id"])
        self.assertTrue(base64.b64decode(body["image_data"]).startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertIsNone(body["author_image_data"])

    def test_embed_que_nao_existe_e_404_e_sem_sessao_e_401(self):
        _, embed = self.send_link()
        self.assertEqual(404, self.login("ana").call("GET", f"/embeds/{uuid.uuid4()}")[0])
        for path in (f"/embeds/{embed['id']}", f"/embeds/{embed['id']}/video"):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=5)
            caught.exception.close()
            self.assertEqual(401, caught.exception.code, path)


class RelayTest(EmbedTestCase):
    def test_sem_range_serve_o_video_semeado_inteiro_com_o_tipo_do_embed(self):
        _, embed = self.send_link()
        status, headers, body = self.relay(embed)
        self.assertEqual((200, CLIP), (status, body))
        self.assertEqual(("video/mp4", "bytes", str(len(CLIP))), (headers["Content-Type"], headers["Accept-Ranges"], headers["Content-Length"]))

    def test_trecho_devolve_206_com_content_range_e_416_fora_do_arquivo(self):
        _, embed = self.send_link()
        status, headers, body = self.relay(embed, {"Range": "bytes=100-199"})
        self.assertEqual((206, CLIP[100:200], "bytes 100-199/64000"), (status, body, headers["Content-Range"]))
        status, headers, _ = self.relay(embed, {"Range": "bytes=64000-"})
        self.assertEqual((416, "bytes */64000"), (status, headers["Content-Range"]))
        self.assertEqual(416, self.relay(embed, {"Range": "bytes=abc"})[0])

    def test_serve_o_semeado_de_mesmo_nome_e_senao_o_primeiro_do_mesmo_tipo(self):
        _, webm = self.send_link("https://videos.example.com/outro.webm")
        status, headers, body = self.relay(webm)
        self.assertEqual((200, "video/webm", OTHER), (status, headers["Content-Type"], body))
        _, unknown = self.send_link("https://videos.example.com/nao-semeado.mp4")
        self.assertEqual(CLIP, self.relay(unknown)[2])

    def test_sem_semente_do_tipo_o_embed_existe_mas_o_relay_responde_404(self):
        # Só há .mp4 e .webm semeados: um link .ogv anuncia video/ogg e não tem o que servir.
        _, embed = self.send_link("https://videos.example.com/teora.ogv")
        self.assertEqual("video/ogg", embed["video"]["mime_type"])
        status, headers, body = self.relay(embed)
        self.assertEqual((404, "application/problem+json", "vídeo de embed não encontrado"),
                         (status, headers["Content-Type"], json.loads(body)["detail"]))

    def test_embed_que_nao_existe_e_404_no_relay(self):
        self.assertEqual(404, self.login("ana").fetch(f"/embeds/{uuid.uuid4()}/video")[0])


class RelayLogTest(EmbedTestCase):
    stderr = subprocess.PIPE

    def test_o_log_mostra_o_pedido_ao_relay_com_o_range_e_o_status(self):
        _, embed = self.send_link()
        self.assertEqual(206, self.relay(embed, {"Range": "bytes=10-19"})[0])
        self.process.kill()
        log = self.process.communicate()[1].decode()
        self.assertIn(f"GET /embeds/{embed['id']}/video Range: bytes=10-19 -> 206", log)


class RelayNaoLidoTest(EmbedTestCase):
    permissions = "demo"

    def test_quem_nao_le_o_canal_nao_ve_o_embed_nem_o_video(self):
        # `equipe` só é lido por Moderação (bia); `ana` é Leitores e nem recebe o evento, então o embed vem pela leitura de bia.
        bia = self.login("bia")
        watcher = self.watch(bia)
        status, sent = bia.send(self.channel("equipe", bia)["id"], self.CLIP_LINK)
        self.assertEqual(201, status)
        _, event = self.updates_of(watcher, sent["id"])
        embed = event["embeds"][0]
        ana = self.login("ana")
        self.assertEqual(404, ana.call("GET", f"/embeds/{embed['id']}")[0])
        self.assertEqual(404, ana.fetch(f"/embeds/{embed['id']}/video")[0])
        self.assertEqual(200, bia.call("GET", f"/embeds/{embed['id']}")[0])
        self.assertEqual(200, bia.fetch(f"/embeds/{embed['id']}/video")[0])


class Relay502Test(EmbedTestCase):
    env = {"FAKE_RELAY_502": "1"}

    def test_relay_responde_502_com_problem_json_e_o_embed_segue_normal(self):
        _, embed = self.send_link()
        for headers in (None, {"Range": "bytes=0-9"}):
            status, response, body = self.relay(embed, headers)
            self.assertEqual((502, "application/problem+json", "Mídia indisponível"),
                             (status, response["Content-Type"], json.loads(body)["title"]))
        # A imagem não passa pelo relay.
        self.assertEqual(200, self.login("ana").call("GET", f"/embeds/{embed['id']}")[0])

    def test_embed_sem_semente_continua_404_e_nao_502(self):
        _, embed = self.send_link("https://videos.example.com/teora.ogv")
        self.assertEqual(404, self.relay(embed)[0])


class Relay502ZeroTest(EmbedTestCase):
    env = {"FAKE_RELAY_502": "0"}

    def test_zero_nao_liga(self):
        _, embed = self.send_link()
        self.assertEqual(200, self.relay(embed)[0])


class RelayVelocidadeTest(EmbedTestCase):
    env = {"FAKE_VIDEO_KBPS": "128"}

    def test_relay_tambem_e_entregue_devagar(self):
        _, embed = self.send_link()
        started = time.monotonic()
        status, _, body = self.relay(embed, {"Range": "bytes=0-39999"})
        elapsed = time.monotonic() - started
        self.assertEqual((206, CLIP[:40000]), (status, body))
        self.assertGreaterEqual(elapsed, 0.2)  # 40000 bytes a 128 KB/s: 0,31 s; o último pedaço sai antes disso


class RelaySessaoVencidaTest(EmbedTestCase):
    env = {"FAKE_VIDEO_401_ONCE": "2"}

    def test_o_pedido_do_relay_conta_entre_os_de_video(self):
        _, embed = self.send_link()
        ana = self.login("ana")
        path = f"/embeds/{embed['id']}/video"
        self.assertEqual(206, ana.fetch(path, {"Range": "bytes=0-9"})[0])
        self.assertEqual(401, ana.fetch(path, {"Range": "bytes=10-19"})[0])
        self.assertEqual(206, ana.fetch(path, {"Range": "bytes=10-19"})[0])


class ArquivoInvalidoTest(unittest.TestCase):
    def run_with(self, spec):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ruim.json"
            path.write_text(spec if isinstance(spec, str) else json.dumps(spec))
            return self.run_path(str(path))

    def run_path(self, path):
        env = {**clean_env(), "FAKE_PERMISSIONS": path, "FAKE_EMOJI_SMALL": "1"}
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


class VideoInvalidoTest(unittest.TestCase):
    def run_with(self, **env):
        done = subprocess.run([sys.executable, str(FAKE), "0"], env={**clean_env(), "FAKE_EMOJI_SMALL": "1", **env},
                              capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, done.returncode)
        return done.stderr

    def test_pasta_inexistente_ou_sem_video_derruba_o_fake_com_mensagem(self):
        self.assertIn("FAKE_VIDEO_DIR: nenhum .mp4, .webm ou .ogv em /caminho/que/nao/existe", self.run_with(FAKE_VIDEO_DIR="/caminho/que/nao/existe"))
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "leia-me.txt").write_text("sem vídeo")
            self.assertIn("FAKE_VIDEO_DIR: nenhum", self.run_with(FAKE_VIDEO_DIR=folder))

    def test_velocidade_ou_pedido_ilegivel_derruba_o_fake_com_mensagem(self):
        self.assertIn("FAKE_VIDEO_KBPS e FAKE_VIDEO_401_ONCE: valor ilegível", self.run_with(FAKE_VIDEO_KBPS="rápido"))
        self.assertIn("FAKE_VIDEO_KBPS e FAKE_VIDEO_401_ONCE: valor ilegível", self.run_with(FAKE_VIDEO_401_ONCE="uma vez"))


if __name__ == "__main__":
    unittest.main()
