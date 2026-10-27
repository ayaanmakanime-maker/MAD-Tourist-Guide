# Tourist Guide MAD — Test cases and results

**Automated** results come from real runs (see `docs/UPGRADE_REPORT.md`). **Device** cases were *not* run in the development environment (no emulator/phone available). Run them on an emulator and a physical phone and record the results before submission.

## A. Automated tests

| Suite | What it covers | Result |
|---|---|---|
| `BudgetCalculatorTest` (11) | Totals, remaining, per-day rounding, utilisation, exact/over budget, zero total, max values without overflow, clamping, parsing (commas, ₹, empty, negative, decimals, huge numbers), days 1–365, INR grouping | Pass |
| `QuizTest` (9) | Bank integrity (unique ids/options, explanations), ≥4 questions per topic×difficulty, original 3 questions retained, shuffling keeps the correct answer, scoring, no double-scoring, can't skip | Pass |
| `ItineraryTest` (9) | No duplicates, days and clamping, reorder within day, move day, remove + undo, user costs only, notes limit, proximity order, JSON round trip (no photo names), corrupt data | Pass |
| `PlaceTest` (9) | Stable unique catalogue ids, backend JSON parsing with missing fields left empty, curated source preserved, filter/sort/dedupe/open-now, curated categories, load-more merge, query normalisation, distances | Pass |
| `AssistantTest` (4) | Chat request: rounded location, rounded distances, no invented ratings, history cap, budget summary, default area not sent; offline assistant intents | Pass |
| `UiSmokeTest` (10, Robolectric) | Launch; offline Explore falls back to the curated guide labelled "not live"; every tab opens; map fallback without a key; Explore→Plan add with duplicate refused and persistence; budget live update, over-budget warning, validation; chat offline fallback grounded in food places; full quiz run with best score; account screen honest without Firebase; detail sheet for a valid and an unknown id; choosing an area reloads | Pass |
| `BackendContractTest` (3, optional) | Real app data layer ↔ real FastAPI backend (providers faked): live nearby parse, text search and details, chat with grounded add-to-plan place and the invented id dropped | Pass (when run with `-PcontractBackendUrl`) |
| `backend/tests/test_backend.py` (34) | Health, validation, normalisation, dedupe, closed places hidden, field mask (no Atmosphere fields), caching, error mapping (429/403/500/400/timeout), text search params, details 404, photo URL, rate limits, daily cap, chat grounding, coordinate rounding, live food search, model fallback, non-JSON replies, food-intent detection for the 5 sample queries | Pass |

## B. Device test plan

| ID | Type | Scenario | Expected result | Actual | Status |
|---|---|---|---|---|---|
| D01 | Positive | First launch, backend running with keys | Explore shows "Live from Google Maps", places with ratings/distance; permission card explains location | | Not run |
| D02 | Positive | Tap "Use my location", allow | Location chip shows your area; results reload around you | | Not run |
| D03 | Negative | Deny location | "Location is off" card with Choose an area; app fully usable | | Not run |
| D04 | Negative | Deny with "Don't ask again", tap Open settings | App settings open | | Not run |
| D05 | Positive | Choose area "Bandra West" | Results reload around Bandra; Map circle moves | | Not run |
| D06 | Positive | Restaurants chip, Sort: Rating, Open now | Only food places, highest rated first, only places marked open | | Not run |
| D07 | Positive | Search "vegetarian thali", Load more | Text results; more results appended without duplicates | | Not run |
| D08 | Positive | Pull to refresh | Spinner, list refreshes, "updated just now" | | Not run |
| D09 | Negative | Stop backend, refresh | Saved results labelled "not live" (if <24 h) or curated guide with reason and Retry | | Not run |
| D10 | Positive | Open a live place | Photo with credit, rating, hours, price label, Google attribution | | Not run |
| D11 | Positive | Detail › Show on map | Map centres on that place with info window | | Not run |
| D12 | Positive | Detail › Add to plan (3-day trip) | Asked which day; stop appears in that day | | Not run |
| D13 | Negative | Add the same place twice | "Already in your plan"; one stop | | Not run |
| D14 | Positive | Plan: move up/down, move to day, note, cost, Undo remove | Order/notes/costs update and persist after restart | | Not run |
| D15 | Positive | Plan › Navigate (day with 3 stops) | Google Maps opens with waypoints | | Not run |
| D16 | Positive | Budget: 5000 / 2 days / food 2500 / transport 1500 / tickets 1800 | "Over budget by ₹800", 116.0% used, warning shown | | Not run |
| D17 | Negative | Budget: type "-5", "12.5", 999999999999 | Field errors, no crash | | Not run |
| D18 | Positive | Close and reopen the app | Plan, budget, favourites and quiz best scores kept | | Not run |
| D19 | Positive | Chat: "Find vegetarian restaurants nearby" (backend + keys) | Answer names real places with "Add to plan" rows | | Not run |
| D20 | Negative | Chat with backend stopped | Offline suggestion labelled as such, "Why" line, Retry | | Not run |
| D21 | Positive | Quiz: Food · Hard, play to the end, Play again | Feedback per answer, result screen, best score saved | | Not run |
| D22 | Positive | More › Theme › Dark | Whole app switches to dark theme, readable contrast | | Not run |
| D23 | Positive | More › Clear cached place data | Cache cleared; plan and saved places still there | | Not run |
| D24 | Positive | With Firebase: create account, Back up, sign out, sign in on another device, Restore | Data restored | | Not configured |
| D25 | Negative | Firebase: register existing email / wrong password | Clear error messages | | Not configured |
| D26 | Positive | TalkBack on: navigate Explore and Plan | Buttons announce names (e.g. "Add Gateway of India to plan") | | Not run |
| D27 | Positive | System font size: Largest | Text scales; nothing overlaps or is cut off | | Not run |

## Execution evidence checklist
- [ ] Emulator model and API level recorded
- [ ] Physical phone model and Android version recorded
- [ ] Screenshots of core flows taken on the device
- [ ] Actual results filled in for D01–D27
- [ ] Logcat reviewed for crashes
