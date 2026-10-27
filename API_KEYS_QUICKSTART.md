# Set up your API keys (about 10 minutes)

The app works without any keys (curated guide + OpenStreetMap + offline chat).
Add keys to unlock:

| Key | Unlocks | Paste it into |
|---|---|---|
| **Gemini** | Online AI chat | `backend/.env` → `GEMINI_API_KEY=` |
| **Google Places** | Live nearby places, restaurants, ratings, photos | `backend/.env` → `PLACES_API_KEY=` |
| **Google Maps** (optional) | Google Maps instead of OpenStreetMap | `local.properties` → `MAPS_API_KEY=` |

Never upload `backend/.env` or `local.properties` to GitHub (they're already in `.gitignore`).

---

## 1. Gemini key (free tier, ~2 min)

1. Open https://aistudio.google.com/apikey and sign in.
2. Click **Create API key** and copy it.
3. Open `backend/.env` and paste it:
   ```
   GEMINI_API_KEY=paste_here
   ```

## 2. Google Places key (~5 min, needs billing)

1. Open https://console.cloud.google.com/ → top bar → **New project** (e.g. `tourist-guide`) → select it.
2. **Billing** (left menu) → **Link a billing account** (add a card). Google requires this; there is a monthly free allowance. Optional but smart: **Billing → Budgets & alerts → Create budget** (e.g. ₹500) so you get an email before any spend.
3. Search bar → **Places API (New)** → **Enable**.
   (Make sure it says **(New)**.)
4. **APIs & Services → Credentials → Create credentials → API key** → copy it.
5. Click the new key → **API restrictions → Restrict key → Places API (New)** → **Save**.
6. Paste into `backend/.env`:
   ```
   PLACES_API_KEY=paste_here
   ```

## 3. Google Maps key (optional, ~3 min)

Skip this if OpenStreetMap is fine.

1. Same project → search **Maps SDK for Android** → **Enable**.
2. **Credentials → Create credentials → API key** → copy it.
3. Click the key → **API restrictions → Restrict key → Maps SDK for Android** → **Save**.
4. Paste into `local.properties` (project's top folder):
   ```
   MAPS_API_KEY=paste_here
   ```
5. In Android Studio: **File → Sync Project with Gradle Files**, then Run.

## 4. Start the backend (Mac, Android Studio → Terminal)

First time:
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```
Every time after:
```bash
cd backend
source .venv/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8000
```
Keep that terminal open while using the app. After editing `backend/.env`, stop the server (**Control + C**) and start it again.

## 5. Check it worked

- Open http://localhost:8000/health → should show `"ai_configured":true` and `"places_configured":true`.
- In the app (emulator): Explore banner says **Live from Google Maps**, Chat says **Online**.
- On a real phone: **More › Settings › Server address** → `http://<your Mac's IP>:8000`
  (Mac IP: System Settings → Wi-Fi → Details → IP address).

## If something's wrong

| You see | Fix |
|---|---|
| "Can't reach the Tourist Guide server" | Backend isn't running (step 4). |
| "Live places are not configured on the server" | `PLACES_API_KEY` is empty, or you didn't restart the backend. |
| "Google Places rejected the server key" | Billing not linked, or **Places API (New)** not enabled, or key restricted to the wrong API. |
| Chat: "AI isn't configured on the server" | `GEMINI_API_KEY` is empty, or the backend wasn't restarted. |
| Google map is grey | Billing/Maps SDK not enabled for that key's project. Use **More › Settings › Map style → OpenStreetMap** meanwhile. |

More detail: `docs/SETUP.md`.
