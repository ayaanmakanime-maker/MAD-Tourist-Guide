# Setup guide

Everything is optional: with no keys at all the app runs on the curated Mumbai guide and offline suggestions. Each step below unlocks one feature.

| Feature | Needs | Where the secret lives |
|---|---|---|
| Google Maps instead of the built-in OpenStreetMap map (optional) | Maps SDK for Android key | `local.properties` → `MAPS_API_KEY` (Android-restricted) |
| Live places, photos, restaurant search | Places API (New) key + billing | `backend/.env` → `PLACES_API_KEY` (server key) |
| AI assistant | Gemini API key | `backend/.env` → `GEMINI_API_KEY` |
| Login + cloud backup | Firebase project | `app/google-services.json` + Firestore rules |

> **Security first.** The `Ayaan_MAD.zip` uploaded to the public GitHub repo contained a real Maps key and a real Gemini key (in `local.properties`, `backend/.env` and inside `prebuilt/TouristGuide-debug.apk`). Treat both as leaked: **delete/regenerate them in Google Cloud Console and Google AI Studio before doing anything else**, then remove the zip from the repository (and its history) or make the repo private.

## 0. Prerequisites

- Android Studio (recent stable), JDK 17+ (Android Studio's bundled JDK is fine).
- Python 3.10+ for the backend.
- A Google account. Live places require a Google Cloud **billing account** (Google Maps Platform will not serve Places requests without one).

## 1. Google Cloud project and keys

1. Go to https://console.cloud.google.com/ → create a project (e.g. `tourist-guide-mad`).
2. **Billing**: Billing → link a billing account to the project. Optionally set a **budget alert** (Billing → Budgets & alerts) and lower the quotas (APIs & Services → Places API (New) → Quotas) so a demo can never run up a bill.
3. **Enable APIs** (APIs & Services → Library):
   - **Maps SDK for Android** (embedded map)
   - **Places API (New)**. Make sure it is the *New* one; the app uses `places.googleapis.com/v1`.
4. **Create two separate keys** (APIs & Services → Credentials → Create credentials → API key):

   **a) Android key → `MAPS_API_KEY`**
   - Application restriction: *Android apps* → add package `com.example.touristguide` + your SHA-1 fingerprint.
     Get the debug SHA-1 with `./gradlew signingReport` (look for `Variant: debug`, `SHA1:`), or
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`.
   - API restriction: *Restrict key* → **Maps SDK for Android** only.

   **b) Server key → `PLACES_API_KEY`**
   - API restriction: **Places API (New)** only.
   - Application restriction: *IP addresses* → your server's public IP once deployed. (On a laptop with a changing IP you may temporarily leave this unset; keep the API restriction and quotas.)
   - Never put this key in the app.

### What the backend requests (and why it costs what it does)

The backend calls Nearby Search (New), Text Search (New), Place Details (New) and Place Photos (New) with a **field mask** of only what the app shows:
`id, displayName, formattedAddress, shortFormattedAddress, location, types, primaryType, primaryTypeDisplayName, rating, userRatingCount, priceLevel, priceRange, currentOpeningHours, photos, googleMapsUri, businessStatus`.

`rating`, `userRatingCount`, `priceLevel`, `priceRange` and `currentOpeningHours` are **Enterprise**-tier fields, so each search is billed at the Enterprise SKU. No Atmosphere fields (reviews, `servesVegetarianFood`, …) are requested; vegetarian searches use a text query instead. Photos are only fetched when a detail sheet is opened. Google gives a free monthly usage allowance per SKU. Check the current numbers at https://developers.google.com/maps/billing-and-pricing/pricing before a demo.

Cost protection in the backend: a 10-minute in-memory cache for identical searches, per-client rate limiting, and daily caps (`DAILY_PLACES_LIMIT`, `DAILY_CHAT_LIMIT`).

## 2. Gemini key

1. Go to https://aistudio.google.com/apikey → *Create API key* (use a fresh key, not the leaked one).
2. Put it in `backend/.env` as `GEMINI_API_KEY`. The default model is `gemini-3.1-flash-lite` (what the original project used); override with `GEMINI_MODEL`. `GEMINI_FALLBACK_MODEL` is tried if the main model is busy or retired.

## 3. Run the backend

```bash
cd backend
python -m venv .venv
source .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env                 # Windows: copy .env.example .env
# edit .env: GEMINI_API_KEY=..., PLACES_API_KEY=...
uvicorn main:app --host 0.0.0.0 --port 8000
```

Check http://localhost:8000/health → `{"status":"ok","ai_configured":true,"places_configured":true,...}`.
Interactive API docs: http://localhost:8000/docs.

| Endpoint | Purpose |
|---|---|
| `GET /health` | Status and which providers are configured |
| `GET /places/nearby?lat&lng&radius&category&rank` | Nearby Search (New) |
| `GET /places/search?q&lat&lng&radius&category&open_now&page_token` | Text Search (New), paginated |
| `GET /places/details/{place_id}` | Fresh details for one place |
| `GET /places/photo?name&max_width` | Short-lived photo URL (no key in it) |
| `POST /chat` | Grounded assistant (existing endpoint, extended request body) |

## 4. Run the Android app

1. Open the project root in Android Studio and let Gradle sync (first sync needs internet).
2. Create/update `local.properties` (see `local.properties.example`):
   ```properties
   MAPS_API_KEY=AIza...your_android_key...
   # BACKEND_URL=http://10.0.2.2:8000      # default; fine for the emulator
   ```
   **Remove any old `GEMINI_API_KEY` line**: the app no longer embeds it.
3. Run on an emulator (API 24+). `10.0.2.2` is the emulator's address for your computer.
4. **Physical phone**: put the phone and computer on the same Wi-Fi, start uvicorn with `--host 0.0.0.0`, then in the app go to **More › Settings › Server address** and enter `http://<your-computer-IP>:8000` (find the IP with `ipconfig` / `ip addr`). Plain HTTP is allowed **only in debug builds**. For a release build, deploy the backend behind HTTPS (e.g. Cloud Run, Render, Railway) and use that URL.

## 5. Firebase (optional login + cloud backup)

1. https://console.firebase.google.com → *Add project* → you can select the same Google Cloud project.
2. *Add app* → Android → package name `com.example.touristguide` → add the debug **SHA-1** (from step 1.4a).
3. Download **`google-services.json`** and place it at **`app/google-services.json`**. The build detects it automatically (`BuildConfig.FIREBASE_CONFIGURED` becomes `true` and the Google Services plugin is applied). It is gitignored.
4. Authentication → *Get started* → enable **Email/Password**. (Google sign-in is not implemented in this version.)
5. Firestore Database → *Create database* (production mode, a region near you, e.g. `asia-south1`).
6. Deploy the security rules from `firebase/firestore.rules`, either:
   - Console: Firestore → *Rules* → paste the file → *Publish*, or
   - CLI: `npm i -g firebase-tools && firebase login && cd firebase && firebase use <project-id> && firebase deploy --only firestore:rules`.
7. Rebuild. **More** now shows *Sign in or create account*; after signing in, *Back up to cloud* / *Restore* appear.

What is stored: one document `users/{uid}` with your favourites, itinerary, budget and quiz progress (JSON strings), written only when you tap **Back up**. Passwords are handled by Firebase Authentication; the app never stores them.

## 6. Verify everything

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
cd backend && pip install -r requirements-dev.txt && pytest -q
```

Then on a device: Explore should show **Live · Google Maps**, the Chat header **Online**, and More › Settings › Server address › Save should report `live places: yes · AI: yes`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Explore says "curated guide… Can't reach the server" | Backend not running, wrong server address, or phone not on the same network. |
| "Live places are not configured on the server" | `PLACES_API_KEY` missing in `backend/.env`; restart uvicorn. |
| "Google Places rejected the server key" | Places API (New) not enabled, billing not linked, or the key's API/IP restrictions exclude the server. |
| Map shows OpenStreetMap instead of Google Maps | Expected without `MAPS_API_KEY`. Add the key to `local.properties` and rebuild to switch to Google Maps. |
| OpenStreetMap is blank/grey | The emulator/phone has no internet access (tiles are downloaded). |
| Map is grey | Android key restriction doesn't match the package name/SHA-1, or Maps SDK for Android isn't enabled. |
| "Accounts aren't set up in this build" | `app/google-services.json` missing; add it and rebuild. |
