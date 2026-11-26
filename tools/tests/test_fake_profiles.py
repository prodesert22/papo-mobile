"""Testes dos perfis, da mídia e dos webhooks de tools/dev/fake_papo.py. Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre (ver `FakeTestCase`), então nenhum depende do estado deixado por outro.
"""

import base64
import hashlib
import re
import unittest
import uuid

from test_fake_papo import FakeTestCase, raw_call

PNG = b"\x89PNG\r\n\x1a\n"


class PerfilTest(FakeTestCase):
    def profile(self, viewer, target):
        """O perfil de `target` visto por `viewer`: (status, corpo)."""
        return viewer.call("GET", f"/users/{target.id}/profile")

    def setUp(self):
        super().setUp()
        self.ana, self.bia, self.caio = self.login("ana"), self.login("bia"), self.login("caio")

    def test_perfil_tem_a_forma_da_openapi(self):
        status, body = self.profile(self.ana, self.bia)

        self.assertEqual(200, status)
        self.assertEqual(self.bia.id, body["id"])
        self.assertEqual("bia", body["username"])
        self.assertEqual("Bia Borges", body["nickname"])
        self.assertEqual("Revisando o deploy", body["status_message"])
        self.assertIn("https://papo.example.com/bia", body["description"])
        self.assertEqual("PNG", body["avatar_format"])
        self.assertTrue(base64.b64decode(body["avatar_blob"]).startswith(PNG))
        self.assertRegex(body["banner_media"], r"^[0-9a-f]{64}$")
        self.assertEqual(["Admin"], [r["name"] for r in body["roles"]])
        self.assertEqual("#E86CA5", body["roles"][0]["color"])
        self.assertRegex(body["created_at"], r"^\d{4}-\d\d-\d\dT")

    def test_outros_usuarios_semeados_tem_apelido_recado_descricao_e_cargo(self):
        _, ana = self.profile(self.bia, self.ana)
        _, caio = self.profile(self.bia, self.caio)

        self.assertEqual("Ana Lima", ana["nickname"])
        self.assertEqual("busy", ana["status"])
        self.assertEqual("Em reunião até as 15h", ana["status_message"])
        self.assertEqual(["Design"], [r["name"] for r in ana["roles"]])
        self.assertEqual("away", caio["status"])
        self.assertEqual("De férias", caio["status_message"])
        self.assertEqual("Volto na segunda.", caio["description"])
        self.assertEqual([], caio["roles"])

    def test_o_apelido_do_perfil_e_o_da_lista_de_usuarios(self):
        _, listing = self.ana.call("GET", "/users")
        by_name = {u["username"]: u for u in listing["users"]}

        self.assertEqual("Ana Lima", by_name["ana"]["nickname"])
        self.assertEqual(["Design"], [r["name"] for r in by_name["ana"]["roles"]])
        self.assertIsNone(by_name["caio"]["nickname"])

    def test_sem_banner_nem_avatar_os_campos_ficam_de_fora_ou_nulos(self):
        _, body = self.profile(self.bia, self.caio)

        self.assertIsNone(body["banner_media"])
        self.assertNotIn("avatar_blob", body)
        self.assertNotIn("avatar_format", body)

    def test_usuario_que_nao_existe_e_404_e_sem_sessao_e_401(self):
        self.assertEqual(404, self.ana.call("GET", f"/users/{uuid.uuid4()}/profile")[0])
        status, _, body = raw_call(self.port, "GET", f"/users/{self.bia.id}/profile")
        self.assertEqual(401, status)
        self.assertEqual(401, body["status"])

    def test_usuario_registrado_depois_tem_perfil_basico(self):
        status, body = self.ana.call("POST", "/auth/register", {"username": "novo", "password": "Senha@999"})
        self.assertEqual(201, status)

        status, profile = self.ana.call("GET", f"/users/{body['id']}/profile")

        self.assertEqual(200, status)
        self.assertEqual("novo", profile["username"])
        self.assertIsNone(profile["description"])
        self.assertEqual([], profile["roles"])

    def test_banner_e_servido_pelo_sha_com_sessao(self):
        _, profile = self.profile(self.ana, self.bia)
        sha = profile["banner_media"]

        status, head, data = raw_call(self.port, "GET", f"/media/{sha}", self.ana.auth())

        self.assertEqual(200, status)
        self.assertEqual("image/png", head["Content-Type"])
        self.assertTrue(data.startswith(PNG))
        self.assertEqual(sha, hashlib.sha256(data).hexdigest())

    def test_midia_sem_sessao_e_401_e_sha_desconhecido_e_404(self):
        _, profile = self.profile(self.ana, self.bia)

        self.assertEqual(401, raw_call(self.port, "GET", f"/media/{profile['banner_media']}")[0])
        self.assertEqual(404, raw_call(self.port, "GET", f"/media/{'0' * 64}", self.ana.auth())[0])

    def test_avatar_de_quem_tem_e_servido_e_o_de_quem_nao_tem_segue_404(self):
        _, profile = self.profile(self.ana, self.bia)

        status, head, data = raw_call(self.port, "GET", f"/users/{self.bia.id}/avatar", self.ana.auth())

        self.assertEqual(200, status)
        self.assertEqual("image/png", head["Content-Type"])
        self.assertEqual(base64.b64decode(profile["avatar_blob"]), data)
        self.assertEqual(404, raw_call(self.port, "GET", f"/users/{self.caio.id}/avatar", self.ana.auth())[0])
        self.assertEqual(404, raw_call(self.port, "GET", f"/users/{uuid.uuid4()}/avatar", self.ana.auth())[0])


class WebhooksTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana = self.login("ana")
        _, body = self.ana.call("GET", f"/channels/{self.channel('papo', self.ana)['id']}/messages?limit=100")
        self.webhook_messages = [m for m in body["messages"] if m["webhook_id"]]

    def by_author(self, name):
        return [m for m in self.webhook_messages if m["external_author"]["name"] == name]

    def test_cada_webhook_tem_um_id_estavel_nas_suas_mensagens(self):
        monitor = self.by_author("Monitor de Status")

        self.assertEqual(2, len(monitor))
        self.assertEqual(1, len({m["webhook_id"] for m in monitor}))
        ids = {m["external_author"]["name"]: m["webhook_id"] for m in self.webhook_messages}
        self.assertEqual({"Robô de Deploy", "Monitor de Status", "Maria da Ponte"}, set(ids))
        self.assertEqual(3, len(set(ids.values())))
        for webhook_id in ids.values():
            self.assertEqual(webhook_id, str(uuid.UUID(webhook_id)))

    def test_o_id_do_webhook_e_o_mesmo_a_cada_partida(self):
        first = {m["external_author"]["name"]: m["webhook_id"] for m in self.webhook_messages}
        self.stop()
        self.setUp()

        again = {m["external_author"]["name"]: m["webhook_id"] for m in self.webhook_messages}

        self.assertEqual(first, again)

    def test_webhook_de_avatar_persistente_aponta_para_a_rota_publica_dele(self):
        message = self.by_author("Monitor de Status")[0]
        webhook_id = message["webhook_id"]

        self.assertEqual(f"/webhooks/{webhook_id}/avatar", message["external_author"]["avatar_url"])
        # Sem cookie: a rota é pública, como na OpenAPI.
        status, head, data = raw_call(self.port, "GET", f"/webhooks/{webhook_id}/avatar")
        self.assertEqual(200, status)
        self.assertEqual("image/png", head["Content-Type"])
        self.assertTrue(data.startswith(PNG))

    def test_webhook_sem_avatar_nao_tem_avatar_url_e_a_rota_responde_404(self):
        message = self.by_author("Robô de Deploy")[0]

        self.assertNotIn("avatar_url", message["external_author"])
        status, _, body = raw_call(self.port, "GET", f"/webhooks/{message['webhook_id']}/avatar")
        self.assertEqual(404, status)
        self.assertEqual(404, body["status"])

    def test_autor_temporario_traz_url_externa_que_o_fake_nao_serve(self):
        message = self.by_author("Maria da Ponte")[0]
        url = message["external_author"]["avatar_url"]

        self.assertRegex(url, r"^https://[^/]*example[^/]*/")
        self.assertEqual(404, raw_call(self.port, "GET", f"/webhooks/{message['webhook_id']}/avatar")[0])

    def test_webhook_desconhecido_e_404(self):
        self.assertEqual(404, raw_call(self.port, "GET", f"/webhooks/{uuid.uuid4()}/avatar")[0])

    def test_mensagens_seguidas_do_mesmo_webhook_ficam_juntas_no_historico(self):
        _, body = self.ana.call("GET", f"/channels/{self.channel('papo', self.ana)['id']}/messages?limit=100")
        order = [m["external_author"]["name"] if m["webhook_id"] else None for m in reversed(body["messages"])]
        start = order.index("Monitor de Status")

        self.assertEqual(["Monitor de Status", "Monitor de Status"], order[start:start + 2])

    def test_a_busca_devolve_o_mesmo_webhook_id(self):
        _, found = self.ana.call("POST", "/search", {"text": "Latência"})
        hit = next(r for r in found["results"] if r["webhook_id"])

        self.assertEqual(self.by_author("Monitor de Status")[0]["webhook_id"], hit["webhook_id"])
        self.assertTrue(re.fullmatch(r"/webhooks/[0-9a-f-]{36}/avatar", hit["external_author"]["avatar_url"]))


if __name__ == "__main__":
    unittest.main()
