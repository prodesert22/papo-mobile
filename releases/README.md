# Versões e notas de versão

A versão do app fica em `gradle.properties`, na chave `papo.version`, no formato
`major.minor.patch` com `-beta.N` opcional (`1.0.1`, `1.0.1-beta.2`). Dela saem o
`versionName` e o `versionCode` do Android, e a tag de release é sempre `v` + esse valor.

## O que fica nesta pasta

- `Unreleased.md` — o que mudou desde a última versão final, em tópicos, em inglês.
  Registre aqui cada novidade, mudança de comportamento ou correção na mesma entrega da
  alteração, descrevendo o efeito para quem usa o app.
- `vX.Y.Z.md` — as notas de cada versão **final**. Começam com o título
  `# Papo Mobile X.Y.Z`, seguido de um parágrafo de abertura e de seções `##`.

Betas não têm arquivo: a release `vX.Y.Z-beta.N` é publicada com o conteúdo do
`Unreleased.md` daquele momento.

## Lançar uma versão final

1. Em `gradle.properties`, ajuste `papo.version` (por exemplo `0.2.0`).
2. Crie `releases/v0.2.0.md` a partir do `Unreleased.md` e deixe o `Unreleased.md` vazio.
3. Leve esse commit para a `main`.
4. Dispare a release, de um dos dois jeitos:
   - crie e envie a tag: `git tag -a v0.2.0 -m "Papo Mobile 0.2.0" && git push origin v0.2.0`
     (o `-a -m` evita que o Git abra o editor pedindo a mensagem quando a assinatura de
     tags está ligada); ou
   - no GitHub, em **Actions → Release → Run workflow**, escolha a `main`. O workflow lê
     `papo.version` e cria a tag.

## Lançar um beta

1. Confira que o `Unreleased.md` descreve o que vai no beta.
2. Em `gradle.properties`, ajuste `papo.version` (por exemplo `0.2.0-beta.1`).
3. Dispare a release como acima (tag `v0.2.0-beta.1` ou disparo manual). Um beta pode sair
   de qualquer branch; a versão final só sai da `main`.

O beta é publicado como pré-release e nunca vira a versão mais recente do repositório.
Para o beta seguinte, basta subir o número (`0.2.0-beta.2`) com o `Unreleased.md` atualizado.

## O que o workflow faz

1. Confere a tag contra `papo.version` e as notas (`tools/release.py check`).
2. Roda o CI inteiro: testes de unidade, lint, build e os testes instrumentados no emulador.
3. Compila os APKs de release assinados, um por arquitetura e um universal.
4. Publica a release com as notas, um link de comparação com a versão anterior, os APKs
   e o `SHA256SUMS`.

Uma release já publicada nunca é substituída: para corrigir, lance uma nova versão.

## Assinatura

Os APKs de release são assinados com a keystore guardada nos secrets do repositório
(`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e
`RELEASE_KEY_PASSWORD`). `tools/dev/setup-release-signing.sh` gera a keystore e, com
`--secrets`, grava os quatro. Guarde uma cópia da keystore: sem ela não dá para publicar
uma atualização que o Android aceite por cima da versão instalada.

## Conferir antes de enviar

```
tools/release.py check v0.2.0 --require-main       # a tag pode virar release?
tools/release.py notes v0.2.0 --repo dono/repo     # como fica o corpo da release
python3 -m unittest discover -s tools/tests        # testes do próprio script
```
