# Instalar

[Read in English](install.md)

Você precisa de Android 11 ou mais novo e de um servidor Papo para se conectar.

1. Abra a [versão mais recente](https://github.com/prodesert22/papo-mobile/releases/latest)
   e baixe o APK do seu celular:

   | Arquivo | Para |
   | --- | --- |
   | `papo-mobile-<versão>-arm64-v8a.apk` | Quase todo celular vendido nos últimos anos |
   | `papo-mobile-<versão>-armeabi-v7a.apk` | Celulares antigos de 32 bits |
   | `papo-mobile-<versão>-x86_64.apk` | Emuladores e alguns tablets e Chromebooks |
   | `papo-mobile-<versão>-universal.apk` | Qualquer um deles; é maior |

2. Abra o arquivo baixado. O Android pede para permitir a instalação de apps a
   partir do app com que você o abriu; permita e instale.
3. Abra o Papo, digite o endereço do seu servidor e entre ou crie uma conta.

Para conferir um download, coloque o `SHA256SUMS` da mesma versão ao lado do APK
e rode `sha256sum --check --ignore-missing SHA256SUMS`.

Um endereço que começa com `http://` mostra um aviso antes de conectar, porque
senhas e mensagens trafegam sem criptografia. Use só em redes em que você confia.

Para bloquear o app, toque na engrenagem na base do trilho de servidores e ligue
**Bloquear com digital**.
