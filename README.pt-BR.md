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

**Servidores e contas**

- Vários servidores, cada um com o próprio endereço, e várias contas no mesmo
  servidor. Cada conta tem sessão, histórico e mídia próprios.
- Apelido e cor para cada conta, para diferenciá-las.
- Servidores fechados, que pedem a senha do servidor antes do login.
- Sessões que se renovam sozinhas e uma lista de dispositivos conectados para
  desconectar.

**Privacidade no aparelho**

- Bloqueie o app com a digital ou com o bloqueio de tela do celular. A chave que
  abre os dados guardados fica no hardware seguro do aparelho.
- Contas, sessões, mensagens e mídia em cache ficam cifradas e fora dos backups
  do aparelho.
- Opcionalmente, bloqueie capturas de tela e esconda as conversas na lista de
  apps recentes.

**Mensagens**

- Canais de texto agrupados por categoria e mensagens diretas, em tempo real,
  com marcação de não lidas.
- Anexos, respostas, edição e exclusão, reações, mensagens fixadas e menções.
- Formatação Markdown, prévias de links, miniaturas de imagem com visualizador
  em tela cheia, indicador de digitação e presença.

**Voz, câmera e transmissão de tela**

- Entre num canal de voz, silencie o microfone, troque de câmera e escolha entre
  o alto-falante e o fone do aparelho.
- Transmita sua câmera ou sua tela e veja as que os outros transmitem.
- A chamada continua quando você sai do app, com uma notificação para desligar.

A lista completa está nas [notas da versão 0.1.0](releases/v0.1.0.md) (em inglês).

## Limitações atuais

- Sem notificações push: as mensagens só chegam com o app aberto.
- Sem busca de mensagens, caixa de notificações, edição de perfil, preferências
  do usuário, bloqueio de usuários e administração do servidor.
- Nas chamadas: sem o som da tela transmitida, sem volume por pessoa e sem
  escolha de fone Bluetooth.
- Vídeos anexados só tocam depois de baixados, GIFs não são animados e os emojis
  próprios do servidor não aparecem dentro do texto das mensagens.
- A interface está em português.

## Instalar

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

## Compilar

Você precisa de:

- Um JDK, versão 17 ou mais nova, para iniciar o Gradle. O build em si usa o
  JDK 21 e o baixa quando ele não está instalado.
- O Android SDK. Aponte `ANDROID_HOME` para ele ou crie `local.properties` com
  `sdk.dir=/caminho/para/Android/Sdk`. O Gradle baixa os pacotes do SDK de que
  precisa, desde que as licenças do SDK estejam aceitas.

Compile o APK de debug e instale num aparelho ou emulador conectado:

```sh
git clone https://github.com/prodesert22/papo-mobile.git
cd papo-mobile
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Rode as verificações:

```sh
./gradlew :shared:testAndroidHostTest          # testes de unidade
./gradlew :androidApp:lintDebug                # lint do Android
python3 -m unittest discover -s tools/tests    # testes do tools/release.py
tools/dev/run-device-tests.sh <serial>         # testes instrumentados em um aparelho
```

`adb devices` lista os seriais. Use o script para os testes instrumentados no
lugar de `connectedAndroidTest`: essa tarefa roda em todos os aparelhos
conectados e desinstala o app ao terminar.

Num emulador, um servidor rodando no seu computador fica acessível em
`http://10.0.2.2:<porta>`. Sem servidor, `python3 tools/dev/fake_papo.py 8081`
sobe um falso que cobre login e chat de texto, com as usuárias `ana`
(`Senha@123`) e `bia` (`Senha@456`). Ele não substitui um servidor de verdade e
não tem chamadas de voz.

O build de release é minificado e precisa de uma chave de assinatura; veja
[releases/README.md](releases/README.md).

## Organização do projeto

`shared/` é o módulo Kotlin Multiplatform com quase todo o código, incluindo a
interface em Compose; `androidApp/` é o aplicativo Android. Só o alvo Android é
compilado: os alvos iOS estão declarados e ainda não têm implementação. O
contrato do serviço está em [openapi.yml](openapi.yml).

O [AGENTS.md](AGENTS.md) (em inglês) descreve a organização em detalhe e as
regras para alterar o código.

## Versões

As versões seguem `major.minor.patch`, com betas como `1.0.1-beta.1`. Cada versão
final tem suas notas em [releases/](releases/), e o
[releases/README.md](releases/README.md) explica como uma versão é publicada.

## Licença

O Papo Mobile é licenciado sob a
[GNU Affero General Public License v3.0](LICENSE).

A fonte Bricolage Grotesque incluída é licenciada sob a
[SIL Open Font License](licenses/BricolageGrotesque-OFL.txt). As bibliotecas de
que o app depende mantêm as próprias licenças.
