"""Testes da caixa de notificações de tools/dev/fake_papo.py. Rode com: python3 -m unittest discover -s tools/tests -v

Cada teste sobe um fake novo numa porta livre, então nenhum depende do estado deixado por outro. As regras do fake vêm
do código do servidor real (não observadas em execução): ver a região "Notificações" do fake.
"""

import queue
import unittest
import uuid

from test_fake_papo import FakeTestCase, moment, raw_call


class CaixaTestCase(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia, self.caio = self.login("ana"), self.login("bia"), self.login("caio")
        self.papo = self.channel("papo", self.ana)["id"]

    def inbox(self, session, query=""):
        status, body = session.call("GET", f"/users/{session.id}/notifications{query}")
        self.assertEqual(200, status, body)
        return body

    def contents(self, session, query=""):
        return [n["message_content"] for n in self.inbox(session, query)["notifications"]]

    def row(self, session, content):
        return next(n for n in self.inbox(session)["notifications"] if n["message_content"] == content)

    def mention(self, session, text="oi"):
        return f"{text} @mention(<@{session.id}>)"

    def dm_with(self, session, other):
        status, body = session.call("POST", "/dms", {"user_id": other.id})
        self.assertEqual(200, status, body)
        return body["id"]

    def drain(self, watcher):
        """Os eventos que chegam até o WebSocket ficar 0,4 s em silêncio."""
        seen = []
        try:
            while True:
                seen.append(watcher.events.get(timeout=0.4))
        except queue.Empty:
            return seen


class GatilhosTest(CaixaTestCase):
    def test_o_fake_semeia_a_mencao_a_resposta_e_as_dms(self):
        self.assertIn("Vi sim, @mention(<@%s>) acompanhou comigo.\nSegue o print:" % self.ana.id, self.contents(self.ana))
        self.assertIn("Ana, o deploy de ontem passou nos testes?", self.contents(self.ana))
        # `bia` respondeu à mensagem de `caio`, e `ana` respondeu `bia` na DM.
        self.assertTrue(any("Vi sim" in c for c in self.contents(self.caio)))
        self.assertIn("Passou! Só faltou atualizar a documentação.", self.contents(self.bia))

    def test_mencao_direta_cria_a_linha_e_o_evento_vem_depois_do_message(self):
        watcher = self.watch(self.ana)
        text = self.mention(self.ana, "vem ver")

        status, sent = self.bia.send(self.papo, text)
        events = watcher.wait(lambda e: e["type"] == "new_notification")

        self.assertEqual(201, status)
        self.assertEqual(["message", "new_notification"], [e["type"] for e in events if e["type"] in ("message", "new_notification")])
        event = events[-1]
        row = self.row(self.ana, text)
        self.assertEqual(row["id"], event["id"])
        self.assertEqual((self.bia.id, sent["id"], text), (event["user_id"], event["message_id"], event["message_content"]))
        self.assertEqual((sent["id"], self.papo, self.bia.id, False), (row["message_id"], row["channel_id"], row["author_id"], row["read"]))
        self.assertNotIn("webhook_id", row)

    def test_a_linha_nasce_tres_milissegundos_depois_da_mensagem(self):
        _, sent = self.bia.send(self.papo, self.mention(self.ana))

        row = self.row(self.ana, sent["content"])

        self.assertEqual(3000, (moment(row["created_at"]) - moment(sent["created_at"])).microseconds)
        self.assertEqual(0, (moment(row["created_at"]) - moment(sent["created_at"])).days)

    def test_mensagem_comum_nao_gera_linha_nem_evento(self):
        watcher = self.watch(self.ana)

        self.bia.send(self.papo, "só conversa")

        self.assertNotIn("só conversa", self.contents(self.ana))
        self.assertEqual([], [e for e in self.drain(watcher) if e["type"] == "new_notification"])

    def test_quem_envia_nunca_e_notificado_nem_quem_so_foi_mencionado_em_outra_conta(self):
        self.ana.send(self.papo, self.mention(self.ana, "eu mesma"))
        self.ana.send(self.papo, "oi @everyone")

        self.assertNotIn(self.mention(self.ana, "eu mesma"), self.contents(self.ana))
        self.assertNotIn(self.mention(self.ana, "eu mesma"), self.contents(self.bia))
        self.assertNotIn("oi @everyone", self.contents(self.ana))
        self.assertIn("oi @everyone", self.contents(self.bia))

    def test_resposta_a_uma_mensagem_minha_notifica_o_autor_dela(self):
        _, mine = self.ana.send(self.papo, "minha pergunta")
        status, _ = self.bia.call("POST", "/messages", raw=self.reply_form(self.papo, "minha resposta", mine["id"]),
                                  content_type="multipart/form-data; boundary=xx")

        self.assertEqual(201, status)
        self.assertIn("minha resposta", self.contents(self.ana))
        self.assertNotIn("minha resposta", self.contents(self.caio))

    def reply_form(self, channel, content, reply_to):
        fields = {"channel_id": channel, "content": content, "reply_to": reply_to}
        body = "".join(f'--xx\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n' for k, v in fields.items())
        return (body + "--xx--\r\n").encode()

    def test_so_o_formato_do_servidor_vale_como_mencao(self):
        self.bia.send(self.papo, f"sem o prefixo <@{self.ana.id}>")
        self.bia.send(self.papo, f"id errado @mention(<@{uuid.uuid4()}>)")
        self.bia.send(self.papo, f"maiúsculas no id @mention(<@{self.ana.id.upper()}>)")
        self.bia.send(self.papo, f"@MENTION(<@{self.ana.id}>) maiúsculas no prefixo")
        self.bia.send(self.papo, "@everyones não vale")

        self.assertEqual([f"@MENTION(<@{self.ana.id}>) maiúsculas no prefixo"],
                         [c for c in self.contents(self.ana) if "@" in c and "Vi sim" not in c])

    def test_everyone_notifica_todos_menos_o_autor(self):
        self.bia.send(self.papo, "aviso @everyone")

        self.assertIn("aviso @everyone", self.contents(self.ana))
        self.assertIn("aviso @everyone", self.contents(self.caio))
        self.assertNotIn("aviso @everyone", self.contents(self.bia))

    def test_mensagem_de_dm_gera_linha_so_para_o_outro(self):
        dm = self.dm_with(self.ana, self.bia)

        self.ana.send(dm, "oi bia, sem menção nenhuma")

        self.assertIn("oi bia, sem menção nenhuma", self.contents(self.bia))
        self.assertNotIn("oi bia, sem menção nenhuma", self.contents(self.ana))
        self.assertEqual(dm, self.row(self.bia, "oi bia, sem menção nenhuma")["channel_id"])

    def test_texto_longo_vai_truncado_em_512_na_lista_e_no_evento(self):
        watcher = self.watch(self.ana)
        text = self.mention(self.ana, "x" * 600)

        self.bia.send(self.papo, text)
        event = watcher.wait(lambda e: e["type"] == "new_notification")[-1]

        self.assertEqual(text[:512], event["message_content"])
        self.assertTrue(any(c == text[:512] for c in self.contents(self.ana)))


class PreferenciaDoCanalTest(CaixaTestCase):
    def setting(self, session, channel, value):
        return session.call("POST", f"/channels/{channel}/user/{session.id}/settings", {"notification_settings": value})

    def test_all_manda_so_o_evento_com_id_efemero_e_sem_linha(self):
        self.assertEqual(200, self.setting(self.ana, self.papo, "all")[0])
        watcher = self.watch(self.ana)

        _, sent = self.bia.send(self.papo, "conversa comum")
        event = watcher.wait(lambda e: e["type"] == "new_notification")[-1]

        self.assertEqual(sent["id"], event["message_id"])
        self.assertNotIn(event["id"], [n["id"] for n in self.inbox(self.ana)["notifications"]])
        self.assertNotIn("conversa comum", self.contents(self.ana))

    def test_all_com_gatilho_manda_o_evento_com_o_id_da_linha(self):
        self.setting(self.ana, self.papo, "all")
        watcher = self.watch(self.ana)

        self.bia.send(self.papo, self.mention(self.ana))
        event = watcher.wait(lambda e: e["type"] == "new_notification")[-1]

        self.assertEqual(event["id"], self.row(self.ana, self.mention(self.ana))["id"])

    def test_off_nao_manda_nada_nem_com_mencao(self):
        self.setting(self.ana, self.papo, "off")
        watcher = self.watch(self.ana)

        self.bia.send(self.papo, self.mention(self.ana, "fura o silêncio?"))

        self.assertEqual([], [e for e in self.drain(watcher) if e["type"] == "new_notification"])
        self.assertNotIn(self.mention(self.ana, "fura o silêncio?"), self.contents(self.ana))

    def test_a_preferencia_e_de_cada_usuario_e_aparece_em_get_channels(self):
        self.setting(self.ana, self.papo, "off")

        mine = next(c for c in self.ana.call("GET", "/channels")[1]["channels"] if c["id"] == self.papo)
        theirs = next(c for c in self.bia.call("GET", "/channels")[1]["channels"] if c["id"] == self.papo)

        self.assertEqual(("off", "only_mentions"), (mine["notification_settings"], theirs["notification_settings"]))

    def test_resposta_e_erros_da_rota(self):
        status, body = self.setting(self.ana, self.papo, "all")
        self.assertEqual(200, status)
        self.assertEqual((self.ana.id, self.papo, "all"), (body["user_id"], body["channel_id"], body["notification_settings"]))
        self.assertEqual(400, self.setting(self.ana, self.papo, "nenhuma")[0])
        self.assertEqual(404, self.setting(self.ana, str(uuid.uuid4()), "all")[0])
        self.assertEqual(403, self.bia.call("POST", f"/channels/{self.papo}/user/{self.ana.id}/settings", {"notification_settings": "off"})[0])

    def test_dm_tambem_aceita_a_preferencia(self):
        dm = self.dm_with(self.ana, self.bia)
        self.assertEqual(200, self.setting(self.bia, dm, "off")[0])

        self.ana.send(dm, "ninguém vai ver")

        self.assertNotIn("ninguém vai ver", self.contents(self.bia))


class ListaTest(CaixaTestCase):
    env = {"FAKE_NOTIFICATION_PAGE": "2"}

    def setUp(self):
        super().setUp()
        for n in range(5):
            self.bia.send(self.papo, self.mention(self.ana, f"n{n}"))

    def test_desc_por_padrao_com_pagina_de_dois_e_has_more(self):
        page = self.inbox(self.ana)

        self.assertEqual([self.mention(self.ana, "n4"), self.mention(self.ana, "n3")], [n["message_content"] for n in page["notifications"]])
        self.assertTrue(page["has_more"])

    def test_cursor_since_e_last_id_percorre_tudo_sem_repetir(self):
        seen, query = [], ""
        for _ in range(10):
            page = self.inbox(self.ana, query)
            seen += page["notifications"]
            if not page["has_more"]:
                break
            last = page["notifications"][-1]
            query = f"?since={last['created_at']}&last_id={last['id']}"

        ids = [n["id"] for n in seen]
        self.assertEqual(len(ids), len(set(ids)))
        # Cinco mensagens de `n`, mais a menção e a DM semeadas.
        self.assertEqual(7, len(ids))
        self.assertEqual(sorted(ids, key=lambda i: next((n["created_at"], n["id"]) for n in seen if n["id"] == i), reverse=True), ids)

    def test_asc_traz_as_mais_antigas_primeiro(self):
        asc = self.inbox(self.ana, "?order=asc")["notifications"]
        self.assertEqual("Vi sim", asc[0]["message_content"][:6])

    def test_since_sozinho_traz_so_as_mais_novas_em_qualquer_ordem(self):
        newest = self.inbox(self.ana)["notifications"][0]
        _, sent = self.bia.send(self.papo, self.mention(self.ana, "depois"))

        for order in ("desc", "asc"):
            page = self.inbox(self.ana, f"?since={newest['created_at']}&order={order}")
            self.assertEqual([sent["content"]], [n["message_content"] for n in page["notifications"]])
            self.assertFalse(page["has_more"])

    def test_last_id_sem_since_e_ignorado(self):
        first = self.inbox(self.ana)["notifications"]

        again = self.inbox(self.ana, f"?last_id={first[0]['id']}")["notifications"]

        self.assertEqual([n["id"] for n in first], [n["id"] for n in again])

    def test_pedido_invalido_e_400_e_so_o_proprio_usuario_le(self):
        self.assertEqual(400, self.ana.call("GET", f"/users/{self.ana.id}/notifications?order=sideways")[0])
        self.assertEqual(400, self.ana.call("GET", f"/users/{self.ana.id}/notifications?since=ontem")[0])
        self.assertEqual(403, self.ana.call("GET", f"/users/{self.bia.id}/notifications")[0])
        self.assertEqual(401, raw_call(self.port, "GET", f"/users/{self.ana.id}/notifications")[0])

    def test_mensagem_apagada_some_da_lista_mas_a_linha_continua_marcavel(self):
        row = self.row(self.ana, self.mention(self.ana, "n4"))

        self.assertEqual(204, self.bia.call("DELETE", f"/messages/{row['message_id']}")[0])

        self.assertNotIn(self.mention(self.ana, "n4"), self.contents(self.ana))
        self.assertEqual(200, self.ana.call("PUT", f"/users/{self.ana.id}/read_notification", {"notification_ids": [row["id"]]})[0])


class LeituraTest(CaixaTestCase):
    def read(self, session, ids):
        return session.call("PUT", f"/users/{session.id}/read_notification", {"notification_ids": ids})

    def setUp(self):
        super().setUp()
        self.sent = [self.bia.send(self.papo, self.mention(self.ana, f"l{n}"))[1] for n in range(3)]
        self.rows = {r["message_content"]: r for r in self.inbox(self.ana)["notifications"]}

    def row_of(self, n):
        return self.rows[self.mention(self.ana, f"l{n}")]

    def reads(self):
        return {n["message_content"]: n["read"] for n in self.inbox(self.ana)["notifications"]}

    def test_marcar_conta_as_linhas_e_marcar_de_novo_ainda_e_200(self):
        ids = [self.row_of(0)["id"], self.row_of(1)["id"]]

        self.assertEqual((200, {"updated": 2}), self.read(self.ana, ids))
        self.assertEqual((200, {"updated": 2}), self.read(self.ana, ids))  # o UPDATE do servidor não filtra por `read`

        reads = self.reads()
        self.assertEqual([True, True, False], [reads[self.mention(self.ana, f"l{n}")] for n in range(3)])

    def test_id_desconhecido_ou_de_outro_usuario_e_404_e_um_conhecido_basta_para_200(self):
        self.assertEqual(404, self.read(self.ana, [str(uuid.uuid4())])[0])
        bia_row = self.inbox(self.bia)["notifications"][0]
        self.assertEqual(404, self.read(self.ana, [bia_row["id"]])[0])
        self.assertEqual((200, {"updated": 1}), self.read(self.ana, [str(uuid.uuid4()), self.row_of(2)["id"]]))
        self.assertFalse(self.inbox(self.bia)["notifications"][0]["read"])

    def test_de_1_a_1000_ids(self):
        self.assertEqual(400, self.read(self.ana, [])[0])
        self.assertEqual(400, self.read(self.ana, [str(uuid.uuid4()) for _ in range(1001)])[0])
        self.assertEqual(400, self.read(self.ana, [""])[0])
        self.assertEqual(400, self.ana.call("PUT", f"/users/{self.ana.id}/read_notification", {})[0])
        self.assertEqual(404, self.read(self.ana, [str(uuid.uuid4()) for _ in range(1000)])[0])

    def test_so_o_proprio_usuario_marca(self):
        self.assertEqual(403, self.bia.call("PUT", f"/users/{self.ana.id}/read_notification", {"notification_ids": [self.row_of(0)["id"]]})[0])

    def test_ler_o_canal_marca_as_linhas_dele_ate_a_mensagem_e_sem_evento(self):
        other = self.channel("avisos", self.ana)["id"]
        _, outside = self.bia.send(other, self.mention(self.ana, "em outro canal"))
        watcher = self.watch(self.ana)

        status, _ = self.ana.call("PUT", f"/channels/{self.papo}/read", {"message_id": self.sent[1]["id"]})

        self.assertEqual(204, status)
        reads = self.reads()
        self.assertEqual([True, True, False], [reads[self.mention(self.ana, f"l{n}")] for n in range(3)])
        self.assertFalse(reads[outside["content"]])
        self.assertEqual([], [e for e in self.drain(watcher) if e["type"] == "new_notification"])
        self.assertTrue(reads["Vi sim, @mention(<@%s>) acompanhou comigo.\nSegue o print:" % self.ana.id])  # antes da marca, no mesmo canal

    def test_ler_a_dm_marca_as_linhas_dela(self):
        dm = self.dm_with(self.bia, self.ana)
        _, first = self.bia.send(dm, "dm 1")
        _, second = self.bia.send(dm, "dm 2")

        self.ana.call("PUT", f"/channels/{dm}/read", {"message_id": first["id"]})

        reads = self.reads()
        self.assertEqual((True, False), (reads["dm 1"], reads["dm 2"]))
        self.assertTrue(second["id"])

    def test_a_marca_de_uma_mensagem_de_outro_canal_nao_marca_nada(self):
        other = self.channel("avisos", self.ana)["id"]
        _, elsewhere = self.bia.send(other, "só um aviso")

        self.ana.call("PUT", f"/channels/{self.papo}/read", {"message_id": elsewhere["id"]})

        self.assertFalse(any(self.reads()[self.mention(self.ana, f"l{n}")] for n in range(3)))


class EveryoneComPermissoesTest(CaixaTestCase):
    permissions = "demo"

    def setUp(self):
        super().setUp()
        self.dona = self.login("dona")

    def test_so_quem_tem_everyone_message_notifica(self):
        self.caio.send(self.papo, "aviso falso @everyone")
        self.bia.send(self.papo, "aviso de verdade @everyone")

        self.assertNotIn("aviso falso @everyone", self.contents(self.ana))
        self.assertIn("aviso de verdade @everyone", self.contents(self.ana))
        self.assertIn("aviso de verdade @everyone", self.contents(self.dona))

    def test_a_mencao_direta_de_quem_nao_pode_everyone_continua_valendo(self):
        self.caio.send(self.papo, self.mention(self.ana, "@everyone sem poder, mas direto:"))

        self.assertIn(self.mention(self.ana, "@everyone sem poder, mas direto:"), self.contents(self.ana))
        self.assertNotIn(self.mention(self.ana, "@everyone sem poder, mas direto:"), self.contents(self.dona))

    def test_so_quem_le_o_canal_e_notificado(self):
        equipe = self.channel("equipe", self.dona)["id"]  # só Moderação lê

        self.dona.send(equipe, "reunião @everyone")

        self.assertIn("reunião @everyone", self.contents(self.bia))
        self.assertNotIn("reunião @everyone", self.contents(self.ana))
        self.assertNotIn("reunião @everyone", self.contents(self.caio))


class ConversaDiretaPorIdTest(CaixaTestCase):
    def test_le_uma_dm_do_usuario_com_o_formato_de_get_dms(self):
        dm = self.dm_with(self.ana, self.bia)

        status, body = self.ana.call("GET", f"/dms/{dm}")

        self.assertEqual(200, status)
        self.assertEqual(next(d for d in self.ana.call("GET", "/dms")[1]["dms"] if d["id"] == dm), body)

    def test_dm_alheia_ou_inexistente_e_404(self):
        dm = self.dm_with(self.ana, self.bia)

        self.assertEqual(404, self.caio.call("GET", f"/dms/{dm}")[0])
        self.assertEqual(404, self.ana.call("GET", f"/dms/{uuid.uuid4()}")[0])
        self.assertEqual(401, raw_call(self.port, "GET", f"/dms/{dm}")[0])

    def test_com_bloqueio_e_403_dm_blocked(self):
        dm = self.dm_with(self.ana, self.bia)
        self.ana.call("POST", f"/users/{self.bia.id}/block")

        status, body = self.bia.call("GET", f"/dms/{dm}")

        self.assertEqual(403, status)
        self.assertTrue(body["type"].endswith("/dm-blocked"))


if __name__ == "__main__":
    unittest.main()
