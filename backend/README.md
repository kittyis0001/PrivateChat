# PrivateChat Notification Backend

A small Node.js + Express API, hosted on Render's free tier, whose
only job is to send the FCM push notification that the Android app
itself has no safe way to send directly (that would require bundling
a Firebase service-account private key inside the APK).

Flow: Android writes a message to Firebase → Android calls this API →
this API looks up the recipient's FCM token in the same Firebase
Realtime Database → sends the push via FCM HTTP v1 (through
`firebase-admin`, which uses the v1 API internally).

Uses only the Firebase **Spark (free)** plan — no Cloud Functions, no
Blaze plan. All it needs from Firebase is a service account (free on
any plan) and read/write access to the existing Realtime Database.

## 1. Generate a Firebase service account

1. Firebase Console → your project (**private-chat-7a103**) → gear
   icon → **Project settings** → **Service accounts**.
2. Click **Generate new private key** → confirm. A JSON file downloads.
3. Open it — you'll need three fields from it in step 3 below:
   `project_id`, `client_email`, `private_key`.
4. **Do not commit this JSON file anywhere.** Delete it from your
   Downloads folder once you've copied the values into Render.

## 2. Create the Web Service on Render

1. [render.com](https://render.com) → **New** → **Web Service**.
2. Connect this GitHub repository.
3. Configure:
   - **Root Directory**: `backend`
   - **Runtime**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Plan**: Free

   (Alternatively: **New** → **Blueprint**, point it at
   `backend/render.yaml` in this repo, and Render fills in the same
   settings for you — you'll still be prompted for the secret env
   vars below either way.)

## 3. Set Environment Variables

In the new service's **Environment** tab, add:

| Key | Value |
|---|---|
| `PROJECT_ID` | `project_id` from the service account JSON |
| `CLIENT_EMAIL` | `client_email` from the service account JSON |
| `PRIVATE_KEY` | `private_key` from the service account JSON, **including** the `\n` sequences, wrapped in quotes |
| `API_SECRET` | Any long random string — e.g. run `openssl rand -hex 32` locally and paste the result |
| `PORT` | `10000` (Render also sets this automatically; harmless either way) |
| `YOUTUBE_API_KEY` | Optional — powers the story music feature's search/trending/recommend. Get one from Google Cloud Console: enable the "YouTube Data API v3" on a project, then create an API key under Credentials. Without this set, those three endpoints just return an empty song list rather than erroring — everything else works fine either way. |

See `.env.example` for the exact shape expected.

## 4. Deploy

Render deploys automatically once the env vars are saved. Confirm
it's up:

```bash
curl https://YOUR-SERVICE-NAME.onrender.com/health
# {"status":"ok"}
```

## 5. Point the Android app at it

In `app/build.gradle.kts`, update:

```kotlin
buildConfigField("String", "BACKEND_BASE_URL", "\"https://YOUR-SERVICE-NAME.onrender.com/\"")
```

and set the matching `API_SECRET` value in
`NotificationApiClient.kt` (or wherever you choose to keep it —
see that file's comments) to the exact same string you put in
Render's `API_SECRET`. Rebuild the app.

## API

### `POST /api/notify`

Headers: `X-Api-Secret: <API_SECRET>`, `Content-Type: application/json`

Body:
```json
{
  "senderId": "katis1",
  "receiverId": "kittyis0001",
  "senderName": "Kat",
  "preview": "Hey, are you there?"
}
```

`senderId`/`receiverId` must be exactly the app's two fixed usernames
(`katis1`, `kittyis0001`) and must differ from each other — anything
else is rejected with `400`. Requests without a matching
`X-Api-Secret` are rejected with `401`. Rate-limited to 60
requests/minute per IP.

### `GET /health`

No auth required. Used for Render's own health checks and for
confirming the service is reachable.

### `GET /api/music/search?q=<query>`

Headers: `X-Api-Secret: <API_SECRET>`

Returns `{ "songs": [...] }` — each song shaped
`{ videoId, jamendoId, title, artist, thumbnail, audioUrl, source }`.
Empty list if `YOUTUBE_API_KEY` isn't configured.

### `GET /api/music/trending`

Same response shape as search — YouTube's current most-popular music videos.

### `POST /api/music/recommend`

Headers: `X-Api-Secret: <API_SECRET>`, `Content-Type: application/json`

Body: `{ "caption": "optional story caption text" }`

Returns `{ "songs": [...], "mood": "Chill", "vibe": "relaxed" }` — a
few keywords in the caption are matched to a mood (falls back to a
generic one), then that mood is searched on YouTube. Simpler than the
reference web app's Gemini-based mood analysis, since no AI API key is
configured here — see `services/music.js`'s own comment.

## Local testing

```bash
cd backend
cp .env.example .env   # fill in real values, never commit this file
npm install
npm start
```
