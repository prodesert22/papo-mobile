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
- O player do YouTube dentro do app é a única exceção: enquanto ele está aberto,
  o componente web do celular guarda cookies e cache sem cifrar na pasta do app.
  Eles são apagados quando o player fecha e quando o app trava; algumas bases
  vazias com o nome do YouTube ficam até a próxima vez que o app abrir.

## Mensagens

- Canais de texto agrupados por categoria e mensagens diretas, em tempo real,
  com marcação de não lidas e a contagem das mensagens novas que chegaram
  enquanto você lia mais acima.
- As mensagens aparecem assim que você as envia e saem uma de cada vez; a que
  falha fica com o motivo e um botão para reenviar, e nunca é reenviada sozinha.
- Um rascunho para cada conversa, com a mensagem que você estava respondendo.
- Anexe fotos e vídeos da galeria, ou qualquer arquivo, com prévia antes de
  enviar; GIFs, figurinhas e imagens do teclado ou coladas também entram como
  anexo. Até 10 arquivos, 100 MB cada e 105 MB somados; os arquivos sobem em
  fluxo, com progresso e botão de cancelar, e uma foto JPEG que leva a sua
  localização avisa antes de enviar.
- Respostas, edição e exclusão, reações, mensagens fixadas e menções. As
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
- Formatação Markdown com listas e divisórias, links (inclusive endereços que
  começam com `www.`), indicador de digitação e presença. Segure uma mensagem
  para ver para onde vai cada link dela, e um link cujo texto esconde o endereço
  pergunta antes de abrir. Mensagens da mesma pessoa em até 10 minutos aparecem
  sob um só nome.
- Prévias de links com tudo o que o servidor manda: cor, autor, título, campos e
  rodapé. Links de GIF do Tenor, GIPHY e KLIPY se mexem na mensagem.
- Um visualizador para as imagens, GIFs e vídeos de uma mensagem, e as imagens
  das prévias de links dela: deslize entre eles, dê zoom de até 5x e salve ou
  compartilhe imagens e GIFs. GIFs anexados se mexem na lista.
- Vídeos, anexados ou de links, tocam na própria mensagem com um toque no botão
  de play, sem controles, e um toque no vídeo tocando abre a tela cheia do mesmo
  ponto; a tela cheia tem controles próprios do app, com saltos de 10 segundos e
  busca. Tocam enquanto baixam e os cartões mostram uma capa. Links do YouTube
  tocam dentro do app do mesmo jeito, e nada é pedido ao YouTube antes do toque
  no play.
- Áudios anexados, inclusive gravações de voz feitas no web, tocam na própria
  mensagem com busca e velocidade de 1x, 1,5x e 2x, e uma barra no topo mantém o
  áudio à mão quando a mensagem sai da tela.
- Anexos removidos pela moderação do servidor mostram um aviso; os sensíveis
  ficam escondidos até você tocar.
- Emojis: seletor com busca com todos os emojis Unicode e os do servidor,
  sugestões ao digitar `:nome`, emojis do servidor dentro das mensagens e emojis
  maiores nas mensagens que só têm emojis. Os emojis do servidor em GIF e WebP se
  mexem nas mensagens, nas reações e no seletor; ficam no primeiro quadro nas
  sugestões, e quando o celular está com as animações removidas. No campo de
  mensagem os emojis do servidor também aparecem como imagem, e o texto continua
  `:nome:`: é o que se envia, se guarda e se copia, e cada emoji conta como um
  caractere ao mover o cursor, apagar ou selecionar.

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
- Nas chamadas: sem o som da tela transmitida, sem volume por pessoa e sem
  escolha de fone Bluetooth.
- Emojis em APNG mostram só o primeiro quadro, e os GIFs ficam parados quando o
  celular está com as animações removidas.
- Vídeos e áudios pausam quando você sai do app e não continuam em segundo plano.
  Um vídeo tocando na mensagem para, e perde o ponto, quando sai da tela.
- O YouTube precisa do WebView do sistema Android 133 ou mais novo; com um mais
  antigo, os vídeos do YouTube abrem fora do app. Aberto em tela cheia pelo
  cartão, o vídeo do YouTube começa do início.
- A prévia de link mostra uma imagem só e não mostra o ícone do rodapé, porque o
  servidor não serve mais que isso: uma prévia personalizada com miniatura e
  imagem mostra só a miniatura.
- Salvar e compartilhar no visualizador valem para imagens e GIFs, anexados ou
  de prévias de links; vídeos e áudios se salvam com **Baixar**. Vídeos de links
  não entram no visualizador: tocam no cartão ou em tela cheia.
- Sem câmera no menu de anexos. O aviso de localização vale só para fotos JPEG, e
  o app não tira a localização. O que vem do teclado tem limite de 20 MB por item.
- Mensagens que esperam para sair e arquivos anexados a um rascunho se perdem
  quando o app trava ou fecha.
- A interface está em português.
