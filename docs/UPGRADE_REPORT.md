# Upgrade report (v2.1 → v3.0)

This documents what changed when TouristGuideMAD was upgraded from the uploaded `Ayaan_MAD.zip`, what was verified, and what still needs your configuration.

## ⚠ Action required: leaked keys

The uploaded zip (now public on GitHub `main`) contains a **real Google Maps key** and a **real Gemini key** in `local.properties` and `backend/.env`, and the Gemini key is also compiled into `prebuilt/TouristGuide-debug.apk`. None of these files are in the upgraded project, but the keys are already exposed:

1. Delete/regenerate the Maps key (Google Cloud Console → Credentials) and the Gemini key (Google AI Studio).
2. Remove `Ayaan_MAD.zip` from the repo (and from history, or make the repo private).

## 1. What I found in the original project

- Java + Views + Material 3 + ViewBinding, package `com.example.touristguide`, AGP 9.1.1 / Gradle 9.3.1, compile/target SDK 36, min SDK 24. All kept; no library downgrades or risky upgrades.
- Five tabs (Explore, Map, Plan, Chat, More), a 14-place hard-coded catalogue, favourites and plan stored **by name** in SharedPreferences, a 3-question reveal-answer quiz, plan time/budget totals, travel personalities, badges.
- **Chat called Gemini directly from the app with the key embedded in the APK** (`BuildConfig.GEMINI_API_KEY`). The FastAPI backend existed but was unused.
- No budget planner, no live places, no dark mode, no tests.
- A crash: `DetailSheet` dereferenced a null place when a name wasn't found.

## 2. Implementation plan that was followed

1. Backend: Places API (New) proxy and grounded `/chat`, keys only on the server.
2. Shared data layer: `Place` with stable ids, `PlaceRepository` (live → cached → curated), `LocationRepository`, `Itinerary`/`PlanRepository`, `BudgetCalculator`/`BudgetRepository`, quiz model, with migration of old data.
3. Screens rebuilt on that layer, existing tabs and features kept.
4. Budget planner and the expanded quiz.
5. Assistant grounded in places, plan and budget, with add-to-plan.
6. Optional Firebase login and manual backup.
7. Dark mode, accessibility, states, polish.
8. Build, lint, tests, docs.

## 3. Files

**New (Android):** `TouristApp`, `PlaceRepository`, `LocationRepository`, `PlanRepository`, `BudgetRepository`, `BudgetCalculator`, `BudgetFragment`, `Itinerary`, `PlaceQuery`, `PlaceFilter`, `GeoUtils`, `Money`, `BackendClient`, `OfflineAssistant`, `ImageLoader`, `LocationSheet`, `QuizActivity`, `QuizBank`, `QuizQuestion`, `QuizSession`, `AuthManager`, `CloudSync`, `AccountActivity`; layouts `activity_account`, `activity_quiz`, `dialog_input`, `dialog_trip`, `fragment_budget`, `item_chat_place`, `item_day_header`, `item_load_more`, `item_permission`, `item_plan_header`, `item_quiz_entry`, `item_skeleton`, `item_status`, `sheet_location`; `values-night/colors.xml`, `values(-night)/bools.xml`; 12 Material icons; `src/debug/res/xml/network_security_config.xml`.

**Modified (Android):** `AiClient` (now calls the backend), `Catalog` (ids + 5 curated food stops), `Place` (ids, provider fields, JSON), `Store` (id-based storage + migration, cache, settings), `Formats`, `PlaceAdapter`, `ChatAdapter`, `ChatSession`, `ChatFragment`, `DetailSheet`, `ExploreFragment`, `MainActivity`, `MapTabFragment`, `MoreFragment`, `PlanFragment`; all existing layouts; `strings.xml`, `colors.xml`, `themes.xml`; `AndroidManifest.xml`; `app/build.gradle.kts`, root `build.gradle.kts`.

**Removed:** `item_quiz.xml` (replaced by the quiz screen) and unused drawables (`bg_drag`, `ic_delete`, `ic_link`, `ic_payment`, `ic_time`). The `prebuilt/` APK (it contained the leaked key), `local.properties` and `backend/.env` were left out on purpose.

**Backend:** `main.py` (rewritten, same `/health` and `/chat`), new `config.py`, `places.py`, `assistant.py`, `requirements-dev.txt`, `tests/test_backend.py`, `tests/fake_provider_server.py`; updated `.env.example`.

**Other:** `firebase/firestore.rules`, `firebase/firebase.json`, `local.properties.example`, `.gitignore`, `README.md`, `docs/SETUP.md`, `docs/ARCHITECTURE.md`, `docs/PROJECT_DOCUMENTATION.md`, `docs/TEST_CASES.md`, this report.

## 4. Feature checklist

Legend: ✅ implemented & verified by automated tests · 🟡 implemented, verified only by build/review (needs a device or real keys) · ⚙️ implemented, needs your configuration · ❌ not implemented

| Area | Item | Status |
|---|---|---|
| Live places | Nearby/text search, details, photos via backend (Places API New, field masks) | ✅ backend tests + app↔backend contract test (Google faked) · ⚙️ real key |
| | Categories, search, radius, sort, open-now, pull-to-refresh, load more | ✅ logic tests · 🟡 on device |
| | Loading / empty / permission-denied / network / API-error states | ✅ offline fallback in UI test · 🟡 others on device |
| | Curated fallback and 24 h cache, always labelled not live | ✅ |
| | Stable ids, dedupe, permanently closed places hidden | ✅ |
| Location | Rationale card, one-shot location, manual areas, Geocoder search | 🟡 (no device; area selection is UI-tested) |
| Shared data | One repository for Explore/Map/Plan/Chat; show-on-map; add from anywhere | ✅ (Explore→Plan, Chat→Plan tested) |
| Restaurants | Food categories, food detail info, assistant food search, add to plan, favourites | ✅ backend/logic · ⚙️ real key |
| Assistant | Backend-only key, timeouts, retry, model fallback, grounding, invented ids dropped, follow-up flag, suggestions, add-to-plan, offline fallback, minimal context | ✅ · ⚙️ Gemini key |
| Budget | 7 inputs, all calculations, validation, overflow-safe, warning, reset, persistence, INR format, itinerary estimates kept separate | ✅ |
| Quiz | 60 questions, 5 topics, 3 levels, feedback, explanations, progress, results, retry/restart, best scores | ✅ |
| Plan | Days, dates, reorder, move day, notes, costs, undo, navigate day, proximity order, empty state, persistence | ✅ core logic · 🟡 dialogs on device |
| Accounts | Guest mode, email register/login/logout/reset, manual backup/restore, Firestore rules | ✅ guest + "not configured" message · ⚙️ Firebase project |
| | Google sign-in | ❌ (not configurable here; documented as future work) |
| UI | Dark mode, semantic colours, AA-contrast buttons, 48dp targets, content descriptions, scalable text | 🟡 renders reviewed in light and dark (JVM), not on a device |
| Security | No keys in APK, cleartext only in debug, validation, rate limits, daily caps, cache | ✅ |

## 5. Tests and results

Run on 2026-10-27 in a Linux container (Android SDK 36, JDK 25 toolchain, Python 3.13):

| Command | Result |
|---|---|
| `./gradlew clean assembleDebug assembleRelease lintDebug testDebugUnitTest` | **BUILD SUCCESSFUL**. Debug APK and minified release APK (unsigned) built. |
| Android JVM + Robolectric tests | **55 passed, 0 failed** (BudgetCalculator 11, Quiz 9, Itinerary 9, Place 9, Assistant 4, UiSmoke 10, BackendContract 3 against the real backend with faked providers) |
| `lintDebug` | **0 errors**. Remaining warnings: `NotifyDataSetChanged` (simple adapters) and `InsecureBaseConfiguration` (intentional, debug-only LAN HTTP). |
| `cd backend && pytest` | **34 passed** |
| Build with a dummy `google-services.json` | Google Services plugin applied, `FIREBASE_CONFIGURED=true`; builds without it too |
| Mutation check | Mislabelling the curated banner as "Live" made `UiSmokeTest` fail as expected (then reverted) |

Optional extras:
```bash
# End-to-end contract test (no real keys needed)
cd backend && python tests/fake_provider_server.py &      # serves on 127.0.0.1:8765
./gradlew testDebugUnitTest -PcontractBackendUrl=http://127.0.0.1:8765
# PNG renders of the main screens in light and dark (JVM renders, not device screenshots) → app/build/screens
./gradlew testDebugUnitTest --tests '*ScreenRenderTest*' -PrenderScreens=true
```
If Robolectric can't download its Android jar (rate limits), add `-ProbolectricRepoUrl=https://maven-central.storage-download.googleapis.com/maven2/`.

### Not tested (and why)

- **No emulator or phone was available** (no KVM in the build container). None of the device cases D01–D27 in `TEST_CASES.md` were run, including real GPS, the permission dialogs, the Google Map rendering, external Maps intents, TalkBack, and large-font layouts.
- **No real Google Places, Gemini or Firebase calls were made** (no keys; the uploaded ones are leaked and must not be used). Provider behaviour was tested against documented request/response formats with mocks.
- No screenshots of a running device were taken, and none are claimed.

## 6. Known limitations / future work

See `PROJECT_DOCUMENTATION.md` §3 and §10. In short: no route optimisation or travel times, no Google sign-in, backup is manual, curated prices are indicative, and live data costs money once the free allowance is used up.
