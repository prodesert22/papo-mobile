"""Testes de bloqueios, troca de senha, redefinição por link e informações do servidor de tools/dev/fake_papo.py.

Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre, então nenhum depende do estado deixado por outro.
"""

import base64
import os
import socket
import unittest
import uuid

from test_fake_papo import PASSWORDS, FakeTestCase, raw_call

NOVA = "Nova@Senha1"


class BloqueiosTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia, self.caio = self.login("ana"), self.login("bia"), self.login("caio")

    def dm_ids(self, session):
        status, body = session.call("GET", "/dms")
        self.assertEqual(200, status)
        return [d["id"] for d in body["dms"]]

    def test_lista_comeca_vazia_e_cada_usuario_tem_a_sua(self):
        self.assertEqual((200, {"users": []}), self.ana.call("GET", "/users/blocks"))

        self.assertEqual(204, self.ana.call("POST", f"/users/{self.caio.id}/block")[0])

        _, mine = self.ana.call("GET", "/users/blocks")
        self.assertEqual([self.caio.id], [u["id"] for u in mine["users"]])
        self.assertEqual("caio", mine["users"][0]["username"])
        self.assertEqual([], self.bia.call("GET", "/users/blocks")[1]["users"])

    def test_bloquear_e_desbloquear_sao_idempotentes(self):
        for _ in range(2):
            self.assertEqual(204, self.ana.call("POST", f"/users/{self.caio.id}/block")[0])
        self.assertEqual(1, len(self.ana.call("GET", "/users/blocks")[1]["users"]))

        for _ in range(2):
            self.assertEqual(204, self.ana.call("DELETE", f"/users/{self.caio.id}/block")[0])
        self.assertEqual([], self.ana.call("GET", "/users/blocks")[1]["users"])

    def test_nao_bloqueia_a_si_mesmo_nem_quem_nao_existe(self):
        self.assertEqual(400, self.ana.call("POST", f"/users/{self.ana.id}/block")[0])
        self.assertEqual(404, self.ana.call("POST", f"/users/{uuid.uuid4()}/block")[0])
        self.assertEqual(404, self.ana.call("DELETE", f"/users/{uuid.uuid4()}/block")[0])

    def test_sem_sessao_e_401(self):
        self.assertEqual(401, raw_call(self.port, "GET", "/users/blocks")[0])
        self.assertEqual(401, raw_call(self.port, "POST", f"/users/{self.bia.id}/block")[0])

    def test_dm_bloqueada_some_da_lista_dos_dois_lados_e_desbloquear_a_traz_de_volta(self):
        dm = self.dm_ids(self.ana)
        self.assertEqual(1, len(dm))

        self.ana.call("POST", f"/users/{self.bia.id}/block")

        self.assertEqual([], self.dm_ids(self.ana))
        self.assertEqual([], self.dm_ids(self.bia))

        self.ana.call("DELETE", f"/users/{self.bia.id}/block")
        # No servidor de verdade desbloquear não reabre uma DM fechada pelo app; a do fake nunca foi fechada.
        self.assertEqual(dm, self.dm_ids(self.ana))

    def test_abrir_dm_com_bloqueio_em_qualquer_sentido_e_403_dm_blocked(self):
        self.ana.call("POST", f"/users/{self.caio.id}/block")

        status, body = self.ana.call("POST", "/dms", {"user_id": self.caio.id})
        self.assertEqual(403, status)
        self.assertTrue(body["type"].endswith("/dm-blocked"), body["type"])
        status, body = self.caio.call("POST", "/dms", {"user_id": self.ana.id})
        self.assertEqual(403, status)
        self.assertTrue(body["type"].endswith("/dm-blocked"))

        self.ana.call("DELETE", f"/users/{self.caio.id}/block")
        self.assertEqual(200, self.caio.call("POST", "/dms", {"user_id": self.ana.id})[0])

    def test_enviar_na_dm_bloqueada_e_403_dm_blocked_e_nao_cria_a_mensagem(self):
        dm = self.dm_ids(self.ana)[0]
        self.assertEqual(201, self.ana.send(dm, "antes")[0])
        self.bia.call("POST", f"/users/{self.ana.id}/block")

        status, body = self.ana.send(dm, "depois")
        self.assertEqual(403, status)
        self.assertTrue(body["type"].endswith("/dm-blocked"))

        self.bia.call("DELETE", f"/users/{self.ana.id}/block")
        _, listing = self.ana.call("GET", f"/channels/{dm}/messages?limit=100")
        self.assertNotIn("depois", [m["content"] for m in listing["messages"]])

    def test_mensagens_em_canais_continuam_com_bloqueio(self):
        papo = self.channel("papo", self.ana)["id"]
        self.ana.call("POST", f"/users/{self.caio.id}/block")

        self.assertEqual(201, self.caio.send(papo, "no canal pode")[0])
        _, listing = self.ana.call("GET", f"/channels/{papo}/messages?limit=5")
        self.assertIn("no canal pode", [m["content"] for m in listing["messages"]])


class InformacoesDoServidorTest(FakeTestCase):
    def test_server_traz_contagens_e_criacao(self):
        status, body = raw_call(self.port, "GET", "/server")[0::2]

        self.assertEqual(200, status)
        self.assertEqual(2, body["role_count"])   # Admin, de `bia`, e Design, de `ana`
        self.assertEqual("2026-01-01T08:00:00Z", body["created_at"])
        self.assertEqual(3, body["channel_count"])
        self.assertEqual(3, body["member_count"])


class InformacoesDoServidorComCargosTest(FakeTestCase):
    permissions = "demo"

    def test_com_permissoes_conta_os_cargos_do_cenario(self):
        body = raw_call(self.port, "GET", "/server")[2]
        self.assertEqual(2, body["role_count"])


class TrocaDeSenhaTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana = self.login("ana")

    def test_sem_o_reset_antes_e_409_no_reset_password(self):
        status, body = self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})

        self.assertEqual(409, status)
        self.assertTrue(body["type"].endswith("/no-reset-password"), body["type"])
        self.assertEqual(200, self.ana.call("GET", "/auth/whoami")[0])   # nada mudou, a sessão segue

    def test_reset_e_depois_a_senha_troca_revoga_a_sessao_e_a_nova_senha_entra(self):
        self.assertEqual(200, self.ana.call("POST", f"/users/{self.ana.id}/reset")[0])
        old = self.ana.auth()

        status, _ = self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})

        self.assertEqual(200, status)
        # A sessão atual cai (401, e o refresh também), sem marcar violação.
        self.assertEqual(401, raw_call(self.port, "GET", "/auth/whoami", token=old)[0])
        self.assertEqual(401, raw_call(self.port, "POST", "/auth/refresh", token=old)[0])
        self.assertEqual(401, raw_call(self.port, "POST", "/auth/login", body={"username": "ana", "password": PASSWORDS["ana"]})[0])
        status, _, body = raw_call(self.port, "POST", "/auth/login", body={"username": "ana", "password": NOVA})
        self.assertEqual(200, status)
        self.assertFalse(body["connection_violation"])

    def test_a_marca_do_reset_vale_uma_vez(self):
        self.ana.call("POST", f"/users/{self.ana.id}/reset")
        self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})

        _, head, _ = raw_call(self.port, "POST", "/auth/login", body={"username": "ana", "password": NOVA})
        token = head["Set-Cookie"].partition("=")[2].partition(";")[0]
        status, _, body = raw_call(self.port, "PUT", f"/users/{self.ana.id}/password", token=token, body={"password": "Outra@Senha2"})

        self.assertEqual(409, status)
        self.assertTrue(body["type"].endswith("/no-reset-password"))

    def test_politica_fora_dos_limites_e_400_e_nao_gasta_a_marca(self):
        self.ana.call("POST", f"/users/{self.ana.id}/reset")

        for fraca in ("Ab@1", "semmaiuscula@1", "SemSimbolo1234"):
            status, body = self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": fraca})
            self.assertEqual(400, status, fraca)
            self.assertIn("política", body["detail"])
        self.assertEqual(400, self.ana.call("PUT", f"/users/{self.ana.id}/password", {})[0])
        self.assertEqual(200, self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})[0])

    def test_so_a_propria_senha_e_usuario_inexistente_e_404(self):
        bia = self.login("bia")
        self.assertEqual(403, self.ana.call("PUT", f"/users/{bia.id}/password", {"password": NOVA})[0])
        self.assertEqual(404, self.ana.call("POST", f"/users/{uuid.uuid4()}/reset")[0])
        self.assertEqual(403, self.ana.call("POST", f"/users/{bia.id}/reset")[0])   # sem ser dono, não gera link

    def test_outras_sessoes_do_usuario_tambem_caem_e_as_dos_outros_ficam(self):
        other = self.login("ana")
        bia = self.login("bia")
        self.ana.call("POST", f"/users/{self.ana.id}/reset")
        self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})

        self.assertEqual(401, other.call("GET", "/auth/whoami")[0])
        self.assertEqual(200, bia.call("GET", "/auth/whoami")[0])

    def test_o_websocket_do_usuario_e_fechado(self):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        self.addCleanup(sock.close)
        cookie = f"Auth={self.ana.auth()}"
        key = base64.b64encode(os.urandom(16)).decode()
        sock.sendall((f"GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nCookie: {cookie}\r\n\r\n").encode())
        self.assertIn(b" 101 ", sock.recv(4096))
        bia = self.login("bia")

        self.ana.call("POST", f"/users/{self.ana.id}/reset")
        self.ana.call("PUT", f"/users/{self.ana.id}/password", {"password": NOVA})

        data = b""
        while True:   # o quadro de fechamento, e depois o fim da conexão
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
        self.assertIn(b"\x88\x00", data)
        self.assertEqual(200, bia.call("GET", "/auth/whoami")[0])


class RedefinirComLinkTest(FakeTestCase):
    permissions = "demo"

    def consume(self, token, password=NOVA):
        status, _, body = raw_call(self.port, "POST", "/auth/password_reset", body={"token": token, "password": password})
        return status, body

    def test_token_semeado_troca_a_senha_uma_vez_e_depois_e_410(self):
        ana = self.login("ana")

        self.assertEqual(200, self.consume("seed-valido-ana")[0])
        self.assertEqual(410, self.consume("seed-valido-ana", "Outra@Senha2")[0])
        self.assertEqual(401, ana.call("GET", "/auth/whoami")[0])   # as sessões dela foram revogadas
        self.assertEqual(200, raw_call(self.port, "POST", "/auth/login", body={"username": "ana", "password": NOVA})[0])

    def test_usado_expirado_e_desconhecido_sao_410(self):
        for token in ("seed-usado", "seed-expirado", "nao-existe"):
            self.assertEqual(410, self.consume(token)[0], token)

    def test_politica_fora_dos_limites_e_400_e_o_token_segue_valendo(self):
        status, body = self.consume("seed-valido-ana", "fraca")

        self.assertEqual(400, status)
        self.assertIn("política", body["detail"])
        self.assertEqual(200, self.consume("seed-valido-ana")[0])

    def test_sem_token_ou_sem_senha_e_400(self):
        self.assertEqual(400, raw_call(self.port, "POST", "/auth/password_reset", body={"password": NOVA})[0])
        self.assertEqual(400, raw_call(self.port, "POST", "/auth/password_reset", body={"token": "seed-valido-ana"})[0])

    def test_o_dono_gera_um_link_de_24h_e_o_novo_invalida_o_anterior(self):
        dona, ana = self.login("dona"), self.login("ana")

        status, first = dona.call("POST", f"/users/{ana.id}/reset")
        self.assertEqual(200, status)
        self.assertTrue(first["reset_url"].startswith("https://papo.cyberasilo.online/passwordchange/"))
        self.assertRegex(first["expires_at"], r"^\d{4}-\d\d-\d\dT.*Z$")
        second = dona.call("POST", f"/users/{ana.id}/reset")[1]

        self.assertEqual(410, self.consume(first["reset_url"].rsplit("/", 1)[1])[0])
        self.assertEqual(200, self.consume(second["reset_url"].rsplit("/", 1)[1])[0])


if __name__ == "__main__":
    unittest.main()
