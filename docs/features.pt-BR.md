# Recursos

[Read in English](features.md)

O que a versão atual do Papo Mobile faz e o que ainda não faz. As notas de cada
versão, em [releases/](../releases/) (em inglês), descrevem o que mudou nela.

## Servidores e contas

- Vários servidores, cada um com o próprio endereço, e várias contas no mesmo
  servidor. Cada conta tem a própria sessão, histórico e mídia.
- Apelido e cor para cada conta, para diferenciá-las.
- Servidores fechados, que pedem a senha do servidor antes do login.
- Sessões que se renovam sozinhas e uma lista de dispositivos conectados para
  encerrar sessões.
- Troca rápida que acha qualquer conversa de qualquer uma das suas contas, com
  menções e conversas não lidas primeiro.

## Privacidade no aparelho

- Bloqueie o app com a digital ou com o bloqueio de tela do celular. A chave que
  abre os dados guardados fica no hardware seguro do celular.
- Contas, sessões, mensagens, rascunhos e mídia em cache ficam cifrados e fora
  dos backups do aparelho.
- Opcionalmente, bloqueie capturas de tela e esconda as conversas na lista de
  apps recentes.

## Mensagens

- Canais de texto agrupados por categoria e mensagens diretas, em tempo real,
  com marcação de não lidas e a contagem das mensagens novas que chegaram
  enquanto você lia mais acima.
- As mensagens aparecem assim que você as envia e saem uma de cada vez; a que
  falha fica com o motivo e um botão para reenviar, e nunca é reenviada sozinha.
- Um rascunho para cada conversa, com a mensagem que você estava respondendo.
- Anexos, respostas, edição e exclusão, reações, mensagens fixadas e menções. As
  menções acham as pessoas por qualquer parte do nome, sem se importar com
  acentos, e as mensagens que mencionam você ou respondem a você ficam destacadas.
- Toque numa resposta para ir à mensagem original, deslize uma mensagem para
  respondê-la e toque duas vezes para reagir com 👍. Copie uma mensagem ou
  selecione parte do texto.
- Segure uma reação, ou escolha "Ver reações" nas ações de uma mensagem, para ver
  quem reagiu, com uma lista para cada emoji. A lista é lida quando abre; abra de
  novo para atualizá-la. Para vê-la basta poder ler o canal.
- O app segue as permissões do servidor: esconde os canais que você não pode ler e
  só oferece as ações que você pode fazer; o `@everyone` aparece e é destacado para
  quem tem permissão.
- Busca de mensagens no servidor, por texto e por conversa, autor, menção, link,
  anexos e data. O resultado abre na conversa, por mais antigo que seja.
- Formatação Markdown com listas e divisórias, prévias de links, miniaturas de
  imagem com visualizador em tela cheia, indicador de digitação e presença.
  Mensagens da mesma pessoa em até 10 minutos aparecem sob um só nome.
- Vídeos, anexados ou de links, tocam dentro do app enquanto baixam, em tela
  cheia, com busca; os cartões mostram uma capa.
- Emojis: seletor com busca com todos os emojis Unicode e os do servidor,
  sugestões ao digitar `:nome`, emojis do servidor dentro das mensagens e emojis
  maiores nas mensagens que só têm emojis. Os emojis do servidor em GIF e WebP se
  mexem nas mensagens, nas reações e no seletor; ficam no primeiro quadro nas
  sugestões, e quando o celular está com as animações removidas. No campo de
  mensagem os emojis do servidor também aparecem como imagem, e o texto continua
  `:nome:`: é o que se envia, se guarda e se copia, e cada emoji conta como um
  caractere ao mover o cursor, apagar ou selecionar.

## Pessoas

- Toque no avatar ou no nome de alguém numa conversa, ou no título de uma
  conversa direta, para abrir o perfil: banner, avatar, presença, recado, cargos,
  descrição e a data em que a pessoa entrou. Do perfil dá para abrir uma conversa
  direta com ela ou copiar o `@usuário`.
- Lista de membros do servidor: o botão no topo de um canal abre um painel que
  entra pela borda direita, com todos agrupados por presença e busca por nome.
  Toque em alguém para abrir o perfil.
- Mensagens enviadas por webhook levam o selo **APP** ao lado do nome e mostram o
  avatar do webhook.

## Voz, câmera e transmissão de tela

- Entre num canal de voz, silencie o microfone, troque de câmera e escolha entre
  o alto-falante e o fone do aparelho.
- Transmita sua câmera ou sua tela e veja as que os outros transmitem.
- Os canais de voz têm chat próprio, que abre pela lista de canais ou durante a
  chamada.
- A chamada continua quando você sai do app, com uma notificação para desligar.

## Limitações atuais

- Sem notificações push: as mensagens só chegam com o app aberto.
- Sem caixa de notificações, edição de perfil, preferências do usuário, bloqueio
  de usuários e administração do servidor.
- A lista de membros mostra todos do servidor, não só quem pode ler o canal em
  que você está.
- O avatar de um webhook só aparece quando está guardado no servidor. A mensagem
  cuja imagem de autor é um endereço em outro site mostra as iniciais, para que
  ler uma conversa nunca contate um terceiro.
- Nas chamadas: sem o som da tela transmitida, sem volume por pessoa e sem
  escolha de fone Bluetooth.
- GIFs anexados, imagens de prévia de links e emojis em APNG mostram só o primeiro
  quadro.
- Os vídeos pausam quando você sai do app e não continuam em segundo plano.
- Mensagens que esperam para sair e arquivos anexados a um rascunho se perdem
  quando o app trava ou fecha.
- A interface está em português.
