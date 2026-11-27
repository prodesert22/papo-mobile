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
- The server's details in the account settings: name, icon, whether it is
  public or closed, owner, and how many members, channels and roles it has.
- Change your password from the account settings, or reset it with a link from
  the sign-in screen. The link's address usually belongs to the website, not
  to the server's API, so the app shows both and asks before sending when they
  differ.
- A quick switcher that finds any conversation of any of your accounts, with
  mentions and unread conversations first.
- Settings in two tabs, as in Android's own settings: "Aplicativo" for this
  phone and "Servidor" for the selected account, each a list with a short
  summary of every item. The server bar shows your avatar on each account.

## Privacy on the device

- Lock the app behind your fingerprint or the phone's screen lock. The key that
  opens the stored data stays in the phone's secure hardware.
- Accounts, sessions, messages, drafts and cached media are stored encrypted and
  are left out of device backups.
- Lock right away or 1, 5 or 15 minutes or an hour after you leave the app.
- Allow or block screenshots and screen recording, which also hides the
  conversations in the recent apps list, with or without the app lock.
- Choose what the app's own notifications show: everything, nothing on the
  phone's lock screen, or only "Nova mensagem" everywhere. Push notifications
  shown by Android follow the server's preview instead.
- The in-app YouTube player is the one exception: while it is open, the phone's
  web component keeps its cookies and cache unencrypted in the app's folder.
  They are deleted when the player closes and when the app locks; a few empty
  databases named after YouTube stay until the app is next opened.

## Appearance

- A theme for the whole app: follow the system, light, dark or OLED, with a
  black background and black list areas to save energy on OLED screens. Six
  presets (Papo, Oceano, Floresta, Ametista, Âmbar and Rosa) and a live
  preview of the channel list and a conversation.
- A server can have its own theme, used while that account is selected; the
  lock screen and adding a server always use the app's theme.

## Messages

- Text channels grouped by category and direct messages, in real time, with
  unread markers and a count of the new messages that arrived while you read
  further up.
- The channel list has two tabs, "Canais" and "Conversas diretas", each with
  its mention count; without direct conversations, a button in the middle
  starts one. Hold a direct conversation to close it: it leaves the list and
  keeps its history, and a new message or starting it again brings it back.
- Tap the description under a channel's name to read it in full. People
  allowed to manage channels can rename a channel and change its
  description, from the conversation menu, from the description or by
  holding the channel in the list; nobody else sees the option.
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
- The conversation is a panel over the channel list, as in Discord. Swipe it to
  the right to see the channels, and the conversation hides completely; swipe
  the channel list to the left to return to it. The panel
  follows your finger and settles open or closed when you let go, and the back
  button and gesture close it too. Each account keeps its own last conversation,
  and after the app locks no conversation waits off screen. Opening the app,
  unlocking it or coming back to it with the channel list on screen opens a
  conversation of the selected account in the panel: the one you had open
  there, or else the first text channel of the list, as soon as the channels
  arrive from the server. Coming back to a conversation or another screen leaves
  you there; without a text channel you can read, without a connection or with
  the session ended, the channel list stays. Messages that
  arrive in a conversation waiting off screen stay unread.
  A message swiped to the left still replies, and areas that scroll sideways,
  such as the attachment previews, take the swipe first.
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

## People

- Tap a person's avatar or name in a conversation, or the title of a direct
  conversation, to open their profile: banner, avatar, presence, status, roles,
  description and the date they joined. From the profile you can open a direct
  conversation with them or copy their `@username`.
- A member list for the server: the button at the top of a channel opens a panel
  from the right edge with everyone grouped by presence and a search by name.
  Tap a person to open their profile.
- Edit your own profile: nickname, status message, description and typing
  phrase, with your avatar and banner chosen from the gallery through a pencil button
  on each image, laid out like a profile card. A picked picture opens an editor where you
  move, zoom and rotate it under a fixed frame (square for the avatar, 3:1 for the
  banner); the app then crops and shrinks it on the phone, drops the location it carries, and
  shows the result of each change. You can also remove your banner, and choose
  Available, Away or Busy from the profile editor or from your own profile, each
  with a disc in the color of the status.
- The top of the channel list shows your avatar, your name and the color of your
  status; tap it to edit your profile.
- Block people from their profile, and list or unblock them in the account
  settings. Blocking removes the direct conversation and stops direct messages
  in both directions; their messages in channels stay visible, and unblocking
  does not bring the conversation back. The new conversation list hides the
  people you blocked and says so when the other person blocked you.
- Messages sent by a webhook carry an **APP** badge next to the name and show
  the webhook's avatar.

## Voice, camera and screen sharing

- Join a voice channel, mute, switch cameras and choose between the speaker and
  the earpiece.
- Share your camera or your screen, and watch the ones other people share.
- Voice channels have their own chat, which you can open from the channel list
  or during the call.
- A call continues when you leave the app, with a notification to hang up.

## Notifications

- A notification inbox for each account, opened from the bell at the top of the
  channel list: mentions, replies to your messages, `@everyone` and direct
  messages, with the reason for each one. Opening one takes you to the message
  and marks it as read; you can also mark all the loaded ones as read.
- Mention counts in red next to each channel and direct conversation, on each
  account in the server bar and on the bell. A channel with new messages shows
  its name in white and a dot on the right.
- Notification settings for each server and, with "Use the server default" as
  the starting point, for each channel; hold a channel in the list or use the
  conversation menu. You choose which messages notify (all, mentions only or
  nothing), mute for 15 minutes up to 24 hours or until you turn it back on, and
  hide `@everyone` or direct mentions. Direct conversations can be muted too.
- Muting removes the new-message dot and dims the channel, but mentions still
  count. Hiding `@everyone` or mentions removes them from the count.
- A channel's notification level is saved on the server and shared with the
  other Papo clients. Turn off "Sincronizar com o servidor" for a channel or for
  the whole server to keep a different level on this phone.
- System notifications in the Android notification shade, one per conversation
  and grouped by account, following those settings. Tapping one opens the
  message, after the unlock when the app lock is on. Each account has its own
  Android notification channels (mentions and replies, direct messages,
  messages), where you can change the sound and vibration. Each message shows
  its author's picture, or the webhook's, when the app gets it within about two
  seconds; otherwise the notification shows the initial and does not wait. A
  picture on another site is never fetched.
- Push notifications with the app closed or in the background, when the server
  has push turned on. Android shows them by itself, so mute, the notification
  level and hiding `@everyone` or mentions do not apply to them, and the text
  follows the server's preview setting. Tapping one opens the app, after the
  unlock when the app lock is on, and then the conversation. They have no author
  picture: that would need the server to send it. The push does not
  say which server or account it is for, so the app opens the active account if
  it has that conversation and otherwise the first account that has it. Two
  accounts on the same server cannot both receive pushes on one phone: the
  server keeps one account per device, the last one that registered.

## Current limitations

- The notifications that follow your settings (mute, level, hiding `@everyone`
  or mentions) arrive only while the app is open. With the app closed or in the
  background only the server's push arrives, and only if the server has it
  turned on.
- Mute, the server-wide level and hiding `@everyone` or mentions stay on this
  phone: the server does not store them, so they do not follow you to the web or
  to another phone. Direct conversations have no level on the server, only mute.
- The mention count reads up to 500 notifications per account and shows "99+"
  beyond that. Reading on another device reaches the phone only when the app
  reconnects or comes back to the front, because the server does not announce
  it; a push notification already in the shade stays until you open or dismiss
  it. A channel set to "nothing" on the server
  gets no notifications from it, so it shows no count.
- No server administration beyond renaming a channel and changing its
  description.
- Closing a direct conversation reaches your other devices only when they
  load the list again, because the server does not announce it.
- A server's own theme stays on this phone.
- Your avatar cannot be removed once set, only replaced, and an animated avatar or
  banner is not cropped: it goes as it is when it fits the size limits and is
  refused otherwise; there is no "invisible"
  status.
- Changing your password ends every session of the account, this one included,
  and the server does not check the current password, so the app only asks you
  to confirm. The check on the new password in the app is basic (8 characters, a
  capital letter and a symbol); the server may ask for more.
- The member list shows everyone on the server, not only the people who can read
  the channel you are in.
- A webhook's avatar is shown only when it is stored on the server. A message
  whose author picture is an address on another site shows initials instead, so
  that reading a conversation never contacts a third party.
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
