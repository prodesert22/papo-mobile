"""Testes de `DELETE /dms/{id}` em tools/dev/fake_papo.py. Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre, com a DM entre ana e caio (a ana e a bia já têm uma semeada). A regra vem da OpenAPI: fechar não apaga o histórico, e uma mensagem
nova faz a conversa reaparecer.
"""

import unittest
import uuid

from test_fake_papo import FakeTestCase, raw_call


class FecharDmTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia, self.caio = self.login("ana"), self.login("bia"), self.login("caio")
        status, body = self.ana.call("POST", "/dms", {"user_id": self.caio.id})
        self.assertEqual(200, status, body)
        self.dm = body["id"]

    def ids(self, session):
        status, body = session.call("GET", "/dms")
        self.assertEqual(200, status, body)
        return [d["id"] for d in body["dms"]]

    def test_fecha_so_para_quem_pediu_e_responde_204(self):
        self.assertEqual(204, self.ana.call("DELETE", f"/dms/{self.dm}")[0])

        self.assertNotIn(self.dm, self.ids(self.ana))
        self.assertIn(self.dm, self.ids(self.caio))

    def test_fechar_de_novo_continua_204(self):
        self.ana.call("DELETE", f"/dms/{self.dm}")

        self.assertEqual(204, self.ana.call("DELETE", f"/dms/{self.dm}")[0])

    def test_quem_nao_e_da_conversa_ou_conversa_inexistente_e_404(self):
        self.assertEqual(404, self.bia.call("DELETE", f"/dms/{self.dm}")[0])
        self.assertEqual(404, self.ana.call("DELETE", f"/dms/{uuid.uuid4()}")[0])
        self.assertIn(self.dm, self.ids(self.ana))

    def test_sem_sessao_e_401(self):
        self.assertEqual(401, raw_call(self.port, "DELETE", f"/dms/{self.dm}")[0])

    def test_o_historico_continua_e_a_dm_fechada_ainda_se_le_por_id(self):
        self.caio.send(self.dm, "antes de fechar")
        self.ana.call("DELETE", f"/dms/{self.dm}")

        self.assertEqual(200, self.ana.call("GET", f"/dms/{self.dm}")[0])
        _, body = self.ana.call("GET", f"/channels/{self.dm}/messages")
        self.assertEqual(["antes de fechar"], [m["content"] for m in body["messages"]])

    def test_mensagem_nova_reabre_e_manda_dm_update(self):
        self.ana.call("DELETE", f"/dms/{self.dm}")
        watcher = self.watch(self.ana)

        self.caio.send(self.dm, "voltei")

        update = watcher.wait(lambda e: e["type"] == "dm_update")[-1]
        self.assertEqual(self.dm, update["dm"]["id"])
        self.assertIn(self.dm, self.ids(self.ana))

    def test_mensagem_de_quem_fechou_tambem_reabre(self):
        self.ana.call("DELETE", f"/dms/{self.dm}")

        self.ana.send(self.dm, "mudei de ideia")

        self.assertIn(self.dm, self.ids(self.ana))

    def test_abrir_de_novo_pelo_post_reabre(self):
        self.ana.call("DELETE", f"/dms/{self.dm}")

        status, body = self.ana.call("POST", "/dms", {"user_id": self.caio.id})

        self.assertEqual((200, self.dm), (status, body["id"]))
        self.assertIn(self.dm, self.ids(self.ana))


if __name__ == "__main__":
    unittest.main()
