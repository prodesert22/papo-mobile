"""Testes das sementes de áudio (`seed_track_a`, onda 3) de tools/dev/fake_papo.py.

Rode com: python3 -m unittest discover -s tools/tests -v
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_fake_papo import FAKE, FakeTestCase, clean_env

OGG = b"OggS" + bytes(range(256)) * 4
MP3 = b"ID3" + bytes(reversed(range(256))) * 6


class AudioTestCase(FakeTestCase):
    def messages(self, session=None):
        """As mensagens do canal `audio`, do conteúdo para a mensagem."""
        session = session or self.login("ana")
        _, body = session.call("GET", f"/channels/{self.channel('audio', session)['id']}/messages?limit=100")
        return {m["content"]: m for m in body["messages"]}


class SemPastaDeAudioTest(AudioTestCase):
    def test_o_canal_audio_existe_dentro_da_categoria_geral(self):
        ana = self.login("ana")
        channel = self.channel("audio", ana)
        self.assertEqual("text", channel["type"])
        self.assertEqual(self.channel("Geral", ana)["id"], channel["parent_id"])

    def test_sem_arquivos_so_ha_os_dois_casos_que_nao_precisam_deles(self):
        self.assertEqual({"formato que o aparelho não decodifica (bytes que não são Ogg)",
                          "bloqueado pela moderação, mas a lista ainda não sabe (o arquivo responde 410)"}, set(self.messages()))

    def test_o_anexo_quebrado_e_servido_como_ogg_mas_nao_e_ogg(self):
        attachment = self.messages()["formato que o aparelho não decodifica (bytes que não são Ogg)"]["attachments"][0]
        self.assertEqual(("audio/ogg", "quebrado.ogg", None, None), (attachment["mime_type"], attachment["original_file_name"],
                                                                 attachment["thumbnail_id"], attachment["width"]))
        status, headers, body = self.login("ana").fetch(f"/attachments/{attachment['id']}")
        self.assertEqual((200, "audio/ogg"), (status, headers["Content-Type"]))
        self.assertFalse(body.startswith(b"OggS"))

    def test_o_anexo_removido_diz_limpo_na_lista_e_responde_410(self):
        attachment = self.messages()["bloqueado pela moderação, mas a lista ainda não sabe (o arquivo responde 410)"]["attachments"][0]
        self.assertEqual("clean", attachment["moderation_status"])
        self.assertEqual(410, self.login("ana").fetch(f"/attachments/{attachment['id']}")[0])


class ComPastaDeAudioTest(AudioTestCase):
    """Fake com uma pasta de áudios (FAKE_AUDIO_DIR): `a-voz.ogg` e `b-musica.mp3`."""

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        Path(folder.name, "a-voz.ogg").write_bytes(OGG)
        Path(folder.name, "b-musica.mp3").write_bytes(MP3)
        Path(folder.name, "leia-me.txt").write_text("não é áudio")
        self.env = {**self.env, "FAKE_AUDIO_DIR": folder.name}
        super().setUp()
        self.ana = self.login("ana")
        self.found = self.messages(self.ana)

    def attachment(self, content, index=0):
        return self.found[content]["attachments"][index]

    def test_uma_mensagem_por_audio_da_pasta_com_o_tipo_da_extensao(self):
        voice, music = self.attachment("áudio a-voz.ogg"), self.attachment("áudio b-musica.mp3")
        self.assertEqual(("audio/ogg", len(OGG)), (voice["mime_type"], voice["size_bytes"]))
        self.assertEqual(("audio/mpeg", len(MP3)), (music["mime_type"], music["size_bytes"]))
        self.assertNotIn("áudio leia-me.txt", self.found)

    def test_o_primeiro_audio_e_seguido_de_trinta_mensagens_curtas(self):
        _, body = self.ana.call("GET", f"/channels/{self.channel('audio', self.ana)['id']}/messages?limit=100")
        order = [m["content"] for m in reversed(body["messages"])]   # do mais antigo ao mais novo
        self.assertEqual("áudio a-voz.ogg", order[0])
        self.assertTrue(all("só para o áudio de cima sair da tela" in c for c in order[1:31]), order[1:32])
        self.assertEqual("áudio b-musica.mp3", order[31])

    def test_a_gravacao_de_voz_do_web_tem_tipo_de_video_e_so_o_nome_diz_que_e_audio(self):
        recorded = self.attachment("gravação de voz do web: o servidor a farejou como vídeo, e só o nome diz que é áudio")
        self.assertEqual(("audio_gravado.ogg", "video/webm"), (recorded["original_file_name"], recorded["mime_type"]))

    def test_o_envio_antigo_tem_tipo_generico(self):
        old = self.attachment("envio antigo, de tipo genérico: só a extensão diz que é áudio")
        self.assertEqual(("antigo.ogg", "application/octet-stream"), (old["original_file_name"], old["mime_type"]))

    def test_o_audio_sensivel_e_do_menor_arquivo(self):
        sensitive = self.attachment("áudio sensível: fica escondido até o toque")
        self.assertLess(len(OGG), len(MP3))
        self.assertEqual(("sensitive", "audio/ogg", len(OGG)), (sensitive["moderation_status"], sensitive["mime_type"], sensitive["size_bytes"]))

    def test_dois_audios_numa_mensagem_so(self):
        names = [a["original_file_name"] for a in self.found["dois áudios numa mensagem: só um toca por vez"]["attachments"]]
        self.assertEqual(["a-voz.ogg", "b-musica.mp3"], names)

    def test_o_arquivo_e_servido_inteiro_e_com_trechos(self):
        voice = self.attachment("áudio a-voz.ogg")
        status, headers, body = self.ana.fetch(f"/attachments/{voice['id']}")
        self.assertEqual((200, OGG, "bytes"), (status, body, headers["Accept-Ranges"]))
        status, headers, body = self.ana.fetch(f"/attachments/{voice['id']}", {"Range": "bytes=10-19"})
        self.assertEqual((206, OGG[10:20], f"bytes 10-19/{len(OGG)}"), (status, body, headers["Content-Range"]))
        status, _, body = self.ana.fetch(f"/attachments/{voice['id']}", {"Range": "bytes=-8"})
        self.assertEqual((206, OGG[-8:]), (status, body))


class PastaDeAudioInvalidaTest(unittest.TestCase):
    def run_with(self, **env):
        done = subprocess.run([sys.executable, str(FAKE), "0"], env={**clean_env(), "FAKE_EMOJI_SMALL": "1", **env},
                              capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, done.returncode)
        return done.stderr

    def test_pasta_inexistente_ou_sem_audio_derruba_o_fake_com_mensagem(self):
        self.assertIn("FAKE_AUDIO_DIR: nenhum .ogg, .opus, .mp3, .m4a, .wav ou .webm em /caminho/que/nao/existe",
                      self.run_with(FAKE_AUDIO_DIR="/caminho/que/nao/existe"))
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "leia-me.txt").write_text("sem áudio")
            self.assertIn("FAKE_AUDIO_DIR: nenhum", self.run_with(FAKE_AUDIO_DIR=folder))


if __name__ == "__main__":
    unittest.main()
