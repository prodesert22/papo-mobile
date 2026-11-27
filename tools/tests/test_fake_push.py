"""Testes do registro de push de tools/dev/fake_papo.py (`PUT` e `DELETE /users/me/push-device`).

Rode com: python3 -m unittest discover -s tools/tests -v

As regras vêm do código do servidor real (`handlers/push_device.go`, `services/push_device.go`, `storage/push_devices.go`),
não observadas em execução. O fake não tem como listar os registros (o servidor também não): o dono de um token se vê pelo
`DELETE`, que dá 204 a quem é o dono e 404 a quem não é.
"""

import unittest

from test_fake_papo import FakeTestCase, raw_call

PATH = "/users/me/push-device"


def body(token="tok-1", **fields):
    return {"token": token, "platform": "android", "device_name": "Pixel 9", "provider": "fcm", **fields}


class RegistroTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana, self.bia = self.login("ana"), self.login("bia")

    def test_registrar_responde_204_sem_corpo(self):
        self.assertEqual((204, None), self.ana.call("PUT", PATH, body()))

    def test_tirar_o_proprio_registro_responde_204_e_so_uma_vez(self):
        self.ana.call("PUT", PATH, body())

        self.assertEqual(204, self.ana.call("DELETE", PATH, body())[0])
        self.assertEqual(404, self.ana.call("DELETE", PATH, body())[0])

    def test_tirar_o_que_nunca_foi_registrado_da_404(self):
        self.assertEqual(404, self.ana.call("DELETE", PATH, body("nunca-visto"))[0])

    def test_registrar_de_novo_o_mesmo_token_atualiza_e_continua_um_so(self):
        self.ana.call("PUT", PATH, body())

        self.assertEqual(204, self.ana.call("PUT", PATH, body(device_name="Outro nome"))[0])
        self.assertEqual(204, self.ana.call("DELETE", PATH, body())[0])
        self.assertEqual(404, self.ana.call("DELETE", PATH, body())[0])

    def test_o_mesmo_token_passa_para_quem_registrou_por_ultimo(self):
        # A limitação do servidor: o token é único no servidor inteiro e o upsert troca o dono.
        self.ana.call("PUT", PATH, body())
        self.bia.call("PUT", PATH, body())

        self.assertEqual(404, self.ana.call("DELETE", PATH, body())[0])
        self.assertEqual(204, self.bia.call("DELETE", PATH, body())[0])

    def test_tirar_o_token_de_outro_usuario_da_404_e_nao_o_tira(self):
        self.ana.call("PUT", PATH, body())

        self.assertEqual(404, self.bia.call("DELETE", PATH, body())[0])
        self.assertEqual(204, self.ana.call("DELETE", PATH, body())[0])

    def test_tokens_diferentes_convivem_no_mesmo_usuario(self):
        self.ana.call("PUT", PATH, body("tok-1"))
        self.ana.call("PUT", PATH, body("tok-2"))

        self.assertEqual(204, self.ana.call("DELETE", PATH, body("tok-1"))[0])
        self.assertEqual(204, self.ana.call("DELETE", PATH, body("tok-2"))[0])

    def test_o_token_se_compara_sem_os_espacos_das_pontas(self):
        self.ana.call("PUT", PATH, body("  tok-1 "))

        self.assertEqual(204, self.ana.call("DELETE", PATH, body("tok-1"))[0])

    def test_o_provedor_vazio_vale_fcm_e_a_ausencia_dele_tambem(self):
        self.assertEqual(204, self.ana.call("PUT", PATH, {"token": "tok-1", "platform": "android"})[0])
        self.assertEqual(204, self.ana.call("PUT", PATH, body("tok-2", provider=""))[0])

    def test_ios_tambem_e_aceito(self):
        self.assertEqual(204, self.ana.call("PUT", PATH, body(platform="ios"))[0])


class ValidacaoTest(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.ana = self.login("ana")

    def assert_invalid(self, method, payload=None, raw=None):
        status, problem = self.ana.call(method, PATH, payload, raw=raw)
        self.assertEqual(400, status, problem)
        self.assertEqual(400, problem["status"])

    def test_token_ausente_vazio_ou_em_branco_da_400_nos_dois_metodos(self):
        for method in ("PUT", "DELETE"):
            with self.subTest(method=method):
                self.assert_invalid(method, {"platform": "android"})
                self.assert_invalid(method, body(""))
                self.assert_invalid(method, body("   "))
                self.assert_invalid(method, body(None))
                self.assert_invalid(method, body(7))

    def test_token_de_ate_1024_bytes_vale_e_com_mais_nao(self):
        self.assertEqual(204, self.ana.call("PUT", PATH, body("t" * 1024))[0])
        self.assert_invalid("PUT", body("t" * 1025))
        self.assert_invalid("DELETE", body("t" * 1025))

    def test_plataforma_precisa_ser_android_ou_ios(self):
        self.assert_invalid("PUT", body(platform="windows"))
        self.assert_invalid("PUT", body(platform=""))
        self.assert_invalid("PUT", {"token": "tok-1"})

    def test_o_delete_nao_confere_a_plataforma(self):
        self.ana.call("PUT", PATH, body())

        self.assertEqual(204, self.ana.call("DELETE", PATH, {"token": "tok-1"})[0])

    def test_nome_do_aparelho_de_ate_32_bytes_vale_e_com_mais_nao(self):
        self.assertEqual(204, self.ana.call("PUT", PATH, body(device_name="n" * 32))[0])
        self.assertEqual(204, self.ana.call("PUT", PATH, body(device_name="  " + "n" * 32 + "  "))[0])
        self.assertEqual(204, self.ana.call("PUT", PATH, body(device_name=""))[0])
        self.assert_invalid("PUT", body(device_name="n" * 33))

    def test_o_nome_do_aparelho_conta_bytes_e_nao_caracteres(self):
        self.assertEqual(204, self.ana.call("PUT", PATH, body(device_name="é" * 16))[0])
        self.assert_invalid("PUT", body(device_name="é" * 17))

    def test_corpo_que_nao_e_um_objeto_ou_nao_e_json_da_400(self):
        self.assert_invalid("PUT", raw=b"[1, 2]")
        self.assert_invalid("PUT", raw=b"nao e json")
        self.assert_invalid("DELETE", raw=b"")

    def test_um_pedido_recusado_nao_registra_nada(self):
        self.assert_invalid("PUT", body(platform="windows"))

        self.assertEqual(404, self.ana.call("DELETE", PATH, body())[0])


class SemSessaoTest(FakeTestCase):
    def test_sem_cookie_os_dois_metodos_dao_401(self):
        for method in ("PUT", "DELETE"):
            with self.subTest(method=method):
                status, _, problem = raw_call(self.port, method, PATH, body=body())
                self.assertEqual(401, status)
                self.assertEqual(401, problem["status"])

    def test_cookie_que_nao_vale_da_401(self):
        status, _, _ = raw_call(self.port, "PUT", PATH, token="sessao-que-nao-existe", body=body())

        self.assertEqual(401, status)

    def test_sem_sessao_nada_e_registrado(self):
        raw_call(self.port, "PUT", PATH, body=body())

        self.assertEqual(404, self.login("ana").call("DELETE", PATH, body())[0])

    def test_depois_do_logout_o_cookie_velho_nao_registra(self):
        ana = self.login("ana")
        old = ana.auth()
        ana.call("POST", "/auth/logout")

        status, _, _ = raw_call(self.port, "PUT", PATH, token=old, body=body())

        self.assertEqual(401, status)


class OutrasRotasTest(FakeTestCase):
    def test_outro_metodo_na_mesma_rota_nao_existe(self):
        ana = self.login("ana")

        self.assertEqual(404, ana.call("POST", PATH, body())[0])
        self.assertEqual(404, ana.call("GET", PATH)[0])

    def test_outro_usuario_na_rota_nao_e_o_registro_de_push(self):
        ana = self.login("ana")

        self.assertEqual(404, ana.call("PUT", "/users/outra-pessoa/push-device", body())[0])


if __name__ == "__main__":
    unittest.main()
