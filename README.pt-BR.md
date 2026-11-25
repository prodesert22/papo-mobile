# Papo Mobile

[![CI](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/prodesert22/papo-mobile/actions/workflows/ci.yml)
[![Versão mais recente](https://img.shields.io/github/v/release/prodesert22/papo-mobile?label=release)](https://github.com/prodesert22/papo-mobile/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/prodesert22/papo-mobile/total)](https://github.com/prodesert22/papo-mobile/releases)
[![Licença: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](#instalar)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](#compilar)

[Read in English](README.md)

Cliente Android do [Papo](https://github.com/prodesert22/papo-backend), um
serviço de chat auto-hospedado com texto, voz e vídeo. Entre em vários servidores
e contas ao mesmo tempo, converse em canais e mensagens diretas e participe de
chamadas de voz com câmera e transmissão de tela. Tudo o que o app guarda no
celular fica cifrado e pode ser bloqueado pela sua digital.

O Papo também tem um [cliente web](https://github.com/prodesert22/papo-frontend)
e um [cliente de terminal](https://github.com/prodesert22/papo-tui).

## Recursos

- Vários servidores e contas ao mesmo tempo, cada um isolado dos outros.
- Canais e mensagens diretas em tempo real, com busca, respostas, reações,
  menções, Markdown e emojis.
- Chamadas de voz com câmera e transmissão de tela.
- Tudo o que fica no celular é cifrado e pode ser bloqueado pela sua digital.

A lista completa e o que ainda falta estão em [docs/features.pt-BR.md](docs/features.pt-BR.md).

## Instalar

Você precisa de Android 11 ou mais novo. Baixe o APK do seu celular na
[versão mais recente](https://github.com/prodesert22/papo-mobile/releases/latest)
(o `arm64-v8a` serve para quase todo celular recente), abra o arquivo e permita
a instalação. O [docs/install.pt-BR.md](docs/install.pt-BR.md) tem os detalhes,
inclusive como conferir o download.

## Compilar

```sh
./gradlew :androidApp:assembleDebug
```

Requisitos, testes e ferramentas de desenvolvimento estão em
[docs/development.pt-BR.md](docs/development.pt-BR.md).

## Versões

As versões seguem `major.minor.patch`, com betas como `1.0.1-beta.1`. Cada versão
final tem suas notas em [releases/](releases/), e o
[releases/README.md](releases/README.md) explica como uma versão é publicada.

## Licença

O Papo Mobile é licenciado sob a
[GNU Affero General Public License v3.0](LICENSE).

A fonte Bricolage Grotesque incluída é licenciada sob a
[SIL Open Font License](licenses/BricolageGrotesque-OFL.txt). Os nomes e as
palavras-chave dos emojis Unicode vêm do [emojibase-data](licenses/Emojibase-MIT.txt)
(MIT), feito sobre dados do Unicode CLDR sob a [Licença Unicode v3](licenses/Unicode-3.0.txt).
As bibliotecas de que o app depende mantêm as próprias licenças.
