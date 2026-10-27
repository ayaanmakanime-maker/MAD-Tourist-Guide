# Tourist Guide App — MAD Mini Project Documentation

## 1. Problem statement
Tourists often switch between multiple sources to discover attractions, understand destinations, plan a route, and find practical travel suggestions. This app brings attraction discovery, place details, saved places, itinerary building, an embedded map with location, and an optional AI travel assistant into one Android interface.

## 2. Objectives
- Provide a clean, responsive light-themed Android UI with Material 3 components.
- Search and filter a curated starter set of Mumbai attractions (live, as-you-type search with focus preservation).
- Present attraction descriptions, areas, approximate visit durations, and indicative budgets.
- Save favourites and plan stops locally across app restarts.
- Render an embedded Google Map with pins for all attractions, tap-to-details info windows, and a locate-me control backed by runtime location permissions.
- Offer a plan workflow with time/budget totals, area-based reordering, and full-day Maps directions with waypoints.
- Connect to an optional server-side Gemini-powered travel assistant with a clearly-labelled offline fallback.
- Document validation, test cases, limitations, and verification evidence honestly.

## 3. Scope and limitations
The starter dataset contains sample attraction metadata for Mumbai. Opening hours, fees, live restaurant listings, traffic, current distance/time estimates, ticket availability, and contact details are not fetched live. Full-day directions open externally in the Google Maps app. The optional AI service needs a configured backend and internet access. AI output may be inaccurate and is labelled as a suggestion in the UI.

## 4. Technology stack
- Android: Java 17, native Android Views, Material 3, ViewBinding, RecyclerView, BottomNavigationView
- Local persistence: Android SharedPreferences
- Maps: Google Maps SDK for Android (play-services-maps 20.x) + Google Play services location
- AI client: plain `HttpURLConnection` on a background executor (no HTTP library on-device)
- Optional AI backend: Python, FastAPI, HTTPX, Gemini API
- Build: Gradle 9.3.1 / AGP 9.1.1, `compileSdk` 36, `targetSdk` 36, `minSdk` 24
- Test documentation: `docs/TEST_CASES.md`

## 5. Architecture
```text
MainActivity (BottomNavigationView, fade tab swap)
   |
   ├── ExploreFragment   — hero, live search, filter chips, quiz header + place cards (RecyclerView)
   ├── MapTabFragment    — SupportMapFragment child, markers, info windows, location FAB, key-less fallback panel
   ├── PlanFragment      — ordered stops, totals, reorder, clear, full-day directions
   ├── ChatFragment      — AI messages, pending/offline/AI states, retry, server setting
   └── MoreFragment      — profile + badges, saved strip, personality picks, settings, guide

Shared pieces
   ├── Catalog          — static Place list + filtering
   ├── Store            — SharedPreferences (saved, plan order, quiz state, AI server URL)
   ├── DetailSheet      — BottomSheetDialogFragment for one attraction
   ├── AiClient         — POST /chat to the configured server, main-thread callbacks
   ├── ChatSession      — process-lifetime chat history (survives tab switches)
```
The AI provider key is stored on the backend (or `backend/.env`), never in the Android application. The Android emulator reaches a backend on the development host at `10.0.2.2:8000`. Cleartext HTTP is permitted only for `10.0.2.2` and `localhost` via `network_security_config.xml`; anything else must be HTTPS.

## 6. Modules
1. Explore: hero, search, interest filters, surprise button, mini-quiz, attraction cards.
2. Detail sheet: description, area, suggested duration, indicative budget, save, add to plan, external directions.
3. Map: embedded map, pins with info windows, locate-me, graceful key-missing fallback.
4. Saved strip (More tab): horizontal favourites list, heart toggles everywhere.
5. Plan: ordered list with per-stop meta, totals, reorder by area, clear, full-day directions.
6. Chat (assistant): input validation, optional server-side AI, loading/offline states and retry.
7. More: profile, badges, personality picks (applyable as plan), food stops, offline guide, AI server setting.

## 7. Input validation and error handling
- Blank assistant messages are rejected with a user-friendly prompt; the send button is guarded while a request is in flight.
- AI networking uses 8s connect / 25s read timeouts and reports failures through a clearly labelled offline bubble with Retry.
- Duplicate plan entries are prevented in the detail flow ("Already in your plan").
- Searches/filters with no matches display an empty state.
- Maps key is detected at runtime; without a key the tab swaps the SDK view for an actionable fallback panel.
- Location permission denial is non-fatal and explained with a toast.
- Backend request text is limited to 1,500 characters; backend returns a clear 503 when no provider key is configured.
- Destructive plan clear always asks for confirmation.

## 8. Setup and execution
See `README.md` for Android Studio, Maps key, and backend instructions. JDK 17+ is expected. First Gradle sync requires internet access.

## 9. Test cases
See `docs/TEST_CASES.md`. Record actual results and mark Pass/Fail only after running the app on both an emulator and a physical Android phone.

## 10. Future enhancements
- Replace sample listings with Google Places or another licensed provider.
- Add night mode, Room database, accounts, and cloud sync.
- Generate itineraries from travel time, opening hours, weather, and accessibility needs.
- Add unit/UI tests and CI build verification.

## 11. Conclusion
Tourist Guide demonstrates an integrated tourism workflow with an embedded map, local persistence, and an optional AI assistant architecture. A final submission should include screenshots, device details, completed test results, and any changes made after testing.
