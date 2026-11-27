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
- As informações do servidor nas configurações da conta: nome, ícone, se é
  público ou fechado, dono, e quantos membros, canais e cargos tem.
- Troca de senha pelas configurações da conta, ou redefinição por um link na
  tela de entrada. O endereço do link costuma ser o do site, não o da API do
  servidor, então o app mostra os dois e pede confirmação antes de enviar quando
  eles diferem.
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
- A conversa é um painel por cima da lista de canais, como no Discord. Deslize-a
  para a direita para ver os canais, e a conversa se esconde por completo;
  deslize a lista de canais para a esquerda para voltar a ela. O painel
  acompanha o dedo e, ao soltar, assenta aberto ou fechado; o botão e o gesto de
  voltar também o fecham. Cada conta guarda a sua última conversa, e depois que o
  app trava nenhuma conversa fica esperando fora da tela. Ao abrir o app,
  destravá-lo ou voltar a ele com a lista de canais à vista, uma conversa da
  conta selecionada aparece no painel: a que estava aberta nela, ou então o
  primeiro canal de texto da lista, assim que os canais chegam do servidor.
  Quem volta numa conversa ou noutra tela fica nela; sem canal de texto que você
  possa ler, sem conexão ou com a sessão encerrada, a lista de canais fica. As mensagens que chegam numa
  conversa esperando fora da tela continuam não lidas.
  Deslizar uma mensagem para a esquerda continua respondendo, e as áreas que
  rolam para o lado, como as prévias de anexos, ficam com o deslize primeiro.
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

## Pessoas

- Toque no avatar ou no nome de alguém numa conversa, ou no título de uma
  conversa direta, para abrir o perfil: banner, avatar, presença, recado, cargos,
  descrição e a data em que a pessoa entrou. Do perfil dá para abrir uma conversa
  direta com ela ou copiar o `@usuário`.
- Lista de membros do servidor: o botão no topo de um canal abre um painel que
  entra pela borda direita, com todos agrupados por presença e busca por nome.
  Toque em alguém para abrir o perfil.
- Edição do próprio perfil: apelido, recado, descrição e frase de digitação, com
  avatar e banner escolhidos da galeria por um lápis sobre cada imagem, no formato
  de um cartão de perfil. A imagem escolhida abre um editor em que você move, amplia
  e gira a foto sob uma moldura fixa (quadrada no avatar, 3:1 no banner); depois o
  app a corta e reduz no celular, tira a localização que ela carrega e mostra o resultado de cada
  mudança. Também dá para remover o banner e escolher Disponível, Ausente ou
  Ocupado pelo editor de perfil ou pelo seu próprio perfil, cada um com um disco
  na cor do status.
- O topo da lista de canais mostra seu avatar, seu nome e a cor do seu status;
  toque nele para editar o perfil.
- Bloqueio de pessoas pelo perfil, com lista e desbloqueio nas configurações da
  conta. Bloquear remove a conversa direta e impede mensagens diretas nos dois
  sentidos; as mensagens da pessoa nos canais continuam visíveis, e desbloquear
  não traz a conversa de volta. A lista de nova conversa esconde quem você
  bloqueou e avisa quando foi a outra pessoa que bloqueou você.
- Mensagens enviadas por webhook levam o selo **APP** ao lado do nome e mostram o
  avatar do webhook.

## Voz, câmera e transmissão de tela

- Entre num canal de voz, silencie o microfone, troque de câmera e escolha entre
  o alto-falante e o fone do aparelho.
- Transmita sua câmera ou sua tela e veja as que os outros transmitem.
- Os canais de voz têm chat próprio, que abre pela lista de canais ou durante a
  chamada.
- A chamada continua quando você sai do app, com uma notificação para desligar.

## Notificações

- Caixa de notificações para cada conta, aberta pelo sino no topo da lista de
  canais: menções, respostas às suas mensagens, `@everyone` e mensagens diretas,
  com o motivo de cada uma. Abrir uma leva à mensagem e a marca como lida; dá
  também para marcar como lidas todas as carregadas.
- Número de menções em vermelho ao lado de cada canal e conversa direta, em cada
  conta na barra de servidores e no sino. Um canal com mensagens novas mostra o
  nome em branco e um círculo à direita.
- Configurações de notificação para cada servidor e, partindo de "Usar padrão do
  servidor", para cada canal; segure o canal na lista ou use o menu da conversa.
  Você escolhe quais mensagens notificam (todas, só menções ou nada), silencia
  de 15 minutos a 24 horas ou até reativar, e suprime `@everyone` ou as menções
  diretas. As conversas diretas também podem ser silenciadas.
- Silenciar tira o círculo de mensagem nova e esmaece o canal, mas as menções
  continuam contando. Suprimir `@everyone` ou as menções as tira da contagem.
- O nível de notificação do canal fica salvo no servidor e é o mesmo nos outros
  clientes do Papo. Desligue "Sincronizar com o servidor" num canal ou no
  servidor inteiro para manter um nível diferente neste celular.
- Notificações do sistema na barra do Android, uma por conversa e agrupadas por
  conta, seguindo essas configurações. Tocar numa delas abre a mensagem, depois
  do desbloqueio quando o bloqueio do app está ligado. Cada conta tem os próprios
  canais de notificação do Android (menções e respostas, mensagens diretas,
  mensagens), onde dá para mudar o som e a vibração.

## Limitações atuais

- As notificações do sistema só chegam com o app aberto: ainda não há push,
  então nada chega com o app fechado ou em segundo plano.
- Silenciar, o nível geral do servidor e suprimir `@everyone` ou as menções
  ficam neste celular: o servidor não os guarda, então eles não seguem você no
  web nem em outro celular. Conversas diretas não têm nível no servidor, só
  silenciar.
- A contagem de menções lê até 500 notificações por conta e mostra "99+" além
  disso. Notificações lidas em outro aparelho se atualizam quando o app
  reconecta ou volta para a frente. Um canal em "nada" no servidor não recebe
  notificações dele, então fica sem número.
- Sem administração do servidor.
- O avatar não pode ser removido depois de definido, só trocado, e avatar ou banner
  animado não é recortado: vai como está quando cabe nos limites de tamanho e é
  recusado quando não cabe; não há status
  "invisível".
- Trocar a senha encerra todas as sessões da conta, inclusive esta, e o servidor
  não confere a senha atual, então o app só pede confirmação. A checagem da senha
  nova no app é básica (8 caracteres, uma maiúscula e um símbolo); o servidor pode
  exigir mais.
- A lista de membros mostra todos do servidor, não só quem pode ler o canal em
  que você está.
- O avatar de um webhook só aparece quando está guardado no servidor. A mensagem
  cuja imagem de autor é um endereço em outro site mostra as iniciais, para que
  ler uma conversa nunca contate um terceiro.
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
