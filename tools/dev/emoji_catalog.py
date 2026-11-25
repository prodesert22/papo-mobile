#!/usr/bin/env python3
"""Gera o catálogo de emojis Unicode do app a partir do pacote npm `emojibase-data`.

Uso: python3 -I tools/dev/emoji_catalog.py [--check] [--tarball arquivo.tgz]

Sem argumentos baixa o pacote fixado abaixo, confere o hash de integridade e grava
shared/src/commonMain/composeResources/files/emoji-catalog.tsv. `--check` regenera em
memória e falha se o arquivo versionado for diferente (reprodutibilidade).
`--tarball` usa um arquivo já baixado em vez da rede; o hash é conferido do mesmo jeito.

O pacote é lido direto do tarball, sem extrair nada em disco. Rode com `python3 -I`.

Licenças: emojibase-data é MIT; os nomes e as palavras-chave vêm do CLDR e seguem a
licença Unicode-3.0. Os dois textos estão em licenses/.
"""
import argparse
import base64
import hashlib
import io
import json
import sys
import tarfile
import urllib.request
from pathlib import Path

VERSION = "18.0.0"
URL = f"https://registry.npmjs.org/emojibase-data/-/emojibase-data-{VERSION}.tgz"
# Integridade publicada pelo registro npm para essa versão (sha512, base64).
INTEGRITY = "sha512-TPPRRYv2zPd0jKI+pTpMquwWNZXYAsC3IVsCMeg2X8y8fEzKwOks4/5bz0YavF5r6d71aGPpb6oDnUEwSLLMoA=="
OUTPUT = Path(__file__).resolve().parents[2] / "shared/src/commonMain/composeResources/files/emoji-catalog.tsv"

# Grupos do emojibase (0..9) -> índice do app. O 2 são componentes (tons de pele, cabelo)
# e não aparece no seletor.
GROUPS = {0: 0, 1: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6, 8: 7, 9: 8}
MAX_SHORTCODES = 6


def fetch() -> bytes:
    with urllib.request.urlopen(URL, timeout=120) as response:
        return response.read()


def verify(data: bytes) -> None:
    digest = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    if digest != INTEGRITY:
        sys.exit(f"integridade diferente da esperada para emojibase-data {VERSION}: {digest}")


def read_members(data: bytes) -> dict:
    wanted = {
        "package/pt/data.json",
        "package/en/data.json",
        "package/en/shortcodes/github.json",
        "package/en/shortcodes/emojibase.json",
        "package/LICENSE",
    }
    found = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar:
            if member.name in wanted and member.isfile():
                found[member.name] = tar.extractfile(member).read()
    missing = wanted - found.keys()
    if missing:
        sys.exit(f"faltam arquivos no pacote: {sorted(missing)}")
    return found


def clean(text: str) -> str:
    # O TSV usa tab e barra vertical como separadores.
    if "\t" in text or "|" in text or "\n" in text:
        sys.exit(f"texto com separador reservado: {text!r}")
    return text.strip()


def shortcodes(hexcode: str, *tables: dict) -> list:
    out = []
    for table in tables:
        for key in (hexcode, hexcode.replace("-FE0F", "")):
            value = table.get(key)
            if value is None:
                continue
            for name in [value] if isinstance(value, str) else value:
                if name not in out:
                    out.append(name)
            break
    return out[:MAX_SHORTCODES]


def build(files: dict) -> str:
    pt = json.loads(files["package/pt/data.json"])
    en = {e["hexcode"]: e for e in json.loads(files["package/en/data.json"])}
    github = json.loads(files["package/en/shortcodes/github.json"])
    emojibase = json.loads(files["package/en/shortcodes/emojibase.json"])

    rows = []
    for entry in pt:
        if "order" not in entry or entry.get("group") not in GROUPS:
            continue
        english = en.get(entry["hexcode"], {})
        rows.append((
            entry["order"],
            [
                entry["emoji"],
                str(GROUPS[entry["group"]]),
                clean(entry["label"]),
                clean(english.get("label", "")),
                "|".join(clean(tag) for tag in entry.get("tags", [])),
                "|".join(clean(name) for name in shortcodes(entry["hexcode"], github, emojibase)),
            ],
        ))
    rows.sort(key=lambda row: row[0])

    header = [
        "# Catálogo de emojis Unicode do Papo Mobile. Gerado por tools/dev/emoji_catalog.py; não edite à mão.",
        f"# Fonte: emojibase-data {VERSION} (MIT), com nomes e palavras-chave do CLDR (Unicode-3.0).",
        "# Colunas: emoji, grupo, nome (pt), nome (en), palavras-chave (pt, separadas por |), atalhos (separados por |).",
        "# Grupos: 0 carinhas, 1 pessoas, 2 animais e natureza, 3 comida, 4 viagens, 5 atividades, 6 objetos, 7 símbolos, 8 bandeiras.",
    ]
    return "\n".join(header + ["\t".join(fields) for _, fields in rows]) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="falha se o arquivo versionado diferir do gerado")
    parser.add_argument("--tarball", type=Path, help="usa este arquivo em vez de baixar o pacote")
    args = parser.parse_args()

    data = args.tarball.read_bytes() if args.tarball else fetch()
    verify(data)
    content = build(read_members(data))

    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current != content:
            sys.exit(f"{OUTPUT} não corresponde ao que o gerador produz")
        print("catálogo em dia")
        return
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(content, encoding="utf-8")
    print(f"{OUTPUT}: {content.count(chr(10)) - 4} emojis, {len(content.encode('utf-8'))} bytes")


if __name__ == "__main__":
    main()
