#!/usr/bin/env python3
"""Identidade e notas de versão do Papo Mobile.

A versão do app vive em `gradle.properties` (`papo.version`). Uma tag `vX.Y.Z`
ou `vX.Y.Z-beta.N` só vira release quando bate com esse valor e as notas
existem: versões finais têm `releases/vX.Y.Z.md`; betas não têm arquivo e saem
com o conteúdo de `releases/Unreleased.md`.

Uso (a partir da raiz do repositório):
    tools/release.py version                   versão declarada
    tools/release.py version-code [VERSÃO]     versionCode do Android
    tools/release.py check TAG [--require-main]
    tools/release.py changed-since SHA         valida uma mudança de versão (PR)
    tools/release.py previous TAG              tag usada no link de comparação
    tools/release.py title TAG                 título da release
    tools/release.py notes TAG --repo DONO/NOME   corpo da release
"""

from __future__ import annotations

import argparse
import posixpath
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

APP_NAME = "Papo Mobile"
VERSION_KEY = "papo.version"
UNRELEASED = "Unreleased.md"

_VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?")
# Links Markdown que não são absolutos nem âncoras da própria página.
_RELATIVE_LINK = re.compile(r"\]\((?!https?://|mailto:|#)([^)\s]+)\)")


class ReleaseError(Exception):
    """Algo impede a release; a mensagem diz o quê e como resolver."""


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int
    beta: int | None = None

    @staticmethod
    def parse(text: str) -> "Version":
        match = _VERSION.fullmatch(text.strip())
        if not match:
            raise ReleaseError(
                f"versão inválida: {text!r} (use major.minor.patch ou major.minor.patch-beta.N, ex.: 1.0.1 ou 1.0.1-beta.1)"
            )
        major, minor, patch = (int(match.group(i)) for i in (1, 2, 3))
        beta = int(match.group(4)) if match.group(4) is not None else None
        # Limites do versionCode: cada parte ocupa duas casas decimais.
        if minor > 99 or patch > 99:
            raise ReleaseError(f"versão inválida: {text!r} (minor e patch vão até 99)")
        if beta is not None and not 1 <= beta <= 98:
            raise ReleaseError(f"versão inválida: {text!r} (o número do beta vai de 1 a 98)")
        return Version(major, minor, patch, beta)

    @staticmethod
    def from_tag(tag: str) -> "Version":
        if not tag.startswith("v"):
            raise ReleaseError(f"tag inválida: {tag!r} (tags de versão começam com 'v', ex.: v1.0.1)")
        return Version.parse(tag[1:])

    @property
    def is_beta(self) -> bool:
        return self.beta is not None

    @property
    def base(self) -> "Version":
        """A versão final a que um beta pertence."""
        return Version(self.major, self.minor, self.patch)

    @property
    def tag(self) -> str:
        return f"v{self}"

    @property
    def code(self) -> int:
        """versionCode do Android: cresce com a versão e põe os betas antes da final."""
        return self.major * 1_000_000 + self.minor * 10_000 + self.patch * 100 + (self.beta if self.is_beta else 99)

    @property
    def order(self) -> tuple[int, int, int, int, int]:
        return (self.major, self.minor, self.patch, 0 if self.is_beta else 1, self.beta or 0)

    def __str__(self) -> str:
        text = f"{self.major}.{self.minor}.{self.patch}"
        return f"{text}-beta.{self.beta}" if self.is_beta else text


# Arquivos do repositório


def read_version(root: Path, text: str | None = None) -> Version:
    """Lê `papo.version` do gradle.properties (ou de [text], o conteúdo de outra revisão)."""
    if text is None:
        text = (root / "gradle.properties").read_text(encoding="utf-8")
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == VERSION_KEY:
            return Version.parse(value)
    raise ReleaseError(f"{VERSION_KEY} não está definido em gradle.properties")


def notes_file(root: Path, version: Version) -> Path:
    return root / "releases" / f"{version.base.tag}.md"


def unreleased(root: Path) -> str:
    path = root / "releases" / UNRELEASED
    return path.read_text(encoding="utf-8").strip() if path.exists() else ""


def final_notes(root: Path, version: Version) -> str:
    """Corpo das notas de uma versão final, sem a linha de título."""
    path = notes_file(root, version)
    if not path.exists():
        raise ReleaseError(f"faltam as notas da versão: crie releases/{path.name}")
    lines = path.read_text(encoding="utf-8").strip().splitlines()
    expected = f"# {APP_NAME} {version}"
    if not lines or lines[0].strip() != expected:
        raise ReleaseError(f"releases/{path.name} deve começar com o título {expected!r}")
    body = "\n".join(lines[1:]).strip()
    if not body:
        raise ReleaseError(f"releases/{path.name} só tem o título; descreva o que mudou")
    return body


# Git


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if result.returncode != 0:
        raise ReleaseError(f"git {' '.join(args)} falhou: {result.stderr.strip()}")
    return result.stdout.strip()


def released_versions(root: Path) -> list[Version]:
    """Versões que já têm tag, ignorando tags que não são de versão."""
    versions = []
    for tag in git(root, "tag", "--list", "v*").splitlines():
        try:
            versions.append(Version.from_tag(tag.strip()))
        except ReleaseError:
            continue
    return sorted(versions, key=lambda v: v.order)


def main_ref(root: Path) -> str:
    for ref in ("origin/main", "main"):
        if subprocess.run(["git", "rev-parse", "--verify", "--quiet", ref], cwd=root, capture_output=True).returncode == 0:
            return ref
    raise ReleaseError("não encontrei a branch main para conferir a origem da versão")


def head_is_on_main(root: Path) -> bool:
    ref = main_ref(root)
    return subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD", ref], cwd=root, capture_output=True).returncode == 0


# Regras


def check_notes(root: Path, version: Version) -> None:
    """As notas que a versão precisa ter no repositório."""
    pending = unreleased(root)
    if version.is_beta:
        if not pending:
            raise ReleaseError(
                f"um beta sai com o conteúdo de releases/{UNRELEASED}, que está vazio: registre nele o que mudou"
            )
        return
    final_notes(root, version)
    if pending:
        raise ReleaseError(
            f"releases/{UNRELEASED} ainda tem mudanças: mova-as para releases/{version.tag}.md e deixe-o vazio"
        )


def check(root: Path, tag: str, tags: list[Version], require_main: bool = False) -> Version:
    """Confere se [tag] pode virar release a partir do commit em uso. Devolve a versão."""
    version = Version.from_tag(tag)
    declared = read_version(root)
    if declared != version:
        raise ReleaseError(
            f"a tag {tag} não bate com {VERSION_KEY}={declared} em gradle.properties: ajuste a versão ou use a tag {declared.tag}"
        )
    if version.is_beta and version.base in tags:
        raise ReleaseError(f"a versão {version.base} já foi lançada; não cabe mais um beta dela")
    check_notes(root, version)
    if not version.is_beta and require_main and not head_is_on_main(root):
        raise ReleaseError(f"a versão final {version} precisa sair de um commit da main")
    return version


def changed_since(root: Path, base_sha: str) -> Version | None:
    """Se a versão mudou desde [base_sha], valida a mudança e devolve a nova versão."""
    current = read_version(root)
    shown = subprocess.run(["git", "show", f"{base_sha}:gradle.properties"], cwd=root, capture_output=True, text=True)
    previous: Version | None = None
    if shown.returncode == 0:
        try:
            previous = read_version(root, shown.stdout)
        except ReleaseError:
            previous = None
    if previous == current:
        return None
    if previous is not None and current.order < previous.order:
        raise ReleaseError(f"a versão não pode diminuir: {previous} -> {current}")
    check_notes(root, current)
    return current


def previous_version(version: Version, tags: list[Version]) -> Version | None:
    """
    A versão contra a qual comparar. Uma final compara com a final anterior, para
    o link cobrir também os betas dela; um beta compara com o que saiu logo antes.
    """
    older = [t for t in tags if t.order < version.order and (version.is_beta or not t.is_beta)]
    return max(older, key=lambda v: v.order) if older else None


def title(version: Version) -> str:
    return f"{APP_NAME} {version}"


def absolute_links(text: str, repo: str, tag: str) -> str:
    """Links relativos das notas só funcionam no repositório; na release viram links para a tag."""

    def replace(match: re.Match[str]) -> str:
        target, hash_mark, anchor = match.group(1).partition("#")
        path = posixpath.normpath(posixpath.join("releases", target))
        if path.startswith(".."):
            return match.group(0)
        return f"](https://github.com/{repo}/blob/{tag}/{path}{hash_mark}{anchor})"

    return _RELATIVE_LINK.sub(replace, text)


def notes(root: Path, tag: str, repo: str, tags: list[Version]) -> str:
    """Corpo da release no GitHub: as notas da versão mais o link de comparação."""
    version = Version.from_tag(tag)
    previous = previous_version(version, tags)
    if version.is_beta:
        pending = unreleased(root)
        if not pending:
            raise ReleaseError(f"releases/{UNRELEASED} está vazio: não há o que publicar no beta")
        last_final = previous_version(version.base, tags)
        heading = f"## Changes since {last_final}" if last_final else "## Changes"
        body = (
            f"This is a **pre-release** of {APP_NAME} {version.base} for testing. It may contain unfinished\n"
            "changes; keep using the latest stable version if you need one.\n\n"
            f"{heading}\n\n{pending}"
        )
    else:
        body = final_notes(root, version)
    if previous:
        link = f"https://github.com/{repo}/compare/{previous.tag}...{tag}"
    else:
        link = f"https://github.com/{repo}/commits/{tag}"
    return f"{absolute_links(body, repo, tag)}\n\n**Full changelog**: {link}\n"


# Linha de comando


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Identidade e notas de versão do Papo Mobile")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent, help="raiz do repositório")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version")
    code = commands.add_parser("version-code")
    code.add_argument("version", nargs="?")
    checker = commands.add_parser("check")
    checker.add_argument("tag")
    checker.add_argument("--require-main", action="store_true")
    changed = commands.add_parser("changed-since")
    changed.add_argument("sha")
    for name in ("previous", "title", "prerelease"):
        commands.add_parser(name).add_argument("tag")
    body = commands.add_parser("notes")
    body.add_argument("tag")
    body.add_argument("--repo", required=True)
    args = parser.parse_args(argv)
    root: Path = args.root

    try:
        if args.command == "version":
            print(read_version(root))
        elif args.command == "version-code":
            print((Version.parse(args.version) if args.version else read_version(root)).code)
        elif args.command == "check":
            print(check(root, args.tag, released_versions(root), args.require_main))
        elif args.command == "changed-since":
            version = changed_since(root, args.sha)
            if version:
                print(version)
        elif args.command == "previous":
            previous = previous_version(Version.from_tag(args.tag), released_versions(root))
            if previous:
                print(previous.tag)
        elif args.command == "title":
            print(title(Version.from_tag(args.tag)))
        elif args.command == "prerelease":
            print("true" if Version.from_tag(args.tag).is_beta else "false")
        elif args.command == "notes":
            sys.stdout.write(notes(root, args.tag, args.repo, released_versions(root)))
    except ReleaseError as error:
        print(f"erro: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
