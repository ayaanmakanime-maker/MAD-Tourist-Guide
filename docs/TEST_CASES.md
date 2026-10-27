# Tourist Guide MAD — Test Case Plan and Results Template

**Important:** These are designed test cases, not claimed execution results. Run them on an emulator and a physical Android device and fill in Actual Result / Status from observed behavior before submission.

| ID | Type | Scenario / Input | Expected result | Actual result | Status |
|---|---|---|---|---|---|
| TC-P01 | Positive | Launch app with valid install | Explore opens on Explore tab with hero, chips and quiz card; no crash | Pending device run | Not run |
| TC-P02 | Positive | Type `Marine Drive` into search | Results filter live while typing; keyboard/focus stays intact | Pending device run | Not run |
| TC-P03 | Positive | Tap any attraction card | Detail sheet shows description, area, duration, price stats | Pending device run | Not run |
| TC-P04 | Positive | Tap heart on an attraction, close app, relaunch | Saved strip shows the place; heart stays filled | Pending device run | Not run |
| TC-P05 | Positive | Add three attractions to plan, open Plan tab | Ordered list with time & budget totals | Pending device run | Not run |
| TC-P06 | Positive | Tap locate-me on Map with permission granted | Camera animates to current position; blue dot appears | Pending device run | Not run |
| TC-P07 | Positive | With MAPS_API_KEY configured, open Map tab | Embedded map renders with all pins; info window opens detail sheet | Pending device run | Not run |
| TC-P08 | Positive | Start backend with GEMINI_API_KEY, send chat message | Loading bubble replaced by AI answer | Pending device run | Not run |
| TC-P09 | Positive | Tap "Open the full day in Maps" with 3+ stops | External Maps opens with destination + waypoints | Pending device run | Not run |
| TC-P10 | Positive | Save 3 places | Local Curator badge shows Earned | Pending device run | Not run |
| TC-N01 | Negative | Search `zzz` | Empty-state message appears; app remains usable | Pending device run | Not run |
| TC-N02 | Negative | Tap send with blank chat input | Inline validation hint, no network call | Pending device run | Not run |
| TC-N03 | Negative | AI backend stopped/is without key | Offline suggestion bubble with Retry appears; no crash | Pending device run | Not run |
| TC-N04 | Negative | Tap Add to plan on the same detail twice | Second tap shows "Already in your plan" toast; single stop in plan | Pending device run | Not run |
| TC-N05 | Negative | Open external Maps route without a Maps app | Toast "No maps app available"; no crash | Pending device run | Not run |
| TC-N06 | Negative | Open Map tab with no MAPS_API_KEY | Fallback panel instead of blank map; "Open in Google Maps" button works | Pending device run | Not run |
| TC-N07 | Negative | Deny location permission, tap locate-me | Explaining toast; map still usable | Pending device run | Not run |
| TC-N08 | Negative | Confirm Clear plan dialog | Plan emptied; empty state visible | Pending device run | Not run |

## Execution evidence checklist
- [ ] Android emulator model and Android/API version recorded
- [ ] Physical Android device model and Android version recorded
- [ ] Screenshot evidence for core flows
- [ ] Actual results and Pass/Fail updated for all cases
- [ ] Crashes and runtime logs reviewed
- [ ] Retest after fixes
