# Tourist Guide App — MAD Mini Project Documentation

## 1. Problem statement
Tourists switch between several apps to find attractions and food nearby, understand places, plan a route, track a budget and get travel suggestions. This app brings live place discovery, place details, saved places, a day-by-day itinerary, a manual budget planner, an embedded map, a quiz and a grounded AI travel assistant into one Android interface.

## 2. Objectives
- Clean, responsive Material 3 UI with light and dark themes, readable text sizes and accessible labels.
- Location-aware discovery of attractions, restaurants and cafés from Google Places (New), with search, category filters, radius, sorting, "open now" and pull-to-refresh.
- One shared place model and repository so Explore, Map, Plan and Chat always show the same data.
- A clearly labelled curated Mumbai guide (and a 24-hour cache) when live data is unavailable. Old or static data is never shown as live.
- A useful trip planner: days, ordering, notes, user-entered costs, navigation per day.
- A manual, persistent budget planner with validation and over-budget warnings.
- A quiz with categories, difficulty levels, feedback, explanations and persistent best scores.
- An AI assistant whose recommendations come from real place data, with keys kept on a server.
- Optional login and manual cloud backup; guest mode by default.
- Honest documentation of what was tested and what needs configuration.

## 3. Scope and limitations
- Live places, photos and AI need the backend and the user's own Google Cloud (billing) and Gemini keys. Without them the app uses the curated guide and offline suggestions.
- Curated entry fees and visit times are indicative. Google price levels are relative categories; per-person price ranges are Google estimates. Both are labelled.
- Travel times and optimal routes are not calculated. "Order by proximity" is a straight-line heuristic and directions open in Google Maps.
- Login and cloud backup need a Firebase project (`google-services.json`). Google sign-in is not implemented.
- AI output can still be wrong; it is labelled as a suggestion.

## 4. Technology stack
- Android: Java 17, native Views, Material 3 (1.14), ViewBinding, RecyclerView, SwipeRefreshLayout, BottomNavigationView
- Maps & location: Google Maps SDK for Android (play-services-maps 20.1), Fused Location Provider (play-services-location 21.4), Android Geocoder
- Persistence: SharedPreferences (JSON documents), versioned with automatic migration from v2.x data
- Networking: `HttpURLConnection` on a background executor (no extra HTTP library)
- Optional cloud: Firebase Authentication + Cloud Firestore (BoM 35.1), applied only when configured
- Backend: Python, FastAPI, Pydantic, HTTPX, Google Places API (New), Gemini API
- Build: Gradle 9.3.1 / AGP 9.1.1, `compileSdk` 36, `targetSdk` 36, `minSdk` 24
- Tests: JUnit 4, Robolectric 4.17 (UI smoke tests on the JVM), pytest

## 5. Architecture
See `docs/ARCHITECTURE.md` for the diagram and data flow.

```text
MainActivity (BottomNavigationView; shared actions: openDetails, addToPlan, showOnMap, askAssistant)
   ├── ExploreFragment  — live/cached/curated places, filters, permission prompt, quiz entry
   ├── MapTabFragment   — same places as Explore + plan stops, search this area, key-less fallback
   ├── PlanFragment     — itinerary by day ── BudgetFragment (toggle)
   ├── ChatFragment     — grounded assistant, suggestions, offline fallback, add-to-plan
   └── MoreFragment     — account, badges, saved places, personalities, settings, privacy
DetailSheet, LocationSheet (bottom sheets) · QuizActivity · AccountActivity

Data layer
   ├── Place / Catalog              — shared model with stable ids; curated guide
   ├── PlaceRepository              — live → cached → curated, id lookup, listeners
   ├── LocationRepository           — selected location (device / area / default)
   ├── Itinerary / PlanRepository   — trip by day, persistence
   ├── BudgetCalculator / BudgetRepository
   ├── QuizBank / QuizSession
   ├── BackendClient / AiClient / OfflineAssistant
   ├── Store                        — SharedPreferences
   └── AuthManager / CloudSync      — optional Firebase
```

## 6. Modules
1. **Explore**: location chip, search, categories, radius, sort, open-now, data-source banner, permission card, skeleton loading, empty/error states with retry, load more, quiz entry.
2. **Detail sheet**: photo (with photographer credit), rating and review count, distance, open status and hours, price info, description (curated), directions, save, add to plan, show on map, ask assistant, Google attribution.
3. **Map**: markers from the shared repository, plan stops highlighted, search radius circle, search this area, locate-me, fallback without a key.
4. **Plan**: trip name, days and start date, stops by day, reorder, move day, notes, cost estimates, undo remove, navigate a day, order by proximity, clear.
5. **Budget**: seven inputs, live calculations, validation messages, over-budget warning, itinerary estimates kept separate, reset, persistence.
6. **Chat**: server status, suggested prompts, transparent "shared with the assistant" line, grounded answers with add-to-plan rows, retry, offline fallback.
7. **Quiz**: 60 questions (5 topics × 3 difficulties × 4), shuffled options, feedback with explanations, score and progress, results, retry, best scores.
8. **More**: guest/account card with manual backup and restore, badges, saved places, travel personalities, theme, live-places switch, server address with connection test, clear cache (keeps trips), reset quiz, privacy notes.
9. **Backend**: `/health`, `/places/nearby`, `/places/search`, `/places/locate`, `/places/details/{id}`, `/places/photo`, `/chat`; `check_keys.py` tests the keys against Google.

## 7. Input validation and error handling
- Budget fields: digits only (commas and ₹ allowed), negatives, decimals and values above ₹10 crore are rejected with a message. Days must be 1–365. Long overflow is impossible.
- Search text is trimmed and debounced. Live search needs at least 2 characters.
- Backend: every query parameter is range-checked (lat/lng, radius 100 m–50 km, category allow-list, photo name and place id patterns). Chat bodies are size-limited.
- Provider errors are mapped to clear messages: not configured, key rejected, quota, timeout, not found. Network failures fall back to cached or curated data, never a crash.
- Duplicate plan entries are refused. Removing a stop can be undone. Clearing the plan, resetting the budget, restoring a backup and replacing the plan all ask first.
- Location denial is non-fatal, with an explanation, a "choose an area" alternative and a link to settings.
- Detail sheets for places that no longer exist close safely (fixes a crash in the previous version).

## 8. Setup and execution
See `docs/SETUP.md` (Google Cloud keys and restrictions, Gemini, Firebase, running the backend and app on an emulator or phone).

## 9. Test cases
See `docs/TEST_CASES.md`. Automated: 58 JVM/Robolectric tests, 4 optional app↔backend contract tests and 46 backend tests. Device tests are listed with status "Not run" until you run them.

## 10. Future enhancements
- Google sign-in, automatic background sync with conflict resolution.
- Real travel times and route optimisation (Routes API).
- Room database, offline map tiles.
- Reviews and menus (Places Atmosphere fields; higher cost).
- Hindi and Marathi translations.
- Firebase App Check for the backend.

## 11. Conclusion
The app now combines live, location-aware discovery with a reliable offline fallback, a single shared data layer, practical planning and budgeting tools, and an AI assistant grounded in real data, while keeping keys off the device and being honest about what is live, estimated or untested.
