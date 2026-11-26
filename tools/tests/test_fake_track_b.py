"""Testes das sementes de embed, GIF e moderação (`seed_track_b`) de tools/dev/fake_papo.py.

Rode com: python3 -m unittest discover -s tools/tests -v
"""

import base64
import json
import struct
import subprocess
import sys
import time
import unittest

from test_fake_papo import FAKE, FakeTestCase, clean_env


def png_size(data):
    """Largura e altura de um PNG, lidas do chunk IHDR."""
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), data[:8]
    return struct.unpack(">II", data[16:24])


def gif_size(data):
    """Largura e altura de um GIF, lidas do descritor de tela."""
    assert data.startswith(b"GIF89a"), data[:6]
    return struct.unpack("<HH", data[6:10])


def gif_frames(data):
    """Quantos quadros um GIF tem: um descritor de imagem por Graphic Control Extension (o fake escreve um par por quadro)."""
    return data.count(b"\x21\xF9\x04")


class EmbedsSementesTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana = self.login("ana")
        self.cid = self.channel("embeds", self.ana)["id"]
        _, body = self.ana.call("GET", f"/channels/{self.cid}/messages?limit=100")
        self.messages = {m["content"]: m for m in body["messages"]}

    def embed_of(self, content):
        return self.messages[content]["embeds"][0]

    def media_of(self, embed):
        """O que `GET /embeds/{id}` devolve: a resposta e os bytes da imagem e do ícone do autor."""
        status, body = self.ana.call("GET", f"/embeds/{embed['id']}")
        self.assertEqual(200, status)
        decode = lambda text: base64.b64decode(text) if text else None
        return body, decode(body["image_data"]), decode(body["author_image_data"])

    def test_o_canal_embeds_existe_dentro_da_categoria_geral(self):
        channel = self.channel("embeds", self.ana)
        self.assertEqual("text", channel["type"])
        self.assertEqual(self.channel("Geral", self.ana)["id"], channel["parent_id"])

    def test_embed_customizado_completo_traz_cor_autor_campos_e_rodape(self):
        embed = self.embed_of("Build 42 concluído.")
        self.assertEqual(("custom", "manual", "#2ECC71"), (embed["source_type"], embed["fetch_method"], embed["color"]))
        self.assertEqual("Robô de Build", embed["author"]["name"])
        self.assertTrue(embed["author"]["url"].startswith("https://"))
        self.assertEqual(64, embed["author"]["media"]["width"])
        self.assertEqual("Executado em 3 min · ramo principal", embed["footer"]["text"])
        # Fora de ordem no JSON, com `position` certa; há `inline` em número ímpar e um campo largo no meio.
        fields = sorted(embed["fields"], key=lambda f: f["position"])
        self.assertEqual(["Ramo", "Duração", "Testes", "Falhas", "Notas", "Quem enviou"], [f["name"] for f in fields])
        self.assertEqual([True, True, True, True, False, True], [f["inline"] for f in fields])
        self.assertNotEqual(sorted(embed["fields"], key=lambda f: f["position"]), embed["fields"])
        self.assertGreater(embed["description"].count("\n"), 6)   # comprida o bastante para o "Mostrar mais"

    def test_get_embed_traz_a_imagem_e_o_icone_do_autor_em_base64(self):
        body, image, icon = self.media_of(self.embed_of("Build 42 concluído."))
        self.assertEqual((800, 450), png_size(image))
        self.assertEqual((64, 64), png_size(icon))
        self.assertEqual("#2ECC71", body["color"])   # o resto do embed vem junto, como no servidor

    def test_embed_sem_imagem_nem_autor_devolve_nulos(self):
        body, image, icon = self.media_of(self.embed_of("Backup noturno."))
        self.assertEqual((None, None), (image, icon))
        self.assertIsNone(body["image_data"])
        self.assertIsNone(body["author_image_data"])

    def test_embed_de_links_invalidos_serve_esquemas_que_o_app_deve_recusar(self):
        embed = self.embed_of("Links que o app não abre (o servidor real os recusa na criação).")
        self.assertTrue(embed["url"].startswith("javascript:"))
        self.assertTrue(embed["author"]["url"].startswith("intent:"))

    def test_embed_de_gif_so_traz_a_thumbnail_animada(self):
        for content, provider, size in (("https://tenor.com/view/gato-dancando-1234567890", "Tenor", (320, 180)),
                                        ("https://giphy.com/gifs/quadrado-9876543210", "GIPHY", (200, 200))):
            embed = self.embed_of(content)
            self.assertEqual(("gif", "link", provider), (embed["fetch_method"], embed["source_type"], embed["provider"]))
            self.assertIsNone(embed["video"])
            self.assertIsNone(embed["image"])
            self.assertEqual("image/gif", embed["thumbnail"]["mime_type"])
            _, image, icon = self.media_of(embed)
            self.assertEqual(size, (embed["thumbnail"]["width"], embed["thumbnail"]["height"]))
            self.assertEqual(size, gif_size(image))
            self.assertGreaterEqual(gif_frames(image), 3)
            self.assertIn(b"NETSCAPE2.0", image)   # repete sem fim
            self.assertEqual(len(image), embed["thumbnail"]["size_bytes"])
            self.assertIsNone(icon)
        self.assertEqual("Gato dançando", self.embed_of("https://tenor.com/view/gato-dancando-1234567890")["title"])

    def test_gif_como_imagem_de_opengraph_tambem_e_animado(self):
        embed = self.embed_of("Página com GIF no OpenGraph")
        self.assertEqual("opengraph", embed["fetch_method"])
        self.assertGreaterEqual(gif_frames(self.media_of(embed)[1]), 3)

    def test_imagens_de_embed_tem_as_medidas_dos_bytes_com_e_sem_video(self):
        for width, height in ((1200, 630), (600, 900), (512, 512)):
            for with_video in ("sem", "com"):
                embed = self.embed_of(f"imagem {width}x{height} {with_video} vídeo")
                self.assertEqual((width, height), (embed["thumbnail"]["width"], embed["thumbnail"]["height"]))
                self.assertEqual((width, height), png_size(self.media_of(embed)[1]))
                if with_video == "sem":
                    self.assertIsNone(embed["video"])
                else:
                    # O vídeo é sempre 16:9 e a imagem não: a caixa do cartão tem de seguir a imagem.
                    self.assertEqual(("video/mp4", 1280, 720), (embed["video"]["mime_type"], embed["video"]["width"], embed["video"]["height"]))

    def test_imagem_de_embed_sem_medidas_chega_sem_largura_nem_altura(self):
        embed = self.embed_of("imagem 1200x630 sem medidas no embed")
        self.assertEqual({"mime_type": "image/png"}, {k: v for k, v in embed["thumbnail"].items() if v is not None})
        self.assertEqual((1200, 630), png_size(self.media_of(embed)[1]))

    def test_mensagem_com_um_anexo_e_varios_embeds_para_o_visualizador(self):
        message = self.messages["vários embeds com imagem e um anexo"]
        self.assertEqual(["anexo.png"], [a["original_file_name"] for a in message["attachments"]])
        embeds = {e["title"]: e for e in message["embeds"]}
        self.assertEqual(7, len(message["embeds"]))   # o servidor aceita até 10 por mensagem
        self.assertEqual((1200, 630), png_size(self.media_of(embeds["Imagem 1 da galeria"])[1]))
        self.assertEqual((600, 900), png_size(self.media_of(embeds["Imagem 2 da galeria"])[1]))
        # Só com `image`: o servidor a serve no lugar da thumbnail.
        only_image = embeds["Só com image"]
        self.assertIsNone(only_image["thumbnail"])
        self.assertEqual((512, 512), png_size(self.media_of(only_image)[1]))
        self.assertEqual("gif", embeds["Gato dançando"]["fetch_method"])
        self.assertGreaterEqual(gif_frames(self.media_of(embeds["Gato dançando"])[1]), 3)
        # Com thumbnail e image, `GET /embeds/{id}` serve uma imagem só: a thumbnail. A image não chega ao app.
        both = embeds["Com thumbnail e image"]
        self.assertEqual(((800, 450), (600, 600)), ((both["thumbnail"]["width"], both["thumbnail"]["height"]), (both["image"]["width"], both["image"]["height"])))
        self.assertEqual((800, 450), png_size(self.media_of(both)[1]))
        # Fora do visualizador: o embed com vídeo e o que não tem imagem.
        self.assertEqual("video/mp4", embeds["Embed com vídeo (fora do visualizador)"]["video"]["mime_type"])
        self.assertIsNone(self.media_of(embeds["Sem imagem (fora do visualizador)"])[1])

    def test_anexos_em_cada_estado_de_moderacao(self):
        def attachment(prefix):
            content = next(c for c in self.messages if c.startswith(prefix))
            return self.messages[content]["attachments"][0]
        states = {"GIF anexado (": "clean", "GIF anexado sensível": "sensitive", "imagem pendente": "pending",
                  "imagem bloqueada (": "blocked", "imagem que o servidor já bloqueou": "pending"}
        self.assertEqual(states, {prefix: attachment(prefix)["moderation_status"] for prefix in states})
        gif = attachment("GIF anexado (")
        self.assertEqual(("image/gif", gif["id"], 320, 180), (gif["mime_type"], gif["thumbnail_id"], gif["thumbnail_width"], gif["thumbnail_height"]))
        # A miniatura de um GIF é um GIF animado, como no servidor.
        self.assertGreaterEqual(gif_frames(self.ana.fetch(f"/attachments/{gif['id']}/thumbnail")[2]), 3)
        for prefix in ("GIF anexado (", "GIF anexado sensível", "imagem pendente"):
            for path in ("", "/thumbnail"):
                self.assertEqual(200, self.ana.fetch(f"/attachments/{attachment(prefix)['id']}{path}")[0], (prefix, path))

    def test_anexo_bloqueado_responde_410_no_arquivo_e_na_miniatura_mesmo_quando_a_lista_diz_pending(self):
        for prefix in ("imagem bloqueada (", "imagem que o servidor já bloqueou"):
            content = next(c for c in self.messages if c.startswith(prefix))
            attachment_id = self.messages[content]["attachments"][0]["id"]
            for path in ("", "/thumbnail"):
                status, _, body = self.ana.fetch(f"/attachments/{attachment_id}{path}")
                self.assertEqual(410, status, (prefix, path))
                self.assertIn("moderação", json.loads(body)["detail"])

    def test_sem_moderacao_ao_vivo_por_padrao(self):
        self.assertFalse([c for c in self.messages if c.startswith("moderação ao vivo")])

    def test_embed_do_youtube_traz_a_pagina_do_player_sem_mime_type_e_a_miniatura(self):
        embed = self.embed_of("Vídeo do YouTube: https://www.youtube.com/watch?v=aB3dE5gH7jK")
        self.assertEqual(("oembed", "link", "YouTube"), (embed["fetch_method"], embed["source_type"], embed["provider"]))
        # Como o servidor: o player só em `video.url`, sem `mime_type`, e nada em `embed_url`.
        self.assertEqual({"url": "https://www.youtube.com/embed/aB3dE5gH7jK"}, {k: v for k, v in embed["video"].items() if v is not None})
        self.assertIsNone(embed.get("embed_url"))
        self.assertEqual((480, 270), (embed["thumbnail"]["width"], embed["thumbnail"]["height"]))
        self.assertEqual((480, 270), png_size(self.media_of(embed)[1]))
        # O relay do servidor não serve o YouTube (o app nunca o chama para ele).
        self.assertEqual(404, self.ana.fetch(f"/embeds/{embed['id']}/video")[0])

    def test_embed_com_endereco_de_player_quase_certo_traz_um_host_que_nao_e_o_youtube(self):
        embed = self.embed_of("Parece do YouTube, mas o endereço do player é de outro site")
        self.assertEqual("https://youtube.com.evil.example.com/embed/aB3dE5gH7jK", embed["video"]["url"])
        self.assertIsNone(embed["video"].get("mime_type"))


class YouTubeComIdProprioTest(FakeTestCase):
    env = {"FAKE_YOUTUBE_ID": "Zz9_-Aa1Bb2"}

    def test_o_id_do_ambiente_vai_para_o_player_e_para_o_link(self):
        ana = self.login("ana")
        _, body = ana.call("GET", f"/channels/{self.channel('embeds', ana)['id']}/messages?limit=100")
        message = next(m for m in body["messages"] if m["content"].startswith("Vídeo do YouTube"))
        self.assertTrue(message["content"].endswith("watch?v=Zz9_-Aa1Bb2"))
        self.assertEqual("https://www.youtube.com/embed/Zz9_-Aa1Bb2", message["embeds"][0]["video"]["url"])


class YouTubeInvalidoTest(unittest.TestCase):
    def test_id_que_nao_tem_onze_caracteres_derruba_o_fake_com_mensagem(self):
        done = subprocess.run([sys.executable, str(FAKE), "0"], env={**clean_env(), "FAKE_EMOJI_SMALL": "1", "FAKE_YOUTUBE_ID": "curto"},
                              capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("FAKE_YOUTUBE_ID", done.stderr)


class ModeracaoAoVivoTest(FakeTestCase):
    env = {"FAKE_MODERATION": "2"}

    def test_a_imagem_vira_sensivel_com_evento_e_depois_bloqueada_com_410_e_sem_evento(self):
        ana = self.login("ana")
        watcher = self.watch(ana)
        cid = self.channel("embeds", ana)["id"]

        def live():
            _, body = ana.call("GET", f"/channels/{cid}/messages?limit=100")
            message = next(m for m in body["messages"] if m["content"].startswith("moderação ao vivo"))
            return message["id"], message["attachments"][0]

        message_id, attachment = live()
        self.assertEqual("pending", attachment["moderation_status"])
        self.assertEqual(200, ana.fetch(f"/attachments/{attachment['id']}")[0])

        seen = watcher.wait(lambda e: e["type"] == "attachment_moderation_update", seconds=6)
        self.assertEqual({"type": "attachment_moderation_update", "channel_id": cid, "message_id": message_id,
                          "attachment_id": attachment["id"], "status": "sensitive"}, seen[-1])
        self.assertEqual("sensitive", live()[1]["moderation_status"])
        self.assertEqual(200, ana.fetch(f"/attachments/{attachment['id']}")[0])

        # Depois de mais 2 s o arquivo some (410) e a lista passa a dizer `blocked`, sem outro evento de moderação.
        deadline = time.monotonic() + 6
        while ana.fetch(f"/attachments/{attachment['id']}")[0] != 410 and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertEqual(410, ana.fetch(f"/attachments/{attachment['id']}")[0])
        self.assertEqual("blocked", live()[1]["moderation_status"])
        pending_events = []
        while not watcher.events.empty():
            pending_events.append(watcher.events.get_nowait())
        self.assertEqual([], [e for e in pending_events if e["type"] == "attachment_moderation_update"])


class ModeracaoInvalidaTest(unittest.TestCase):
    def test_valor_ilegivel_derruba_o_fake_com_mensagem(self):
        done = subprocess.run([sys.executable, str(FAKE), "0"], env={**clean_env(), "FAKE_EMOJI_SMALL": "1", "FAKE_MODERATION": "devagar"},
                              capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("FAKE_MODERATION: valor ilegível", done.stderr)


if __name__ == "__main__":
    unittest.main()
