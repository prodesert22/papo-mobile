## Calls

- Choose where the call plays: the speaker, the earpiece, a wired or USB
  headset, or a Bluetooth headset on Android 12 or newer. A headset that
  connects takes over, and when the one in use goes away the call moves to the
  earpiece. With only the speaker and the earpiece, the button still switches
  between them.
- The sound of a screen someone shares from another client now plays, up to
  two at a time; a notice says when one more does not fit.
- Set the volume of each person and of their shared screen, from 0 to 200 %,
  or mute someone just for you: touch a tile without video, or hold any tile.
  The phone keeps it for each account, and the server is not told.
- A floating window (picture-in-picture) keeps the call in view when you leave
  the app: someone's shared screen, the camera of whoever is talking, or their
  picture in a voice-only call, with buttons to mute and hang up. It opens when
  you leave from the call screen, or from anywhere in the app while your camera
  or screen is on.
- The call notification shows the channel, how long the call has lasted and
  what is on (microphone, camera, screen), with buttons to mute and hang up
  that also work on the lock screen without unlocking the phone. On the lock
  screen it hides the channel name when the phone hides notification content.
- Push to talk: turn it on in the call and hold the button to speak. The others
  can take up to about a second to hear you.
- The headset's play/pause button mutes and unmutes the microphone, even with
  the app in the background.

## Fixes

- When Android stops your screen sharing (from its own notice, or when the
  phone locks) or another app takes the camera, the call now stops sending it,
  instead of the app and the server still treating it as on.
- When the server refuses someone's camera or screen (for example, too many at
  once), the app no longer keeps waiting for it, which could make the next one
  show up in the wrong tile.

## Not in this release

- Sending your own screen's sound: the WebRTC library the app uses only sends
  the microphone.
- Calls in direct messages: the server has calls only in voice channels.
- Choosing a Bluetooth headset on Android 11.
