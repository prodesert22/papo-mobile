# Desenvolvimento

[Read in English](development.md)

## Requisitos

- Um JDK, versão 17 ou mais nova, para iniciar o Gradle. O build em si usa o
  JDK 21 e o baixa quando ele não está instalado.
- O Android SDK. Aponte `ANDROID_HOME` para ele ou crie `local.properties` com
  `sdk.dir=/caminho/para/Android/Sdk`. O Gradle baixa os pacotes do SDK de que
  precisa, desde que as licenças do SDK estejam aceitas.

## Compilar e instalar

```sh
git clone https://github.com/prodesert22/papo-mobile.git
cd papo-mobile
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Verificações

```sh
./gradlew :shared:testAndroidHostTest          # testes de unidade
./gradlew :androidApp:lintDebug                # lint do Android
python3 -m unittest discover -s tools/tests    # testes do tools/release.py e do backend falso
tools/dev/run-device-tests.sh <serial>         # testes instrumentados em um aparelho
```

`adb devices` lista os seriais. Use o script para os testes instrumentados no
lugar de `connectedAndroidTest`: essa tarefa roda em todos os aparelhos
conectados e desinstala o app ao terminar.

## Servidores para testar

Num emulador, um servidor rodando no seu computador fica acessível em
`http://10.0.2.2:<porta>`. Sem servidor, `python3 tools/dev/fake_papo.py 8081`
sobe um falso que cobre login, chat de texto e busca de mensagens, com as
usuárias `ana` (`Senha@123`) e `bia` (`Senha@456`). Ele não substitui um
servidor de verdade e não tem chamadas de voz. Com `FAKE_PERMISSIONS=demo` ele também
tem cargos, permissões de canal e uma dona (`dona`, `Senha@000`); a docstring do
`fake_papo.py` descreve o cenário e como carregar o seu de um arquivo JSON.
`FAKE_VIDEO_DIR` aponta uma pasta de vídeos para semear (servidos com `Range`),
`FAKE_VIDEO_KBPS` limita a velocidade e `FAKE_VIDEO_401_ONCE=N` responde 401 ao
N-ésimo pedido de vídeo.
Uma mensagem com um link `https://videos.example.com/...` ganha um embed de vídeo cerca
de um segundo depois (`GET /embeds/{id}` e o relay `/embeds/{id}/video`, que serve um vídeo
semeado com `Range`), e `FAKE_RELAY_502=1` faz o relay responder 502. `FAKE_AUDIO_DIR`
semeia os áudios de uma pasta no canal `audio`, `FAKE_UPLOAD_KBPS` limita a velocidade dos
envios e `FAKE_YOUTUBE_ID` define o id do embed do YouTube semeado (um inventado por padrão,
que o YouTube responde como indisponível). Os canais `embeds` e `galeria` têm prévias de
links, GIFs, estados de moderação e mensagens com várias imagens e vídeos.

## Builds de release

O build de release é minificado e precisa de uma chave de assinatura; veja
[releases/README.md](../releases/README.md).

## Organização do projeto

`shared/` é o módulo Kotlin Multiplatform com quase todo o código, incluindo a
interface em Compose; `androidApp/` é o aplicativo Android. Só o alvo Android é
compilado: os alvos iOS estão declarados e ainda não têm implementação. O
contrato do serviço está em [openapi.yml](../openapi.yml).

O [AGENTS.md](../AGENTS.md) (em inglês) descreve a organização em detalhe e as
regras para alterar o código.
