# Architecture and data flow

## Big picture

```text
┌──────────────────────── Android app (Java, Views) ────────────────────────┐
│  Explore   Map   Plan(+Budget)   Chat   More        Quiz / Account / Sheet  │
│     │       │        │             │      │                                 │
│     ▼       ▼        ▼             ▼      ▼                                 │
│  PlaceRepository  PlanRepository  AiClient  BudgetRepository  AuthManager   │
│  LocationRepository   │  Itinerary      │   BudgetCalculator   CloudSync     │
│     │  Catalog (curated)│               │                                   │
│     ▼                   ▼               ▼                                   │
│  BackendClient (HTTP)        Store (SharedPreferences: favourites, plan,    │
│     │                              budget, quiz, settings, 24 h cache)      │
└─────┼──────────────────────────────────────────────────────────────────────┘
      │ HTTPS (HTTP only to 10.0.2.2 / debug builds)
      ▼
┌──────────────── FastAPI backend (backend/) ───────────────┐      ┌───────────────┐
│ /places/*  places.py  ── field-masked requests ──────────────────▶ Places API    │
│            TtlCache, RateLimiter, input validation         │      │ (New)         │
│ /chat      assistant.py ── context + JSON schema ──────────────────▶ Gemini API   │
│            (adds live restaurant search for food questions)│      └───────────────┘
│ /health    config.py reads GEMINI_API_KEY / PLACES_API_KEY │
└────────────────────────────────────────────────────────────┘
Firebase Auth + Firestore (optional) ◀── AuthManager / CloudSync (manual backup only)
```

## One place model, one repository

- **`Place`**: the single model used everywhere. Every place has a stable id:
  `curated:<slug>` (e.g. `curated:gateway-of-india`) or `google:<Google place id>`.
  Provider fields (`rating`, `ratingCount`, `priceLevel`, `priceStart/End`, `openNow`, `hours`, `photoName`) are nullable and are shown only when present.
- **`PlaceRepository`**: the only place that loads places. Explore and Map both listen to it, so a place you see in Explore is the same object on the Map, in Plan and in Chat. It also resolves any id via session results → curated catalogue → plan snapshot → saved snapshot, so deleted or disappeared listings never crash a screen.
- **States**: `LOADING`, `LIVE`, `CACHED` (live results under 24 h old, shown only when a live request fails and always labelled "not live"), `CURATED_FALLBACK` (shows the error reason) and `CURATED_ONLY` (live turned off).
- **`PlaceQuery` / `PlaceFilter`**: category, text, radius, sort, open-now. Sorting and "open now" run on the device, so changing them doesn't cost an API call. Duplicates are removed by id.

## Location

`LocationRepository` holds the *selected location*: the device position, an area the user picked, or Mumbai by default. Explore asks for permission only after explaining why, and everything works if the user declines.

- **Device position**: a high-accuracy `getCurrentLocation` (GPS when available, up to 20 s, fixes at most 15 s old), falling back to the last known position if it is under 10 minutes old. The accuracy is kept and shown on the Map ("±10 m").
- **Following (foreground only)**: while `MainActivity` is started and the user chose *Use my location*, location updates run (high accuracy, every 10–15 s, 50 m minimum). `shouldFollow` (unit tested) moves the search centre only for fixes within ±200 m when the user moved ≥ 250 m, or when a fix is far more precise than a coarse first one, at most once a minute; the places then reload. Updates stop in `onStop` and when the user picks an area. Nothing runs in the background.
- **Area or landmark search**: `GET /places/locate` (Google Places Text Search biased to a 50 km circle around Mumbai) returns up to 5 matches with full addresses, and the sheet lists them so the user picks the right one. Without the server, the phone's Geocoder is used (restricted to the Mumbai region first) and its matches are labelled approximate.

## Plan and budget

- `Itinerary` (pure Java): days, ordered stops, notes, user-entered cost estimates, dedupe, move up/down/between days, undo, a nearest-neighbour "order by proximity", and JSON persistence. Each stop stores a small snapshot of its place, so the plan still shows offline. Photo names are never stored (Google says they expire).
- `BudgetCalculator` (pure Java): parsing/validation (empty, negative, decimals, huge numbers checked *before* parsing so a `long` can't overflow, capped at ₹10 crore per field) and the summary maths. `BudgetRepository` stores exactly what was typed. Costs typed on plan stops are shown separately and are only added to the budget when the user taps *Use as Tickets & attractions*.

## Assistant data flow

1. `ChatFragment` builds a request with `AiClient.buildRequest(...)`: the message, last 6 turns, the selected area (lat/lng rounded to ~100 m; nothing sent for the default area), up to 25 loaded places (distances rounded to 100 m), the last-viewed place, plan stop names/days and the user's budget numbers. No name, email or account id.
2. The backend (`/chat`) validates it with Pydantic. If the question is about food and a location is known, it runs a **live Places text search** (`vegetarian restaurant`, `cafe`, …, with `openNow` when asked) around the user or the selected place.
3. It sends Gemini a system prompt with grounding rules (recommend only listed ids; never invent ratings, hours or prices; price level ≠ cost; ask one follow-up if information is missing), the context JSON (coordinates rounded again to ~1 km) and a **JSON response schema** `{reply, place_ids, needs_more_info}`.
4. The backend keeps only `place_ids` that exist in the context (invented ids are dropped) and returns those places. The app shows them as rows with **Add to plan**.
5. Gemini models are tried in turn: busy (503), retired (404), rejected (400/401/403) or cut-off answers fall through to the backup model; Google's error message (with anything key-like removed) is logged and, if no model answers, returned to the app ("Google rejected the server's Gemini key: …").
6. If anything fails, `OfflineAssistant` builds a labelled offline answer: places already in the app for food, café, day-plan and topic questions (beaches, museums, history, shopping, nature), or the user's own numbers for "how much budget is left" and "what's in my plan".

## Security and privacy

- No provider key is in the APK. The Maps SDK key is the only key on the device and must be Android-restricted. The Places and Gemini keys live in `backend/.env`.
- Release builds allow cleartext HTTP only to the emulator host. Debug builds allow LAN HTTP so you can test on a phone.
- The backend validates every parameter, maps provider errors (quota → 429, key problems → 503), caches identical searches, rate-limits per client and enforces daily caps.
- Firestore rules allow each user to read and write only `users/{their uid}`, with size and field validation.

## Files at a glance

| Layer | Classes |
|---|---|
| Screens | `MainActivity`, `ExploreFragment`, `MapTabFragment`, `PlanFragment`, `BudgetFragment`, `ChatFragment`, `MoreFragment`, `DetailSheet`, `LocationSheet`, `QuizActivity`, `AccountActivity` |
| Adapters / UI helpers | `PlaceAdapter`, `ChatAdapter`, `Formats`, `ImageLoader` |
| Data | `Place`, `Catalog`, `PlaceQuery`, `PlaceFilter`, `PlaceRepository`, `LocationRepository`, `Itinerary`, `PlanRepository`, `BudgetCalculator`, `BudgetRepository`, `Money`, `GeoUtils`, `QuizQuestion`, `QuizBank`, `QuizSession`, `Store`, `ChatSession` |
| Network / services | `BackendClient`, `AiClient`, `OfflineAssistant`, `AuthManager`, `CloudSync`, `TouristApp` |
| Backend | `main.py` (endpoints, validation, cache, rate limits), `places.py`, `assistant.py`, `config.py` |
