"""Backend tests. Google Places and Gemini are replaced with httpx.MockTransport,
so these tests never call a real provider or need real keys."""
import json
import os
import sys

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import assistant  # noqa: E402
import main  # noqa: E402
import places  # noqa: E402

GOOGLE_PLACE = {
    "id": "ChIJ-gateway-0001",
    "displayName": {"text": "Gateway of India"},
    "shortFormattedAddress": "Apollo Bandar, Colaba, Mumbai",
    "location": {"latitude": 18.9220, "longitude": 72.8347},
    "types": ["historical_landmark", "tourist_attraction"],
    "primaryType": "historical_landmark",
    "primaryTypeDisplayName": {"text": "Historical landmark"},
    "rating": 4.6,
    "userRatingCount": 250000,
    "currentOpeningHours": {"openNow": True, "weekdayDescriptions": ["Monday: Open 24 hours"]},
    "photos": [{"name": "places/ChIJ-gateway-0001/photos/AbCdEfGhIjKl",
                "authorAttributions": [{"displayName": "A Photographer"}]}],
    "googleMapsUri": "https://maps.google.com/?cid=1",
    "businessStatus": "OPERATIONAL",
}
RESTAURANT = {
    "id": "ChIJ-food-000002",
    "displayName": {"text": "Sample Veg Kitchen"},
    "formattedAddress": "Colaba Causeway, Mumbai",
    "location": {"latitude": 18.915, "longitude": 72.826},
    "types": ["vegetarian_restaurant", "restaurant", "food"],
    "primaryType": "vegetarian_restaurant",
    "priceLevel": "PRICE_LEVEL_INEXPENSIVE",
    "priceRange": {"startPrice": {"currencyCode": "INR", "units": "200"},
                   "endPrice": {"currencyCode": "INR", "units": "400"}},
    "businessStatus": "OPERATIONAL",
}
CLOSED = {**RESTAURANT, "id": "ChIJ-closed-00003", "businessStatus": "CLOSED_PERMANENTLY"}


class Recorder:
    """Mock transport that records requests and replies from a handler."""

    def __init__(self, handler):
        self.handler = handler
        self.requests = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.handler(request)


@pytest.fixture
def env(monkeypatch):
    monkeypatch.setenv("PLACES_API_KEY", "test-places-key")
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv("GEMINI_MODEL", "model-a")
    monkeypatch.setenv("GEMINI_FALLBACK_MODEL", "model-b")
    monkeypatch.setenv("RATE_LIMIT_PER_MINUTE", "100")
    main.cache.clear()
    main.limiter.reset()
    yield
    main.app.dependency_overrides.clear()


def make_client(handler):
    recorder = Recorder(handler)
    mock = httpx.AsyncClient(transport=httpx.MockTransport(recorder))
    main.app.dependency_overrides[main.get_client] = lambda: mock
    return TestClient(main.app), recorder


def gemini_reply(payload: dict) -> httpx.Response:
    return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(payload)}]}}]})


# --------------------------------------------------------------------------- health & config

def test_health_reports_configuration(env, monkeypatch):
    client, _ = make_client(lambda r: httpx.Response(500))
    body = client.get("/health").json()
    assert body == {"status": "ok", "ai_configured": True, "places_configured": True, "model": "model-a"}
    monkeypatch.delenv("PLACES_API_KEY")
    monkeypatch.delenv("GEMINI_API_KEY")
    body = client.get("/health").json()
    assert body["ai_configured"] is False and body["places_configured"] is False


def test_places_not_configured_returns_503(env, monkeypatch):
    monkeypatch.delenv("PLACES_API_KEY")
    client, rec = make_client(lambda r: httpx.Response(200, json={}))
    res = client.get("/places/nearby", params={"lat": 18.9, "lng": 72.8})
    assert res.status_code == 503
    assert res.json()["detail"]["kind"] == "places_not_configured"
    assert rec.requests == []


# --------------------------------------------------------------------------- places

def test_nearby_normalizes_dedupes_and_hides_closed(env):
    client, rec = make_client(lambda r: httpx.Response(
        200, json={"places": [GOOGLE_PLACE, GOOGLE_PLACE, RESTAURANT, CLOSED, {"id": "broken"}]}))
    res = client.get("/places/nearby", params={"lat": 18.92, "lng": 72.83, "radius": 2000,
                                                "category": "attractions"})
    assert res.status_code == 200
    data = res.json()
    assert [p["id"] for p in data["places"]] == ["ChIJ-gateway-0001", "ChIJ-food-000002"]
    gateway, food = data["places"]
    assert gateway["rating"] == 4.6 and gateway["rating_count"] == 250000
    assert gateway["open_now"] is True and gateway["is_food"] is False
    assert gateway["photo"]["attributions"] == ["A Photographer"]
    assert food["is_food"] is True
    assert food["price_range"] == {"currency": "INR", "start": 200, "end": 400}
    assert "rating" in food and food["rating"] is None  # missing fields stay missing, never invented

    sent = rec.requests[0]
    assert sent.url.path == "/v1/places:searchNearby"
    assert sent.headers["X-Goog-Api-Key"] == "test-places-key"
    assert "places.rating" in sent.headers["X-Goog-FieldMask"]
    assert "reviews" not in sent.headers["X-Goog-FieldMask"]  # no Atmosphere SKU fields
    body = json.loads(sent.content)
    assert body["includedTypes"] == places.CATEGORY_TYPES["attractions"]
    assert body["locationRestriction"]["circle"]["radius"] == 2000.0


def test_nearby_uses_cache_for_repeat_requests(env):
    client, rec = make_client(lambda r: httpx.Response(200, json={"places": [GOOGLE_PLACE]}))
    params = {"lat": 18.92, "lng": 72.83}
    first = client.get("/places/nearby", params=params).json()
    second = client.get("/places/nearby", params=params).json()
    assert first["cached"] is False and second["cached"] is True
    assert len(rec.requests) == 1


@pytest.mark.parametrize("params", [
    {"lat": 91, "lng": 72.8},
    {"lat": 18.9, "lng": 181},
    {"lat": 18.9, "lng": 72.8, "radius": 50},
    {"lat": 18.9, "lng": 72.8, "radius": 60000},
    {"lat": 18.9, "lng": 72.8, "category": "casinos"},
])
def test_nearby_rejects_invalid_input(env, params):
    client, rec = make_client(lambda r: httpx.Response(200, json={}))
    assert client.get("/places/nearby", params=params).status_code == 422
    assert rec.requests == []


@pytest.mark.parametrize("status,kind,expected", [
    (429, "quota", 429),
    (403, "provider_denied", 503),
    (500, "provider_error", 502),
    (400, "provider_rejected", 502),
])
def test_provider_errors_are_mapped(env, status, kind, expected):
    client, _ = make_client(lambda r: httpx.Response(status, json={"error": {"message": "x"}}))
    res = client.get("/places/nearby", params={"lat": 18.9, "lng": 72.8})
    assert res.status_code == expected
    assert res.json()["detail"]["kind"] == kind


def test_timeout_is_mapped_to_504(env):
    def boom(request):
        raise httpx.ReadTimeout("slow", request=request)
    client, _ = make_client(boom)
    res = client.get("/places/nearby", params={"lat": 18.9, "lng": 72.8})
    assert res.status_code == 504 and res.json()["detail"]["kind"] == "timeout"


def test_text_search_sends_bias_open_now_and_returns_token(env):
    client, rec = make_client(lambda r: httpx.Response(
        200, json={"places": [RESTAURANT], "nextPageToken": "next123"}))
    res = client.get("/places/search", params={"q": "  veg   thali ", "lat": 18.92, "lng": 72.83,
                                                "category": "restaurants", "open_now": "true"})
    assert res.status_code == 200
    assert res.json()["next_page_token"] == "next123"
    body = json.loads(rec.requests[0].content)
    assert body["textQuery"] == "veg thali"
    assert body["openNow"] is True and body["includedType"] == "restaurant"
    assert body["locationBias"]["circle"]["center"] == {"latitude": 18.92, "longitude": 72.83}
    assert "nextPageToken" in rec.requests[0].headers["X-Goog-FieldMask"]


def test_details_validates_id_and_maps_not_found(env):
    client, _ = make_client(lambda r: httpx.Response(404, json={"error": {"message": "gone"}}))
    assert client.get("/places/details/bad id!").status_code == 422
    res = client.get("/places/details/ChIJ-gateway-0001")
    assert res.status_code == 404 and res.json()["detail"]["kind"] == "not_found"


def test_photo_returns_uri_without_key(env):
    client, rec = make_client(lambda r: httpx.Response(
        200, json={"name": "x", "photoUri": "https://lh3.googleusercontent.com/photo"}))
    res = client.get("/places/photo", params={"name": "places/ChIJ-gateway-0001/photos/AbCdEfGhIjKl"})
    assert res.status_code == 200
    assert res.json() == {"uri": "https://lh3.googleusercontent.com/photo"}
    assert rec.requests[0].url.params["skipHttpRedirect"] == "true"
    assert client.get("/places/photo", params={"name": "../../etc/passwd"}).status_code == 422


def test_rate_limit_per_client(env, monkeypatch):
    monkeypatch.setenv("RATE_LIMIT_PER_MINUTE", "2")
    client, _ = make_client(lambda r: httpx.Response(200, json={"places": []}))
    codes = [client.get("/places/search", params={"q": f"query {i}"}).status_code for i in range(3)]
    assert codes == [200, 200, 429]


# --------------------------------------------------------------------------- chat

def test_chat_requires_gemini_key(env, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY")
    client, rec = make_client(lambda r: httpx.Response(200, json={}))
    res = client.post("/chat", json={"message": "hello"})
    assert res.status_code == 503 and res.json()["detail"]["kind"] == "ai_not_configured"
    assert rec.requests == []


def test_chat_rejects_bad_input(env):
    client, _ = make_client(lambda r: httpx.Response(200, json={}))
    assert client.post("/chat", json={"message": ""}).status_code == 422
    assert client.post("/chat", json={"message": "x" * 1501}).status_code == 422
    assert client.post("/chat", json={"message": "hi", "location": {"lat": 100, "lng": 0}}).status_code == 422


def test_chat_grounds_place_ids_and_rounds_location(env):
    def handler(request):
        assert "generativelanguage" in request.url.host
        return gemini_reply({"reply": "Visit the Gateway.",
                             "place_ids": ["curated:gateway", "made-up-id", "curated:gateway"]})
    client, rec = make_client(handler)
    res = client.post("/chat", json={
        "message": "What should I see?",
        "location": {"lat": 18.92234, "lng": 72.83471, "label": "Colaba"},
        "places": [{"id": "curated:gateway", "name": "Gateway of India", "source": "curated"}],
        "budget": {"total": 5000, "days": 2},
    })
    assert res.status_code == 200
    data = res.json()
    assert data["reply"] == "Visit the Gateway."
    assert [p["id"] for p in data["places"]] == ["curated:gateway"]  # invented id dropped
    sent = json.loads(rec.requests[0].content)
    context_text = sent["contents"][-1]["parts"][0]["text"]
    assert '"lat": 18.92' in context_text and "18.92234" not in context_text
    assert sent["generationConfig"]["responseMimeType"] == "application/json"
    assert len(rec.requests) == 1  # not a food question -> no Places call


def test_chat_food_question_fetches_live_restaurants(env):
    def handler(request):
        if request.url.host == "places.googleapis.com":
            return httpx.Response(200, json={"places": [RESTAURANT]})
        return gemini_reply({"reply": "Try Sample Veg Kitchen.", "place_ids": ["ChIJ-food-000002"]})
    client, rec = make_client(handler)
    res = client.post("/chat", json={"message": "Find vegetarian restaurants open now near me",
                                     "location": {"lat": 18.92, "lng": 72.83}})
    assert res.status_code == 200
    assert [p["name"] for p in res.json()["places"]] == ["Sample Veg Kitchen"]
    places_body = json.loads(rec.requests[0].content)
    assert places_body["textQuery"] == "vegetarian restaurant" and places_body["openNow"] is True


def test_chat_food_question_without_location_skips_places(env):
    client, rec = make_client(lambda r: gemini_reply(
        {"reply": "Which area are you in?", "place_ids": [], "needs_more_info": True}))
    res = client.post("/chat", json={"message": "restaurants near me under 500"})
    assert res.json()["needs_more_info"] is True
    assert all(r.url.host != "places.googleapis.com" for r in rec.requests)
    assert "No location was shared" in rec.requests[0].content.decode()


def test_chat_falls_back_to_second_model_when_busy(env):
    def handler(request):
        if "model-a" in request.url.path:
            return httpx.Response(503, json={"error": {"message": "overloaded"}})
        return gemini_reply({"reply": "ok", "place_ids": []})
    client, rec = make_client(handler)
    res = client.post("/chat", json={"message": "hello"})
    assert res.status_code == 200 and res.json()["model"] == "model-b"
    assert len(rec.requests) == 2


def test_chat_quota_error_is_reported(env):
    client, _ = make_client(lambda r: httpx.Response(429, json={"error": {"message": "quota"}}))
    res = client.post("/chat", json={"message": "hello"})
    assert res.status_code == 429 and res.json()["detail"]["kind"] == "quota"


def test_chat_handles_non_json_model_text(env):
    client, _ = make_client(lambda r: httpx.Response(
        200, json={"candidates": [{"content": {"parts": [{"text": "plain answer"}]}}]}))
    res = client.post("/chat", json={"message": "hello"})
    assert res.status_code == 200
    assert res.json()["reply"] == "plain answer" and res.json()["places"] == []


# --------------------------------------------------------------------------- unit helpers

@pytest.mark.parametrize("message,query,open_now,near_selected", [
    ("Find restaurants near me under ₹500", "restaurant", False, False),
    ("Find vegetarian restaurants nearby", "vegetarian restaurant", False, False),
    ("Suggest cafés close to my selected attraction", "cafe", False, True),
    ("Find restaurants that are currently open", "restaurant", True, False),
    ("Plan a Mumbai day trip with a lunch stop", "restaurant", False, False),
])
def test_detect_food_intent(message, query, open_now, near_selected):
    intent = assistant.detect_food_intent(message)
    assert intent == {"query": query, "open_now": open_now, "near_selected": near_selected}


def test_detect_food_intent_ignores_sightseeing():
    assert assistant.detect_food_intent("Which museums should I visit?") is None
    assert assistant.detect_food_intent("Tell me about Elephanta Caves") is None


def test_rate_limiter_daily_cap():
    clock = [1_000_000.0]
    limiter = main.RateLimiter(clock=lambda: clock[0])
    limiter.check("a", 100, "places", 2)
    limiter.check("b", 100, "places", 2)
    with pytest.raises(Exception):
        limiter.check("c", 100, "places", 2)
    clock[0] += 86400  # next day resets the cap
    limiter.check("c", 100, "places", 2)


def test_ttl_cache_expires():
    clock = [0.0]
    cache = main.TtlCache(clock=lambda: clock[0])
    cache.put("k", 1)
    assert cache.get("k", 10) == 1
    clock[0] = 11
    assert cache.get("k", 10) is None
