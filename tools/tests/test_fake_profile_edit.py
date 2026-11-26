"""Testes da edição do próprio perfil em tools/dev/fake_papo.py: `PUT /users/{id}`, `/avatar`, `/banner` e `/status`.
Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre (ver `FakeTestCase`), então nenhum depende do estado deixado por outro.
"""

import base64
import hashlib
import http.client
import struct
import unittest

from test_fake_papo import FakeTestCase, raw_call


def png(width=64, height=64, extra=0):
    """Um PNG que só tem o cabeçalho: o fake lê o formato e as medidas, não decodifica a imagem. `extra` bytes de enchimento."""
    ihdr = struct.pack(">II", width, height) + b"\x08\x06\x00\x00\x00"
    return b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + ihdr + b"\x00" * 4 + b"\x00" * extra


def gif(width, height):
    return b"GIF89a" + struct.pack("<HH", width, height) + b"\x00\x00\x00;"


def jpeg(width, height):
    sof = b"\x08" + struct.pack(">HH", height, width) + b"\x03" + b"\x01\x22\x00\x02\x11\x01\x03\x11\x01"
    return b"\xff\xd8\xff\xc0" + struct.pack(">H", len(sof) + 2) + sof + b"\xff\xd9"


def webp_lossless(width, height):
    bits = (width - 1) | ((height - 1) << 14)
    return b"RIFF" + struct.pack("<I", 20) + b"WEBP" + b"VP8L" + struct.pack("<I", 5) + b"\x2f" + struct.pack("<I", bits) + b"\x00" * 4


def webp_extended(width, height):
    return (b"RIFF" + struct.pack("<I", 30) + b"WEBP" + b"VP8X" + struct.pack("<I", 10) + b"\x10\x00\x00\x00"
            + (width - 1).to_bytes(3, "little") + (height - 1).to_bytes(3, "little"))


def webp_lossy(width, height):
    return (b"RIFF" + struct.pack("<I", 30) + b"WEBP" + b"VP8 " + struct.pack("<I", 10) + b"\x00\x00\x00\x9d\x01\x2a"
            + struct.pack("<HH", width, height))


def b64(data):
    return base64.b64encode(data).decode()


TEXT = {"nickname": "Ana Lima", "status": "Em reunião", "description": "Desenho telas."}


class PerfilTextoTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia = self.login("ana"), self.login("bia")

    def put(self, body, who=None):
        who = who or self.ana
        return who.call("PUT", f"/users/{who.id}", body)

    def profile(self, who=None):
        who = who or self.ana
        status, body = self.bia.call("GET", f"/users/{who.id}/profile")
        self.assertEqual(200, status)
        return body

    def test_grava_e_o_perfil_e_a_lista_de_usuarios_devolvem_o_que_foi_gravado(self):
        status, body = self.put({"nickname": "Ana L.", "status": "Livre", "description": "Nova descrição", "typing": "pensando…"})

        self.assertEqual(200, status)
        self.assertIn("response", body)
        profile = self.profile()
        self.assertEqual(("Ana L.", "Livre", "Nova descrição", "pensando…"),
                         (profile["nickname"], profile["status_message"], profile["description"], profile["typing"]))
        _, listing = self.bia.call("GET", "/users")
        self.assertEqual("Ana L.", next(u for u in listing["users"] if u["username"] == "ana")["nickname"])

    def test_campos_vazios_limpam_o_valor(self):
        self.put({"nickname": "", "status": "", "description": ""})

        profile = self.profile()
        self.assertIsNone(profile["nickname"])
        self.assertIsNone(profile["status_message"])
        self.assertIsNone(profile["description"])

    def test_digitacao_ausente_ou_nula_nao_muda_e_vazia_limpa(self):
        self.put({**TEXT, "typing": "escrevendo…"})
        self.put(TEXT)
        self.assertEqual("escrevendo…", self.profile()["typing"])
        self.put({**TEXT, "typing": None})
        self.assertEqual("escrevendo…", self.profile()["typing"])
        self.put({**TEXT, "typing": ""})
        self.assertIsNone(self.profile()["typing"])

    def test_os_tres_campos_sao_obrigatorios_e_nada_muda_numa_recusa(self):
        before = self.profile()
        for missing in ("nickname", "status", "description"):
            body = {k: v for k, v in TEXT.items() if k != missing}
            status, problem = self.put(body)
            self.assertEqual(400, status, missing)
            self.assertEqual(400, problem["status"])
            self.assertIn(missing, problem["detail"])
        self.assertEqual(400, self.put({**TEXT, "nickname": 5})[0])
        self.assertEqual(400, self.put({**TEXT, "typing": 5})[0])
        self.assertEqual(before, self.profile())

    def test_limites_em_code_points(self):
        limits = {"nickname": 32, "status": 64, "description": 512, "typing": 64}
        for field, limit in limits.items():
            self.assertEqual(200, self.put({**TEXT, field: "a" * limit})[0], field)
            status, problem = self.put({**TEXT, field: "a" * (limit + 1)})
            self.assertEqual(400, status, field)
            self.assertIn(field, problem["detail"])
            self.assertIn(str(limit), problem["detail"])

    def test_emoji_conta_um_e_nao_dois(self):
        self.assertEqual(200, self.put({**TEXT, "nickname": "😀" * 32})[0])
        self.assertEqual(400, self.put({**TEXT, "nickname": "😀" * 33})[0])

    def test_a_recusa_vem_como_problem_json(self):
        status, headers, body = raw_call(self.port, "PUT", f"/users/{self.ana.id}", self.ana.auth(), body={"nickname": "a"})

        self.assertEqual(400, status)
        self.assertIn("application/problem+json", headers["Content-Type"])
        self.assertEqual(400, body["status"])

    def test_so_o_proprio_usuario_edita(self):
        status, body = self.ana.call("PUT", f"/users/{self.bia.id}", TEXT)

        self.assertEqual(403, status)
        self.assertEqual("Bia Borges", self.profile(self.bia)["nickname"])
        self.assertEqual(403, self.ana.call("PUT", "/users/nao-existe", TEXT)[0])

    def test_sem_sessao_e_401(self):
        status, _, _ = raw_call(self.port, "PUT", f"/users/{self.ana.id}", None, body=TEXT)

        self.assertEqual(401, status)

    def test_os_outros_recebem_presence_update_com_o_que_mudou(self):
        watcher = self.watch(self.bia)

        self.put({"nickname": "Ana L.", "status": "Livre", "description": "x", "typing": "pensando…"})
        seen = watcher.wait(lambda e: e["type"] == "presence_update" and e["user_id"] == self.ana.id and e["nickname"] == "Ana L.")

        event = seen[-1]
        self.assertEqual(("Livre", "pensando…"), (event["status_message"], event["typing"]))
        # A Ana não tem conexão aberta: aparece offline, como o servidor mostra quem não está conectado.
        self.assertEqual("offline", event["status"])

    def test_corpo_acima_de_4_mib_e_413_sem_ler_o_corpo(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        self.addCleanup(connection.close)
        connection.putrequest("PUT", f"/users/{self.ana.id}")
        connection.putheader("Cookie", f"Auth={self.ana.auth()}")
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Content-Length", str(5 * 1024 * 1024))
        connection.endheaders()

        response = connection.getresponse()
        response.read()

        self.assertEqual(413, response.status)


class PerfilImagensTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia = self.login("ana"), self.login("bia")

    def avatar(self, data, fmt="PNG", who=None):
        who = who or self.ana
        return who.call("PUT", f"/users/{who.id}/avatar", {"avatar": data if isinstance(data, str) else b64(data), "avatar_format": fmt})

    def banner(self, data, fmt="PNG"):
        return self.ana.call("PUT", f"/users/{self.ana.id}/banner", {"banner": data if isinstance(data, str) else b64(data), "banner_format": fmt})

    def profile(self):
        return self.bia.call("GET", f"/users/{self.ana.id}/profile")[1]

    def test_avatar_valido_aparece_no_perfil_e_na_rota_de_avatar(self):
        image = png(512, 512)

        status, _ = self.avatar(image)

        self.assertEqual(200, status)
        profile = self.profile()
        self.assertEqual("PNG", profile["avatar_format"])
        self.assertEqual(image, base64.b64decode(profile["avatar_blob"]))
        code, headers, body = self.bia.fetch(f"/users/{self.ana.id}/avatar")
        self.assertEqual((200, "image/png", image), (code, headers["Content-Type"], body))

    def test_cada_formato_e_lido_pelos_bytes_e_serve_com_o_tipo_certo(self):
        samples = {"GIF": gif(64, 64), "JPEG": jpeg(64, 64), "PNG": png(), "WEBP": webp_lossless(64, 64)}
        for fmt, data in samples.items():
            self.assertEqual(200, self.avatar(data, fmt)[0], fmt)
            self.assertEqual(fmt, self.profile()["avatar_format"])
            self.assertEqual("image/" + fmt.lower(), self.bia.fetch(f"/users/{self.ana.id}/avatar")[1]["Content-Type"])
        self.assertEqual(200, self.avatar(webp_extended(64, 64), "WEBP")[0])
        self.assertEqual(200, self.avatar(webp_lossy(64, 64), "WEBP")[0])
        self.assertEqual(200, self.avatar(jpeg(64, 64), "JPG")[0])

    def test_limite_de_512_px_no_avatar_vale_para_cada_formato(self):
        makers = {"PNG": png, "GIF": gif, "JPEG": jpeg, "WEBP": webp_lossless}
        for fmt, make in makers.items():
            self.assertEqual(200, self.avatar(make(512, 512), fmt)[0], fmt)
            for width, height in ((513, 100), (100, 513)):
                status, problem = self.avatar(make(width, height), fmt)
                self.assertEqual(400, status, (fmt, width, height))
                self.assertIn("512", problem["detail"])
        self.assertEqual(400, self.avatar(webp_extended(513, 10), "WEBP")[0])
        self.assertEqual(400, self.avatar(webp_lossy(513, 10), "WEBP")[0])

    def test_avatar_recusado_nao_muda_o_que_estava(self):
        self.avatar(png(10, 10))
        before = self.profile()["avatar_blob"]
        cases = [
            ("não é base64", "isto não é base64!!", "PNG"),
            ("formato desconhecido", b64(png()), "BMP"),
            ("não é imagem", b64(b"texto qualquer"), "PNG"),
            ("vazio (remover o avatar não é documentado)", "", "PNG"),
            ("passa de 2 MiB", b64(png(extra=2 * 1024 * 1024)), "PNG"),
            ("passa de 512 px", b64(png(600, 600)), "PNG"),
        ]
        for label, data, fmt in cases:
            status, problem = self.avatar(data, fmt)
            self.assertEqual(400, status, label)
            self.assertEqual("Parâmetro inválido", problem["title"], label)
        self.assertEqual(before, self.profile()["avatar_blob"])
        self.assertEqual(400, self.ana.call("PUT", f"/users/{self.ana.id}/avatar", {"avatar": b64(png())})[0])

    def test_exatamente_2_mib_passa_e_um_byte_a_mais_nao(self):
        base = len(png())
        self.assertEqual(200, self.avatar(png(extra=2 * 1024 * 1024 - base))[0])
        self.assertEqual(400, self.avatar(png(extra=2 * 1024 * 1024 - base + 1))[0])

    def test_avatar_manda_avatar_update_aos_outros(self):
        watcher = self.watch(self.bia)

        self.avatar(png())
        seen = watcher.wait(lambda e: e["type"] == "avatar_update")

        self.assertEqual(self.ana.id, seen[-1]["user_id"])

    def test_avatar_recusado_nao_manda_evento(self):
        watcher = self.watch(self.bia)
        self.avatar(b"nao")
        self.ana.call("PUT", f"/users/{self.ana.id}", {"nickname": "Marca", "status": "", "description": ""})

        seen = watcher.wait(lambda e: e["type"] == "presence_update" and e.get("nickname") == "Marca")

        self.assertNotIn("avatar_update", [e["type"] for e in seen])

    def test_so_o_proprio_usuario_troca_o_avatar_e_o_banner(self):
        self.assertEqual(403, self.ana.call("PUT", f"/users/{self.bia.id}/avatar", {"avatar": b64(png()), "avatar_format": "PNG"})[0])
        self.assertEqual(403, self.ana.call("PUT", f"/users/{self.bia.id}/banner", {"banner": b64(png()), "banner_format": "PNG"})[0])
        self.assertEqual(403, self.ana.call("PUT", f"/users/{self.bia.id}/status", {"status": "busy"})[0])

    def test_banner_valido_vira_midia_enderecada_pelo_conteudo(self):
        image = png(2048, 2048)

        self.assertEqual(200, self.banner(image)[0])

        sha = self.profile()["banner_media"]
        self.assertEqual(hashlib.sha256(image).hexdigest(), sha)
        code, headers, body = self.bia.fetch(f"/media/{sha}")
        self.assertEqual((200, "image/png", image), (code, headers["Content-Type"], body))

    def test_banner_acima_de_2048_px_ou_invalido_e_recusado(self):
        self.assertEqual(400, self.banner(png(2049, 100))[0])
        self.assertEqual(400, self.banner(png(100, 2049))[0])
        self.assertEqual(400, self.banner("###", "PNG")[0])
        self.assertEqual(400, self.banner(b64(b"x"), "PNG")[0])
        self.assertEqual(400, self.banner(png(), "BMP")[0])
        self.assertIsNone(self.profile()["banner_media"])

    def test_banner_vazio_com_formato_vazio_remove(self):
        self.banner(png(1536, 512))
        self.assertIsNotNone(self.profile()["banner_media"])

        status, _ = self.banner("", "")

        self.assertEqual(200, status)
        self.assertIsNone(self.profile()["banner_media"])
        # Remover de novo, sem banner, também dá certo (idempotente).
        self.assertEqual(200, self.banner("", "")[0])

    def test_banner_nao_tem_evento(self):
        watcher = self.watch(self.bia)
        self.banner(png())
        self.ana.call("PUT", f"/users/{self.ana.id}", {"nickname": "Marca", "status": "", "description": ""})

        seen = watcher.wait(lambda e: e["type"] == "presence_update" and e.get("nickname") == "Marca")

        self.assertEqual(["presence_update"], [e["type"] for e in seen])


class PerfilStatusTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia = self.login("ana"), self.login("bia")

    def status(self, value, who=None):
        who = who or self.ana
        return who.call("PUT", f"/users/{who.id}/status", {"status": value})

    def profile_status(self):
        return self.bia.call("GET", f"/users/{self.ana.id}/profile")[1]["status"]

    def test_ausente_ocupado_e_nulo_ficam_no_perfil(self):
        for value in ("away", "busy", None):
            self.assertEqual(200, self.status(value)[0], value)
            self.assertEqual(value, self.profile_status())

    def test_corpo_sem_o_campo_tira_o_status_como_o_nulo(self):
        self.status("busy")

        status, _ = self.ana.call("PUT", f"/users/{self.ana.id}/status", {})

        self.assertEqual(200, status)
        self.assertIsNone(self.profile_status())

    def test_qualquer_outro_valor_e_400_e_nao_muda(self):
        self.status("busy")
        for value in ("invisible", "online", "offline", "", 3):
            status, problem = self.status(value)
            self.assertEqual(400, status, value)
            self.assertIn("away", problem["detail"])
        self.assertEqual("busy", self.profile_status())

    def test_os_outros_recebem_presence_update_com_o_status(self):
        watcher_bia = self.watch(self.bia)
        self.watch(self.ana)

        self.status("busy")
        busy = watcher_bia.wait(lambda e: e["type"] == "presence_update" and e["user_id"] == self.ana.id and e["status"] == "busy")
        self.status(None)
        free = watcher_bia.wait(lambda e: e["type"] == "presence_update" and e["user_id"] == self.ana.id and e["status"] == "online")

        self.assertEqual("busy", busy[-1]["status"])
        self.assertEqual("online", free[-1]["status"])

    def test_o_status_escolhido_vale_na_proxima_conexao(self):
        watcher_bia = self.watch(self.bia)
        self.status("away")

        self.watch(self.ana)
        seen = watcher_bia.wait(lambda e: e["type"] == "presence_update" and e["user_id"] == self.ana.id and e["status"] != "offline")

        self.assertEqual("away", seen[-1]["status"])


if __name__ == "__main__":
    unittest.main()
