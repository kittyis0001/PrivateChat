# PrivateChat Cloud Functions

## Why this exists

The Android app only ever *receives* and displays FCM push
notifications (`ChatFirebaseMessagingService`). Nothing in the
project was ever *sending* one — so granting notification permission
on the phone had no effect, because no push was ever dispatched in
the first place.

Sending a real FCM push requires a Firebase service-account
credential, which must never be bundled inside the Android APK (it
would let anyone who decompiles the app send arbitrary pushes, or
abuse the project's Firebase quota). A Cloud Function is the correct,
standard place for this: it runs on Google's servers with the
project's own service-account credentials, and is the same mechanism
WhatsApp/Messenger-style apps use server-side.

`sendMessageNotification` watches `/messages/{messageId}` for new
messages and pushes a data-only, high-priority FCM message to
whichever of the two fixed users (`katis1` / `kittyis0001`) didn't
send it, reading their token from `fcmTokens/{user}` (already written
by the Android app on login / token refresh).

## One-time setup + deploy

You'll need the Firebase CLI authenticated against this project
(`private-chat-7a103`, per `.firebaserc`):

```bash
npm install -g firebase-tools   # if you don't have it already
firebase login

cd functions
npm install

cd ..
firebase deploy --only functions
```

That's the only manual step — everything else (the function code,
`firebase.json`, `.firebaserc`) is already in place in this repo.

## What it does NOT change

- The Android client's notification *display* logic
  (`ChatFirebaseMessagingService.onMessageReceived`) is untouched —
  it already expected exactly the `senderName` / `preview` data keys
  this function sends.
- Mute stays a purely client-side, per-device setting
  (`Session.isMuted()`); the function has no idea whether the
  recipient is muted, and doesn't need to — the client suppresses the
  visible notification after receiving the push.
