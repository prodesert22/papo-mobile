- Messages from the same person sent within 10 minutes of each other now show
  under one name and picture, as in the web client; before, the window was 5
  minutes. A reply always starts a new group, and messages from different
  webhooks are never grouped.
- Lists in messages now show as lists, as in the web client: items starting with
  `-`, `*`, `+` or a number, nested up to three levels, with long items wrapping
  under their text. `- [ ]` and `- [x]` show as task boxes, and `---`, `***` or
  `___` on a line of their own draw a dividing line.
- **Copiar texto** now copies mentions as `@name` instead of the internal code
  the server stores, and the new **Selecionar texto** in a message's actions
  opens its text in a sheet where you can select and copy any part of it.
- Sending no longer locks the message box. A message shows in the conversation
  at once, marked **Enviando…** (with the upload progress when it has
  attachments), and the ones you send after it wait their turn marked **Na
  fila de envio**. Sending goes on if you leave the conversation.
- A message that could not be sent stays in the conversation with the reason
  and a **Reenviar** action; the app never sends it again by itself. When the
  server did not confirm a message that may have arrived, the app says so and
  asks before sending it again, since it could show twice. Tap a message
  waiting to be sent to discard it, or to cancel an upload in progress.
- Messages waiting to be sent are kept only while the app is open and
  unlocked.
- Typing `@` suggests people by any part of their username or name, without
  caring about accents or case (`joao` finds "João Silva"), with whoever spoke
  recently in the conversation and whoever is online first; the list scrolls
  when there are many. A full username typed after `@` without picking it from
  the list now also mentions that person when it matches exactly one account,
  and a mention followed by a period, as in `@ana.`, is no longer sent as plain
  text.
- Each conversation keeps its own draft: the text and the message you were
  replying to come back when you return to it, even after the app closes. Drafts
  are kept encrypted on the phone; files attached to a draft are not kept.
  Editing a message no longer throws away what you were typing: it comes back
  when you save or cancel the edit.
- Voice channels have a chat. Open it with the chat button at the end of a voice
  channel in the list or with **Abrir chat** during a call; **Entrar na
  chamada** in its top bar joins the call.
