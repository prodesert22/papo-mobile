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
