"""Testes de tools/release.py. Rode com: python3 -m unittest discover -s tools/tests -v"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import release  # noqa: E402
from release import ReleaseError, Version  # noqa: E402

GIT_ENV = {
    **os.environ,
    "GIT_AUTHOR_NAME": "Teste",
    "GIT_AUTHOR_EMAIL": "teste@example.com",
    "GIT_COMMITTER_NAME": "Teste",
    "GIT_COMMITTER_EMAIL": "teste@example.com",
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_SYSTEM": os.devnull,
}


def v(text: str) -> Version:
    return Version.parse(text)


class VersionTest(unittest.TestCase):
    def test_aceita_final_e_beta(self):
        self.assertEqual(Version(1, 0, 1), v("1.0.1"))
        self.assertEqual(Version(1, 0, 1, 2), v("1.0.1-beta.2"))
        self.assertEqual("1.0.1-beta.2", str(v("1.0.1-beta.2")))
        self.assertEqual("v1.0.1-beta.2", v("1.0.1-beta.2").tag)
        self.assertEqual(v("1.0.1"), v("1.0.1-beta.2").base)
        self.assertEqual(v("0.1.0"), Version.from_tag("v0.1.0"))

    def test_recusa_formatos_invalidos(self):
        for text in ("1.0", "1.0.1.2", "v1.0.1", "1.0.1-beta", "1.0.1-beta.0", "1.0.1-rc.1", "1.0.1-beta.99", "1.100.0", "1.0.100", "", "a.b.c"):
            with self.subTest(text=text), self.assertRaises(ReleaseError):
                Version.parse(text)
        with self.assertRaises(ReleaseError):
            Version.from_tag("1.0.1")  # tag sem o 'v'

    def test_version_code(self):
        self.assertEqual(10099, v("0.1.0").code)
        self.assertEqual(1000102, v("1.0.1-beta.2").code)
        self.assertEqual(1000199, v("1.0.1").code)
        ordered = ["0.1.0-beta.1", "0.1.0-beta.2", "0.1.0", "0.1.1-beta.1", "0.1.1", "0.2.0", "0.99.99", "1.0.0-beta.1", "1.0.0", "12.3.4"]
        codes = [v(text).code for text in ordered]
        self.assertEqual(sorted(codes), codes)
        self.assertEqual(len(set(codes)), len(codes))
        # O Android aceita versionCode até 2.100.000.000.
        self.assertLess(v("999.99.99").code, 2_100_000_000)

    def test_escolha_da_versao_anterior(self):
        tags = [v(t) for t in ("0.1.0-beta.1", "0.1.0-beta.2", "0.1.0", "0.2.0-beta.1", "0.2.0-beta.2")]
        previous = lambda text: release.previous_version(v(text), tags)  # noqa: E731
        # Beta compara com o que saiu logo antes, seja beta ou final.
        self.assertEqual(v("0.1.0-beta.1"), previous("0.1.0-beta.2"))
        self.assertEqual(v("0.1.0"), previous("0.2.0-beta.1"))
        self.assertEqual(v("0.2.0-beta.1"), previous("0.2.0-beta.2"))
        # Final compara com a final anterior: o link cobre todos os betas dela.
        self.assertEqual(v("0.1.0"), previous("0.2.0"))
        self.assertIsNone(previous("0.1.0"))
        # A primeira de todas não tem com o que comparar.
        self.assertIsNone(previous("0.1.0-beta.1"))
        self.assertIsNone(release.previous_version(v("1.0.0"), []))


class RepositoryTest(unittest.TestCase):
    """Cada teste roda num repositório git descartável."""

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name)
        (self.root / "releases").mkdir()
        self.git("init", "-q", "-b", "main")
        self.write("gradle.properties", "org.gradle.parallel=true\npapo.version=0.1.0\n")
        self.write("releases/Unreleased.md", "")
        self.write("releases/v0.1.0.md", "# Papo Mobile 0.1.0\n\nFirst release.\n\n## Chat\n\n- Text channels.\n")
        self.commit("inicial")

    def tearDown(self):
        self._dir.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, env=GIT_ENV, check=True, capture_output=True, text=True).stdout.strip()

    def write(self, path: str, text: str) -> None:
        (self.root / path).write_text(text, encoding="utf-8")

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def set_version(self, version: str) -> None:
        self.write("gradle.properties", f"org.gradle.parallel=true\npapo.version={version}\n")

    def tags(self):
        return release.released_versions(self.root)

    def check(self, tag: str, require_main: bool = False):
        return release.check(self.root, tag, self.tags(), require_main)

    def assertRefused(self, fragment: str, callable_, *args):
        with self.assertRaises(ReleaseError) as raised:
            callable_(*args)
        self.assertIn(fragment, str(raised.exception))

    # check

    def test_final_valida(self):
        self.assertEqual(v("0.1.0"), self.check("v0.1.0", require_main=True))

    def test_tag_precisa_bater_com_a_versao_do_arquivo(self):
        self.assertRefused("não bate com papo.version=0.1.0", self.check, "v0.1.1")
        self.assertRefused("não bate", self.check, "v0.1.0-beta.1")

    def test_final_exige_o_arquivo_de_notas_com_titulo_e_conteudo(self):
        self.set_version("0.2.0")
        self.assertRefused("crie releases/v0.2.0.md", self.check, "v0.2.0")
        self.write("releases/v0.2.0.md", "# Papo 0.2.0\n\ntexto\n")
        self.assertRefused("deve começar com o título '# Papo Mobile 0.2.0'", self.check, "v0.2.0")
        self.write("releases/v0.2.0.md", "# Papo Mobile 0.2.0\n\n")
        self.assertRefused("só tem o título", self.check, "v0.2.0")
        self.write("releases/v0.2.0.md", "# Papo Mobile 0.2.0\n\nNotas.\n")
        self.assertEqual(v("0.2.0"), self.check("v0.2.0"))

    def test_final_exige_unreleased_vazio(self):
        self.write("releases/Unreleased.md", "- Added something.\n")
        self.assertRefused("ainda tem mudanças", self.check, "v0.1.0")

    def test_beta_nao_tem_arquivo_e_sai_do_unreleased(self):
        self.set_version("0.2.0-beta.1")
        self.assertRefused("que está vazio", self.check, "v0.2.0-beta.1")
        self.write("releases/Unreleased.md", "- Added voice calls.\n")
        self.assertEqual(v("0.2.0-beta.1"), self.check("v0.2.0-beta.1"))
        self.assertFalse((self.root / "releases" / "v0.2.0-beta.1.md").exists())
        self.assertFalse((self.root / "releases" / "v0.2.0.md").exists())

    def test_nao_ha_beta_de_versao_ja_lancada(self):
        self.git("tag", "v0.1.0")
        self.set_version("0.1.0-beta.3")
        self.write("releases/Unreleased.md", "- Algo.\n")
        self.assertRefused("já foi lançada", self.check, "v0.1.0-beta.3")

    def test_final_so_sai_da_main(self):
        self.git("checkout", "-q", "-b", "experimento")
        self.set_version("0.2.0")
        self.write("releases/v0.2.0.md", "# Papo Mobile 0.2.0\n\nNotas.\n")
        self.commit("versão fora da main")
        self.assertRefused("precisa sair de um commit da main", self.check, "v0.2.0", True)
        self.assertEqual(v("0.2.0"), self.check("v0.2.0"))  # sem a exigência, passa
        # Beta pode sair de qualquer branch.
        self.set_version("0.2.0-beta.1")
        self.write("releases/Unreleased.md", "- Algo.\n")
        self.assertEqual(v("0.2.0-beta.1"), self.check("v0.2.0-beta.1", True))

    def test_tags_que_nao_sao_de_versao_sao_ignoradas(self):
        for tag in ("v0.1.0", "vnext", "v1", "backup-2026"):
            self.git("tag", tag)
        self.assertEqual([v("0.1.0")], self.tags())

    # changed-since

    def test_mudanca_de_versao_em_pr(self):
        base = self.git("rev-parse", "HEAD")
        self.assertIsNone(release.changed_since(self.root, base))  # nada mudou

        self.set_version("0.2.0")
        self.assertRefused("crie releases/v0.2.0.md", release.changed_since, self.root, base)
        self.write("releases/v0.2.0.md", "# Papo Mobile 0.2.0\n\nNotas.\n")
        self.assertEqual(v("0.2.0"), release.changed_since(self.root, base))

        self.set_version("0.0.9")
        self.assertRefused("não pode diminuir", release.changed_since, self.root, base)

        self.set_version("0.2.0-beta.1")
        self.assertRefused("que está vazio", release.changed_since, self.root, base)

    def test_mudanca_de_versao_sem_revisao_de_base(self):
        # Primeiro push de uma branch: não há revisão anterior para comparar.
        self.assertEqual(v("0.1.0"), release.changed_since(self.root, "0" * 40))

    # notes

    def test_notas_da_primeira_versao_apontam_para_os_commits(self):
        self.git("tag", "v0.1.0")
        body = release.notes(self.root, "v0.1.0", "dono/papo-mobile", self.tags())
        self.assertFalse(body.startswith("#"), "o título vai no nome da release, não no corpo")
        self.assertTrue(body.startswith("First release."))
        self.assertIn("## Chat", body)
        self.assertTrue(body.endswith("**Full changelog**: https://github.com/dono/papo-mobile/commits/v0.1.0\n"))

    def test_notas_de_beta_usam_o_unreleased(self):
        self.git("tag", "v0.1.0")
        self.set_version("0.2.0-beta.1")
        self.write("releases/Unreleased.md", "- Added voice calls.\n- Fixed the lock screen.\n")
        self.commit("beta 1")
        self.git("tag", "v0.2.0-beta.1")
        body = release.notes(self.root, "v0.2.0-beta.1", "dono/papo-mobile", self.tags())
        self.assertIn("This is a **pre-release** of Papo Mobile 0.2.0 for testing.", body)
        self.assertIn("## Changes since 0.1.0\n\n- Added voice calls.\n- Fixed the lock screen.", body)
        self.assertTrue(body.endswith("**Full changelog**: https://github.com/dono/papo-mobile/compare/v0.1.0...v0.2.0-beta.1\n"))

        self.set_version("0.2.0-beta.2")
        self.write("releases/Unreleased.md", "- Added voice calls.\n- Fixed the lock screen.\n- Fixed a crash.\n")
        self.commit("beta 2")
        self.git("tag", "v0.2.0-beta.2")
        body = release.notes(self.root, "v0.2.0-beta.2", "dono/papo-mobile", self.tags())
        self.assertIn("- Fixed a crash.", body)
        self.assertIn("## Changes since 0.1.0", body)  # o título segue a última final
        self.assertTrue(body.endswith("compare/v0.2.0-beta.1...v0.2.0-beta.2\n"))  # o link, o beta anterior

    def test_notas_da_final_comparam_com_a_final_anterior(self):
        for tag in ("v0.1.0", "v0.2.0-beta.1", "v0.2.0-beta.2"):
            self.git("tag", tag)
        self.set_version("0.2.0")
        self.write("releases/v0.2.0.md", "# Papo Mobile 0.2.0\n\nVoice calls.\n")
        self.commit("0.2.0")
        self.git("tag", "v0.2.0")
        body = release.notes(self.root, "v0.2.0", "dono/papo-mobile", self.tags())
        self.assertTrue(body.endswith("**Full changelog**: https://github.com/dono/papo-mobile/compare/v0.1.0...v0.2.0\n"))

    def test_primeiro_beta_sem_final_anterior(self):
        self.set_version("0.1.0-beta.1")
        self.write("releases/Unreleased.md", "- First build.\n")
        self.git("tag", "v0.1.0-beta.1")
        body = release.notes(self.root, "v0.1.0-beta.1", "dono/papo-mobile", self.tags())
        self.assertIn("## Changes\n\n- First build.", body)
        self.assertTrue(body.endswith("https://github.com/dono/papo-mobile/commits/v0.1.0-beta.1\n"))

    def test_links_relativos_viram_absolutos_na_tag(self):
        text = (
            "See [0.1.0](v0.1.0.md), the [guide](../docs/usage.md#calls), "
            "[site](https://papo.example.com), [anchor](#known-issues) and [mail](mailto:a@b.c)."
        )
        self.assertEqual(
            "See [0.1.0](https://github.com/dono/repo/blob/v0.2.0/releases/v0.1.0.md), "
            "the [guide](https://github.com/dono/repo/blob/v0.2.0/docs/usage.md#calls), "
            "[site](https://papo.example.com), [anchor](#known-issues) and [mail](mailto:a@b.c).",
            release.absolute_links(text, "dono/repo", "v0.2.0"),
        )
        # Um caminho que sai do repositório não é reescrito.
        self.assertEqual("[x](../../fora.md)", release.absolute_links("[x](../../fora.md)", "dono/repo", "v1.0.0"))

    # linha de comando

    def run_cli(self, *args: str):
        return subprocess.run(
            [sys.executable, str(Path(release.__file__)), "--root", str(self.root), *args],
            capture_output=True, text=True, env=GIT_ENV,
        )

    def test_linha_de_comando(self):
        self.assertEqual("0.1.0", self.run_cli("version").stdout.strip())
        self.assertEqual("10099", self.run_cli("version-code").stdout.strip())
        self.assertEqual("1000102", self.run_cli("version-code", "1.0.1-beta.2").stdout.strip())
        self.assertEqual("Papo Mobile 0.1.0", self.run_cli("title", "v0.1.0").stdout.strip())
        self.assertEqual("false", self.run_cli("prerelease", "v0.1.0").stdout.strip())
        self.assertEqual("true", self.run_cli("prerelease", "v0.1.0-beta.1").stdout.strip())
        self.assertEqual("", self.run_cli("previous", "v0.1.0").stdout.strip())

        ok = self.run_cli("check", "v0.1.0", "--require-main")
        self.assertEqual((0, "0.1.0"), (ok.returncode, ok.stdout.strip()))
        bad = self.run_cli("check", "v9.9.9")
        self.assertEqual(1, bad.returncode)
        self.assertIn("erro: a tag v9.9.9 não bate", bad.stderr)
        self.assertEqual("", bad.stdout)


if __name__ == "__main__":
    unittest.main()
