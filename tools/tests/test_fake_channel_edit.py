"""Testes de `PUT /channels/{id}` (editar nome e descrição de um canal) em tools/dev/fake_papo.py.
Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre (ver `FakeTestCase`), então nenhum depende do estado deixado por outro.
"""

import json
import queue
import tempfile
import unittest
import uuid
from pathlib import Path

from test_fake_papo import FakeTestCase, raw_call


class EditarCanalTest(FakeTestCase):
    """Sem FAKE_PERMISSIONS todos podem editar; o resto da regra (nome, descrição, 409, evento) é o mesmo."""

    def setUp(self):
        super().setUp()
        self.ana = self.login("ana")
        self.papo = self.channel("papo", self.ana)
        self.avisos = self.channel("avisos", self.ana)
        self.geral = self.channel("Geral", self.ana)

    def put(self, channel, body, who=None):
        return (who or self.ana).call("PUT", f"/channels/{channel['id']}", body)

    def test_muda_nome_e_descricao_e_responde_como_a_listagem(self):
        status, body = self.put(self.papo, {"name": "bate-papo", "topic": "Outra conversa"})
        self.assertEqual(200, status)
        self.assertEqual("bate-papo", body["name"])
        self.assertEqual("Outra conversa", body["topic"])
        self.assertEqual(self.papo["parent_id"], body["parent_id"])  # continua na categoria
        self.assertEqual(self.papo["position"], body["position"])
        self.assertEqual("text", body["type"])
        self.assertIn("last_message", body)
        self.assertIn("notification_settings", body)
        listed = self.channel("bate-papo", self.ana)
        self.assertEqual("Outra conversa", listed["topic"])
        self.assertEqual(self.papo["parent_id"], listed["parent_id"])

    def test_descricao_ausente_ou_nula_mantem_e_vazia_apaga(self):
        self.assertEqual("Conversa fiada", self.put(self.papo, {"name": "papo2"})[1]["topic"])
        self.assertEqual("Conversa fiada", self.put(self.papo, {"name": "papo3", "topic": None})[1]["topic"])
        status, body = self.put(self.papo, {"name": "papo3", "topic": ""})
        self.assertEqual(200, status)
        self.assertIsNone(body["topic"])
        self.assertIsNone(self.channel("papo3", self.ana)["topic"])

    def test_canal_sem_descricao_ganha_uma(self):
        status, body = self.put(self.avisos, {"name": "avisos", "topic": "Só o essencial"})
        self.assertEqual(200, status)
        self.assertEqual("Só o essencial", body["topic"])

    def test_nome_de_um_a_trinta_e_dois_caracteres_contados_como_pontos_de_codigo(self):
        self.assertEqual(200, self.put(self.papo, {"name": "a"})[0])
        self.assertEqual(200, self.put(self.papo, {"name": "b" * 32})[0])
        self.assertEqual(200, self.put(self.papo, {"name": "😀" * 32})[0])  # 64 unidades UTF-16, 32 runas
        self.assertEqual(400, self.put(self.papo, {"name": "c" * 33})[0])
        self.assertEqual(400, self.put(self.papo, {"name": "😀" * 33})[0])
        self.assertEqual(400, self.put(self.papo, {"name": ""})[0])
        self.assertEqual(400, self.put(self.papo, {"topic": "sem nome"})[0])
        self.assertEqual(400, self.put(self.papo, {"name": 7})[0])

    def test_descricao_ate_quinhentos_e_doze_caracteres(self):
        self.assertEqual(200, self.put(self.papo, {"name": "papo", "topic": "d" * 512})[0])
        status, body = self.put(self.papo, {"name": "papo", "topic": "d" * 513})
        self.assertEqual(400, status)
        self.assertEqual("Parâmetro inválido", body["title"])
        self.assertEqual(400, self.put(self.papo, {"name": "papo", "topic": 5})[0])
        # A recusa não muda nada.
        self.assertEqual("d" * 512, self.channel("papo", self.ana)["topic"])

    def test_categoria_nao_tem_descricao(self):
        self.assertEqual(400, self.put(self.geral, {"name": "Geral", "topic": "texto"})[0])
        status, body = self.put(self.geral, {"name": "Geral", "topic": ""})
        self.assertEqual(200, status)
        self.assertIsNone(body["topic"])
        status, body = self.put(self.geral, {"name": "Salas"})
        self.assertEqual(200, status)
        self.assertEqual("category", body["type"])

    def test_nome_de_outro_canal_e_conflito_mas_o_proprio_nome_nao(self):
        status, body = self.put(self.papo, {"name": "avisos"})
        self.assertEqual(409, status)
        self.assertEqual("Nome de canal já existe", body["title"])
        self.assertEqual("papo", self.channel("papo", self.ana)["name"])
        self.assertEqual(200, self.put(self.papo, {"name": "papo", "topic": "igual"})[0])

    def test_canal_inexistente_e_404_e_dm_tambem(self):
        self.assertEqual(404, self.put({"id": str(uuid.uuid4())}, {"name": "x"})[0])
        bia = self.login("bia")
        _, dm = self.ana.call("POST", "/dms", {"user_id": bia.id})
        self.assertEqual(404, self.put({"id": dm["id"]}, {"name": "x"})[0])

    def test_a_validacao_vem_antes_de_procurar_o_canal(self):
        self.assertEqual(400, self.put({"id": str(uuid.uuid4())}, {"name": ""})[0])

    def test_sem_sessao_e_401(self):
        status, _, _ = raw_call(self.port, "PUT", f"/channels/{self.papo['id']}", body={"name": "x"})
        self.assertEqual(401, status)

    def test_manda_channel_update_a_todos_com_a_categoria(self):
        bia = self.login("bia")
        watcher = self.watch(bia)
        self.put(self.papo, {"name": "bate-papo", "topic": "Outra conversa"})
        event = watcher.wait(lambda e: e["type"] == "channel_update")[-1]
        self.assertEqual({
            "type": "channel_update", "channel_id": self.papo["id"], "name": "bate-papo", "position": self.papo["position"],
            "topic": "Outra conversa", "parent_id": self.papo["parent_id"],
        }, event)

    def test_apagar_a_descricao_manda_topic_nulo_no_evento(self):
        watcher = self.watch(self.login("bia"))
        self.put(self.papo, {"name": "papo", "topic": ""})
        event = watcher.wait(lambda e: e["type"] == "channel_update")[-1]
        self.assertIsNone(event["topic"])
        self.assertEqual(self.papo["parent_id"], event["parent_id"])

    def test_recusa_nao_manda_evento(self):
        watcher = self.watch(self.login("bia"))
        self.assertEqual(409, self.put(self.papo, {"name": "avisos"})[0])
        self.assertEqual(400, self.put(self.papo, {"name": ""})[0])
        self.assertEqual(200, self.put(self.avisos, {"name": "avisos2"})[0])
        seen = watcher.wait(lambda e: e["type"] == "channel_update")
        self.assertEqual([self.avisos["id"]], [e["channel_id"] for e in seen if e["type"] == "channel_update"])
        with self.assertRaises(queue.Empty):
            watcher.wait(lambda e: e["type"] == "channel_update", seconds=0.3)


class EditarCanalComPermissoesTest(FakeTestCase):
    """Com FAKE_PERMISSIONS: só o dono e quem tem `manage_channels` editam; o 403 vem antes de qualquer validação."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "permissoes.json"
        path.write_text(json.dumps({
            "owner": "caio",
            "roles": [{"name": "Gestão", "permissions": ["manage_channels"]}, {"name": "Moderação", "permissions": ["pin_message"]}],
            "users": {"ana": ["Gestão"], "bia": ["Moderação"], "dona": []},  # a dona existe, sem cargo
        }))
        self.permissions = str(path)
        super().setUp()
        self.ana, self.bia, self.caio = self.login("ana"), self.login("bia"), self.login("caio")
        self.papo = self.channel("papo", self.caio)

    def put(self, who, body, channel=None):
        return who.call("PUT", f"/channels/{(channel or self.papo)['id']}", body)

    def test_cargo_com_manage_channels_edita(self):
        status, body = self.put(self.ana, {"name": "bate-papo", "topic": "Nova"})
        self.assertEqual(200, status)
        self.assertEqual("bate-papo", body["name"])
        self.assertIn("permissions", body)  # com permissões ligadas, a resposta traz as entradas como a listagem

    def test_dono_edita_sem_cargo(self):
        self.assertEqual(200, self.put(self.caio, {"name": "do-dono"})[0])

    def test_cargo_sem_a_flag_e_quem_nao_tem_cargo_levam_403(self):
        for who in (self.bia, self.login("dona")):
            status, body = self.put(who, {"name": "invasor"})
            self.assertEqual(403, status)
            self.assertEqual("Acesso negado", body["title"])
        self.assertEqual("papo", self.channel("papo", self.caio)["name"])

    def test_403_vem_antes_da_validacao_e_antes_do_404(self):
        self.assertEqual(403, self.put(self.bia, {"name": ""})[0])
        self.assertEqual(403, self.put(self.bia, {"name": "x"}, {"id": str(uuid.uuid4())})[0])

    def test_403_nao_manda_evento_e_o_200_sim(self):
        watcher = self.watch(self.caio)
        self.assertEqual(403, self.put(self.bia, {"name": "invasor"})[0])
        self.assertEqual(200, self.put(self.ana, {"name": "bate-papo"})[0])
        seen = watcher.wait(lambda e: e["type"] == "channel_update")
        self.assertEqual(["bate-papo"], [e["name"] for e in seen if e["type"] == "channel_update"])


if __name__ == "__main__":
    unittest.main()
