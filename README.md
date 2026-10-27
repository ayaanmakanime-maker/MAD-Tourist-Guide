# Tourist Guide MAD — AI-powered Mumbai travel companion

A native Android app (Java, Views, Material 3, ViewBinding) for exploring Mumbai: live nearby attractions, restaurants and cafés from Google Places, an embedded map, a day-by-day trip planner, a manual budget planner, a Mumbai quiz, and an AI travel assistant (Gemini) grounded in the places and trip on your screen. A small FastAPI backend keeps every provider key off the phone.

The app works without any keys: it falls back to a clearly labelled curated Mumbai guide and offline suggestions.

## Features

| Tab | What it does |
|---|---|
| **Explore** | Nearby places around your location or a chosen area. Category chips (Attractions, Restaurants, Cafés, Historic, …), search, radius, sort (relevance/distance/rating), *Open now*, pull-to-refresh, load more. Every list says where its data came from: **Live · Google Maps**, **Saved earlier (not live)** or **Curated guide**. Includes the Mumbai quiz entry. |
| **Map** | The same places as Explore (one shared repository) plus your plan stops in a different colour. "Show on map" from any screen, "Search this area", locate-me. Uses Google Maps when `MAPS_API_KEY` is set, otherwise OpenStreetMap (no key needed). |
| **Plan** | Itinerary organised by day: add/remove (with undo), reorder, move between days, notes, your own cost estimates, trip name/dates, navigate a whole day in Google Maps, "order by proximity" (a simple straight-line heuristic, clearly labelled). **Budget** toggle: total, days, food, transport, tickets, shopping, other → planned total, remaining, daily budget, per-day figures, utilisation and an over-budget warning. |
| **Chat** | Travel assistant via the backend. Sends only your rounded area, loaded places, plan and budget numbers. Food questions trigger a live restaurant search on the server. Recommended places come back as "Add to plan" rows. Offline fallback picks from places already in the app. |
| **More** | Optional account (Firebase email/password) and manual cloud backup, badges, saved places, travel-personality plans, theme (light/dark/system), live-places switch, server address, clear cached data, reset quiz, privacy notes. |

## Project layout

```
app/        Android app (com.example.touristguide)
backend/    FastAPI backend: Places API (New) proxy + grounded Gemini chat
firebase/   Firestore security rules for the optional cloud backup
docs/       Setup guide, architecture, test cases, upgrade report
```

## Quick start

1. **Backend** (needed for live places and AI):
   ```bash
   cd backend
   python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
   pip install -r requirements.txt
   cp .env.example .env        # then add GEMINI_API_KEY and PLACES_API_KEY
   uvicorn main:app --host 0.0.0.0 --port 8000
   ```
   Check http://localhost:8000/health.
2. **Android**: open this folder in Android Studio (JDK 17+), let Gradle sync, optionally add `MAPS_API_KEY=` to `local.properties` to use Google Maps instead of the built-in OpenStreetMap map, and run on an emulator (API 24+). The emulator reaches your computer's backend at `http://10.0.2.2:8000` by default. On a phone, set the server address in **More › Settings**.

Quick key setup (Gemini, Places, Maps): **[API_KEYS_QUICKSTART.md](API_KEYS_QUICKSTART.md)**. Full details incl. Firebase: [docs/SETUP.md](docs/SETUP.md).

## Tests

```bash
./gradlew testDebugUnitTest                                   # JVM unit + Robolectric UI smoke tests
cd backend && pip install -r requirements-dev.txt && pytest   # backend tests (providers mocked)
```

Optional extras: an end-to-end app↔backend contract test, and PNG renders of the screens. See [docs/UPGRADE_REPORT.md](docs/UPGRADE_REPORT.md#tests-and-results).

## Honest limits

- Live data needs the backend plus a Google Cloud project with billing and Places API (New) enabled. Without it you see the curated guide, labelled as such.
- Curated prices and visit times are indicative. Google price levels are categories, not exact costs, and are labelled that way.
- "Order by proximity" uses straight-line distance; travel times are not calculated.
- Login and cloud backup only work after you add your own Firebase configuration (see SETUP.md).

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/PROJECT_DOCUMENTATION.md](docs/PROJECT_DOCUMENTATION.md) · [docs/TEST_CASES.md](docs/TEST_CASES.md)
