# Tourist Guide MAD — Android App

A native Android app for exploring and planning trips around Mumbai: a searchable attraction catalog, an embedded Google Map with pins and live location, a trip planner with budget/time estimates, saved favourites, and an AI travel assistant powered directly by Gemini. Java + native Android Views (Material 3), no third-party UI frameworks.

## Features

- **Explore tab** — hero banner, live search (filters as you type, keeps focus), interest filter chips (All / Historic / Beach / Museum / Shopping / Nature), a "Surprise me" button, and an interactive Mumbai mini-quiz with persisted progress.
- **Map tab** — embedded Google Maps SDK (SupportMapFragment): a pin for every attraction, info window opens the detail sheet, an auto-fitted camera, and a locate-me FAB with runtime location permission. If no Maps API key is configured the tab degrades gracefully to a "open in Google Maps app" panel.
- **Detail sheet** — bottom sheet per attraction with description, min-visit time, indicative price, area, plus actions: get directions, save/unsave, add to plan.
- **Plan tab** — ordered itinerary with total time & budget estimates, "reorder by area", clear-all with confirmation dialog, and "open the full day in Maps" (external Maps with waypoints).
- **Saved places** — horizontal card strip on the More tab; hearts sync everywhere instantly.
- **Chat tab** — real AI from Gemini, called directly by the app (loading state, timeouts, friendly errors). No backend server to run. If Gemini is unreachable the app falls back to a built-in offline suggestion with a Retry button.
- **More tab** — explorer profile with earned badges (Local Curator, Trip Planner), travel-personality picks that can be applied as a plan, sample food stops, an offline city guide, and the active Gemini model.
- Everything user-facing lives in `res/values/strings.xml`; favourites and plan persist across restarts via SharedPreferences.

## What's in the box

```
app/          Android application (Java, Views, Material 3, ViewBinding)
backend/      Legacy optional FastAPI proxy (not used by the app)
docs/         Project documentation and test plan
```

## Android app setup

1. Open the `TouristGuideMAD` root folder in Android Studio (JDK 17+), let Gradle sync (first sync needs internet).
2. Run on an API 24+ emulator or device.

Builds are available via `./gradlew assembleDebug` (wrapper included). Gradle 9.3.1 + AGP 9.1.1, `compileSdk` 36, `targetSdk` 36, `minSdk` 24.

## Embedded map (Google Maps SDK)

The map needs a free key. This project wires it through `local.properties` so **no key is committed**:

1. Get a key: [Google AI Studio / Maps Console](https://console.cloud.google.com/google/maps-apis/overview) — enable **Maps SDK for Android**.
2. Add one line to `local.properties` in the project root:
   ```properties
   MAPS_API_KEY=AIza...your_key...
   ```
3. Rebuild. Without a key the Map tab shows a friendly fallback panel instead of a blank map (key is detected at runtime; the placeholder is empty by default).

## Enable the AI assistant

The app calls the Gemini API directly, so there is **no backend server to run**. The key is read from `local.properties` at build time and embedded in the APK.

1. Add these lines to `local.properties` in the project root:
   ```properties
   GEMINI_API_KEY=your_gemini_key
   GEMINI_MODEL=gemini-3.1-flash-lite
   ```
2. Rebuild and install:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. The Chat tab now works on any Android device with internet access. There is no server address to configure. If the configured model is busy or retired, the app automatically falls back to `gemini-3-flash-preview`.

A ready-to-install standalone APK is also kept at `prebuilt/TouristGuide-debug.apk`.

**Security note:** because this is a standalone college project, the Gemini key is inside the APK and anyone who has the APK can extract it. That is fine for a local demo, but do not use this pattern for a public production app — use a server-side proxy or Firebase AI Logic with App Check instead.

## Honest limits

- Attraction names/descriptions are a starter catalogue with indicative "min visit" and price figures — not live listings. Always re-check hours/tickets from official sources (the app says so in-UI too).
- Meals/travel time are excluded from plan estimates.
- The AI only answers as well as your Gemini key; when it is unavailable the app clearly labels the response as an offline suggestion.
