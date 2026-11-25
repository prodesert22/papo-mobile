# Features

[Leia em português](features.pt-BR.md)

What the current version of Papo Mobile does, and what it does not do yet. The
release notes in [releases/](../releases/) describe each version's changes.

## Servers and accounts

- Several servers, each with its own address, and several accounts on the same
  server. Every account keeps its own session, history and media.
- A nickname and a color for each account, to tell them apart.
- Closed servers, which ask for the server password before you sign in.
- Sessions that renew themselves, and a list of connected devices to sign out.
- A quick switcher that finds any conversation of any of your accounts, with
  mentions and unread conversations first.

## Privacy on the device

- Lock the app behind your fingerprint or the phone's screen lock. The key that
  opens the stored data stays in the phone's secure hardware.
- Accounts, sessions, messages, drafts and cached media are stored encrypted and
  are left out of device backups.
- Optionally block screenshots and hide conversations in the recent apps list.

## Messages

- Text channels grouped by category and direct messages, in real time, with
  unread markers and a count of the new messages that arrived while you read
  further up.
- Messages show as soon as you send them and go out one at a time; one that
  fails stays with the reason and a button to send it again, and is never sent
  again on its own.
- A draft for each conversation, with the message you were replying to.
- Attachments, replies, editing and deleting, reactions, pinned messages and
  mentions. Mentions find people by any part of their name, without caring about
  accents, and messages that mention you or reply to you are highlighted.
- Tap a reply to go to the original message, swipe a message to reply to it and
  double tap it to react with 👍. Copy a message or select part of its text.
- Hold a reaction, or choose "Ver reações" in a message's actions, to see who
  reacted, with a list for each emoji. The list is read when it opens; open it
  again to refresh it. Reading it only needs permission to read the channel.
- The app follows the server's permissions: it hides channels you cannot read and
  offers only the actions you may take; `@everyone` is offered and highlighted for
  those allowed to use it.
- Message search on the server, by text and by conversation, author, mention,
  links, attachments and date. A result opens in its conversation, however old
  it is.
- Markdown formatting with lists and dividers, link previews, image thumbnails
  with a full-screen viewer, typing indicators and presence. Messages from the
  same person within 10 minutes show under one name.
- Emoji: a searchable picker with every Unicode emoji and the server's own,
  suggestions as you type `:name`, the server's emoji shown inside messages,
  and larger emoji in messages that have nothing else. The server's emoji in
  GIF and WebP move in messages and reactions; they stay on their first frame in
  the picker and the suggestions, and when the phone has animations removed.

## Voice, camera and screen sharing

- Join a voice channel, mute, switch cameras and choose between the speaker and
  the earpiece.
- Share your camera or your screen, and watch the ones other people share.
- Voice channels have their own chat, which you can open from the channel list
  or during the call.
- A call continues when you leave the app, with a notification to hang up.

## Current limitations

- No push notifications: messages arrive only while the app is open.
- No notification inbox, profile editing, user preferences, blocking or server
  administration.
- In calls: no sound from a shared screen, no per-person volume and no Bluetooth
  headset selection.
- Video attachments play only after being downloaded, and attached GIFs, link
  preview images and APNG emoji show only their first frame.
- Messages waiting to be sent, and files attached to a draft, are lost when the
  app locks or closes.
- The interface is in Portuguese.
