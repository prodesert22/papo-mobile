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
- The in-app YouTube player is the one exception: while it is open, the phone's
  web component keeps its cookies and cache unencrypted in the app's folder.
  They are deleted when the player closes and when the app locks; a few empty
  databases named after YouTube stay until the app is next opened.

## Messages

- Text channels grouped by category and direct messages, in real time, with
  unread markers and a count of the new messages that arrived while you read
  further up.
- Messages show as soon as you send them and go out one at a time; one that
  fails stays with the reason and a button to send it again, and is never sent
  again on its own.
- A draft for each conversation, with the message you were replying to.
- Attach photos and videos from the gallery, or any file, with a preview before
  sending; GIFs, stickers and images from the keyboard or pasted come in as
  attachments too. Up to 10 files, 100 MB each and 105 MB together; files upload
  as a stream, with progress and a cancel button, and a JPEG that carries its
  location warns you before sending.
- Replies, editing and deleting, reactions, pinned messages and
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
- Markdown formatting with lists and dividers, links (addresses that start with
  `www.` included), typing indicators and presence. Hold a message to see where
  each of its links goes, and a link whose text hides its address asks before
  opening. Messages from the same person within 10 minutes show under one name.
- Link previews with everything the server sends: color, author, title, fields
  and footer. GIF links from Tenor, GIPHY and KLIPY move in the message.
- A viewer for a message's images, GIFs and videos, and the images of its link
  previews: swipe between them, zoom up to 5x, and save or share images and
  GIFs. Attached GIFs move in the list.
- Videos, attached or from a link, play in the message with a tap on the play
  button, without controls, and a tap on the playing video opens it full screen
  from the same point; full screen has the app's own controls, with 10-second
  jumps and seeking. They play while they download and their cards show a
  cover. YouTube links play inside the app the same way, and nothing is
  requested from YouTube before you tap play.
- Audio attachments, voice recordings from the web included, play in the
  message with seeking and 1x, 1.5x and 2x speed, and a bar at the top keeps the
  playing audio at hand when its message is out of view.
- Attachments removed by the server's moderation show a notice; sensitive ones
  stay hidden until you tap them.
- Emoji: a searchable picker with every Unicode emoji and the server's own,
  suggestions as you type `:name`, the server's emoji shown inside messages,
  and larger emoji in messages that have nothing else. The server's emoji in
  GIF and WebP move in messages, reactions and the picker; they stay on their
  first frame in the suggestions, and when the phone has animations removed. In
  the message box the server's emoji show as images too, and the text stays
  `:name:`: that is what is sent, saved and copied, and each emoji counts as one
  character when you move the cursor, delete or select.

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
- APNG emoji show only their first frame, and GIFs stay still when the phone has
  animations removed.
- Videos and audio pause when you leave the app and do not keep playing in the
  background. A video playing in a message stops, and loses its place, when it
  scrolls off the screen.
- YouTube needs Android System WebView 133 or newer; with an older one, YouTube
  videos open outside the app. Opened full screen from the card, a YouTube video
  starts from the beginning.
- A link preview shows one image and no footer icon, because the server does not
  serve more: a custom preview with both a thumbnail and an image shows only the
  thumbnail.
- Save and share in the viewer cover images and GIFs, attached or from link
  previews; videos and audio are saved with **Baixar**. Videos from links are not
  in the viewer: they play in their card or full screen.
- No camera in the attachment menu. The location warning covers JPEG photos only,
  and the app does not remove the location. Items from the keyboard are limited
  to 20 MB each.
- Messages waiting to be sent, and files attached to a draft, are lost when the
  app locks or closes.
- The interface is in Portuguese.
