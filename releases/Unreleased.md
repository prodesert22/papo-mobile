- Search messages on the server. Tap the magnifier in the account header to
  search the whole server, or the one in a conversation to search only there.
  Type what you are looking for and tap **Buscar**; filter by conversation,
  author, mentioned person, links, attachments and a range of dates, and choose
  between newest and oldest first. Results come 100 at a time, with **Carregar
  mais** for the next page.
- The server-wide search does not reach direct messages. To search one, pick it
  in **Conversa** or start the search from inside it.
- Tap a search result to open its conversation at that message, highlighted,
  with the history around it, however old it is. While you read an old stretch
  of a conversation, scrolling loads more in both directions and **Ir para as
  recentes** takes you back to the latest messages.
- **Filtros recentes** offers the filters of your last five searches again. They
  are kept only in memory and are gone when the app locks or closes.
- **Mensagens fixadas** has a field that filters the list by text or author as
  you type.
- Searching for a person or a conversation by name ignores accents: `joao` finds
  "João".
- Fixed the names in **Com quem você quer falar?**, which were unreadable in the
  dark theme.
- Fixed the **Fechar** button of the notices shown over a conversation, which was
  hard to read.
- Added an emoji panel to the message box. The smiley button beside **Enviar** opens it in place of the keyboard and at the keyboard's height, and the keyboard button in the same spot brings the keyboard back without the message box moving. Drag the panel up to open it over the whole conversation, or down to close it; **Back** also closes it. It lists every Unicode emoji by category together with the server's own emoji, and searches by name without caring about accents or case, in Portuguese and English (`joia`, `coracao`, `thumbsup`), with the results kept above the keyboard while you type. Picking an emoji inserts it at the cursor and leaves the panel open for more.
- **+** on a message's reaction row opens the same emoji list in a sheet that starts at half the screen and opens fully when dragged up or when you search.
- The emoji you used last come first, in the panel and in the quick reactions, and are kept encrypted on the phone for each account.
- Type `:` and the start of a name in the message box to get emoji suggestions, with the server's own emoji first. Typing the closing colon of a complete name, such as `:+1:`, turns it into the emoji; a name that is also one of the server's emoji stays as text so it shows as that emoji's image.
- The server's custom emoji written as `:name:` now show as images inside messages, including in bold and quotes but not in code. Messages made only of emoji, Unicode or from the server, show them larger.
- The panel and the quick reactions list all of a server's custom emoji. Before, only the first 200 loaded and the quick reactions offered 24. The list loads several times faster than the web client's pace: the next page is requested while the images of the previous one are stored, all of it in low-priority background work so scrolling stays smooth, and pages are spaced so they stay well within the server's rate limit (a page refused for the rate limit is asked again after a pause). The list is kept encrypted on the phone so the panel shows it at once the next time; while it is read for the first time the panel shows animated placeholders instead of a list that keeps changing under your finger.
- The message box now reaches the bottom of the screen and sits right on top of the keyboard, without the empty band that used to appear below it.
