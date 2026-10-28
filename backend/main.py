"""Tourist Guide backend.

Keeps every provider key on the server:
  * Google Places API (New) proxy for Explore / Map / Plan / Chat (PLACES_API_KEY)
  * Grounded Gemini travel assistant (GEMINI_API_KEY)

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import logging
import threading
import time
from contextlib import asynccontextmanager
from typing import Literal, Optional

import httpx
from fastapi import Depends, FastAPI, HTTPException, Path, Query, Request
from pydantic import BaseModel, Field

import assistant
import places
from config import Settings, describe_key, get_settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("backend")

CategoryKey = Literal["all", "attractions", "restaurants", "cafes", "historic", "beach",
                      "museum", "shopping", "nature"]
PLACE_ID_PATTERN = r"^[A-Za-z0-9_-]{10,300}$"
PHOTO_NAME_PATTERN = r"^places/[A-Za-z0-9_-]{10,300}/photos/[A-Za-z0-9_-]{10,1000}$"


# --------------------------------------------------------------------------- infrastructure

class TtlCache:
    """Small in-memory TTL cache so repeated identical searches don't cost quota."""

    def __init__(self, max_entries: int = 300, clock=time.monotonic):
        self._data: dict[str, tuple[float, object]] = {}
        self._lock = threading.Lock()
        self._max = max_entries
        self._clock = clock

    def get(self, key: str, ttl: float):
        with self._lock:
            hit = self._data.get(key)
            if hit and self._clock() - hit[0] < ttl:
                return hit[1]
            self._data.pop(key, None)
            return None

    def put(self, key: str, value) -> None:
        with self._lock:
            if len(self._data) >= self._max:
                oldest = min(self._data, key=lambda k: self._data[k][0])
                self._data.pop(oldest, None)
            self._data[key] = (self._clock(), value)

    def clear(self) -> None:
        with self._lock:
            self._data.clear()


class RateLimiter:
    """Per-client requests/minute plus global daily caps that protect the billing account."""

    def __init__(self, clock=time.time):
        self._clock = clock
        self._lock = threading.Lock()
        self._windows: dict[str, list[float]] = {}
        self._day = ""
        self._daily: dict[str, int] = {}

    def check(self, client_id: str, per_minute: int, bucket: str, daily_limit: int) -> None:
        now = self._clock()
        with self._lock:
            window = [t for t in self._windows.get(client_id, []) if now - t < 60]
            if len(window) >= per_minute:
                raise HTTPException(429, {"kind": "rate_limited",
                                          "message": "Too many requests. Wait a minute and try again."})
            day = time.strftime("%Y-%m-%d", time.gmtime(now))
            if day != self._day:
                self._day, self._daily = day, {}
            if daily_limit and self._daily.get(bucket, 0) >= daily_limit:
                raise HTTPException(429, {"kind": "daily_limit",
                                          "message": "The server's daily request limit was reached. Try again tomorrow."})
            window.append(now)
            self._windows[client_id] = window
            self._daily[bucket] = self._daily.get(bucket, 0) + 1

    def reset(self) -> None:
        with self._lock:
            self._windows.clear()
            self._daily.clear()


cache = TtlCache()
limiter = RateLimiter()
_http: Optional[httpx.AsyncClient] = None


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global _http
    _http = httpx.AsyncClient(timeout=httpx.Timeout(20.0, connect=8.0))
    settings = get_settings()
    log.info("Gemini key %s, model %s (fallback %s); Places key %s",
             describe_key(settings.gemini_api_key), settings.gemini_model,
             settings.gemini_fallback_model or "none", describe_key(settings.places_api_key))
    yield
    await _http.aclose()


app = FastAPI(title="Tourist Guide backend", version="3.1.0", lifespan=lifespan)


def get_client() -> httpx.AsyncClient:
    if _http is None:  # pragma: no cover - only when lifespan did not run
        raise HTTPException(503, {"kind": "starting", "message": "Server is starting."})
    return _http


def _client_id(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _places_key(settings: Settings) -> str:
    if not settings.places_configured:
        raise HTTPException(503, {"kind": "places_not_configured",
                                  "message": "Live places are not configured on the server (PLACES_API_KEY)."})
    return settings.places_api_key


def _raise(exc: places.PlacesError):
    raise HTTPException(exc.status, {"kind": exc.kind, "message": exc.message})


# --------------------------------------------------------------------------- endpoints

@app.get("/")
def root():
    return {"name": "Tourist Guide backend", "docs": "/docs", "health": "/health"}


@app.get("/health")
def health(settings: Settings = Depends(get_settings)):
    return {
        "status": "ok",
        "ai_configured": settings.ai_configured,
        "places_configured": settings.places_configured,
        "model": settings.gemini_model if settings.ai_configured else None,
    }


@app.get("/places/nearby")
async def places_nearby(
        request: Request,
        lat: float = Query(ge=-90, le=90),
        lng: float = Query(ge=-180, le=180),
        radius: int = Query(2000, ge=100, le=50000),
        category: CategoryKey = "all",
        rank: Literal["popularity", "distance"] = "popularity",
        settings: Settings = Depends(get_settings),
        client: httpx.AsyncClient = Depends(get_client)):
    key = _places_key(settings)
    # ~110 m grid so nearby repeat requests share a cache entry.
    cache_key = f"nearby:{lat:.3f}:{lng:.3f}:{radius}:{category}:{rank}"
    cached = cache.get(cache_key, settings.places_cache_seconds)
    if cached is not None:
        return {**cached, "cached": True}
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "places", settings.daily_places_limit)
    try:
        results = await places.search_nearby(client, key, lat, lng, radius, category, rank)
    except places.PlacesError as exc:
        _raise(exc)
    body = {"places": results, "next_page_token": None, "provider": "google_places"}
    cache.put(cache_key, body)
    return {**body, "cached": False}


@app.get("/places/search")
async def places_search(
        request: Request,
        q: str = Query(min_length=2, max_length=120),
        lat: Optional[float] = Query(None, ge=-90, le=90),
        lng: Optional[float] = Query(None, ge=-180, le=180),
        radius: int = Query(5000, ge=100, le=50000),
        category: CategoryKey = "all",
        open_now: bool = False,
        page_token: Optional[str] = Query(None, max_length=1000),
        settings: Settings = Depends(get_settings),
        client: httpx.AsyncClient = Depends(get_client)):
    key = _places_key(settings)
    query = " ".join(q.split())
    if len(query) < 2:
        raise HTTPException(422, {"kind": "bad_request", "message": "Search text is too short."})
    loc = f"{lat:.3f}:{lng:.3f}" if lat is not None and lng is not None else "none"
    cache_key = f"search:{query.lower()}:{loc}:{radius}:{category}:{open_now}:{page_token or ''}"
    cached = cache.get(cache_key, settings.places_cache_seconds)
    if cached is not None:
        return {**cached, "cached": True}
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "places", settings.daily_places_limit)
    try:
        results, token = await places.search_text(client, key, query, lat, lng, radius,
                                                  category, open_now, page_token)
    except places.PlacesError as exc:
        _raise(exc)
    body = {"places": results, "next_page_token": token, "provider": "google_places"}
    cache.put(cache_key, body)
    return {**body, "cached": False}


@app.get("/places/locate")
async def places_locate(
        request: Request,
        q: str = Query(min_length=2, max_length=120),
        lat: float = Query(places.MUMBAI_CENTRE[0], ge=-90, le=90),
        lng: float = Query(places.MUMBAI_CENTRE[1], ge=-180, le=180),
        settings: Settings = Depends(get_settings),
        client: httpx.AsyncClient = Depends(get_client)):
    """Accurate coordinates for an area or landmark the user typed (up to 5 matches)."""
    key = _places_key(settings)
    query = " ".join(q.split())
    if len(query) < 2:
        raise HTTPException(422, {"kind": "bad_request", "message": "Search text is too short."})
    cache_key = f"locate:{query.lower()}:{lat:.2f}:{lng:.2f}"
    cached = cache.get(cache_key, 24 * 3600)  # places don't move; a day is safe
    if cached is not None:
        return {**cached, "cached": True}
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "places", settings.daily_places_limit)
    try:
        results = await places.find_locations(client, key, query, lat, lng)
    except places.PlacesError as exc:
        _raise(exc)
    body = {"results": results, "provider": "google_places"}
    cache.put(cache_key, body)
    return {**body, "cached": False}


@app.get("/places/details/{place_id}")
async def place_details(
        request: Request,
        place_id: str = Path(pattern=PLACE_ID_PATTERN),
        settings: Settings = Depends(get_settings),
        client: httpx.AsyncClient = Depends(get_client)):
    key = _places_key(settings)
    cache_key = f"details:{place_id}"
    cached = cache.get(cache_key, settings.places_cache_seconds)
    if cached is not None:
        return cached
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "places", settings.daily_places_limit)
    try:
        place = await places.get_details(client, key, place_id)
    except places.PlacesError as exc:
        _raise(exc)
    body = {"place": place, "provider": "google_places"}
    cache.put(cache_key, body)
    return body


@app.get("/places/photo")
async def place_photo(
        request: Request,
        name: str = Query(pattern=PHOTO_NAME_PATTERN, max_length=1400),
        max_width: int = Query(800, ge=100, le=1600),
        settings: Settings = Depends(get_settings),
        client: httpx.AsyncClient = Depends(get_client)):
    key = _places_key(settings)
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "places", settings.daily_places_limit)
    try:
        uri = await places.get_photo_uri(client, key, name, max_width)
    except places.PlacesError as exc:
        _raise(exc)
    # The returned URI does not contain the API key.
    return {"uri": uri}


# --------------------------------------------------------------------------- chat

class LocationIn(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    label: Optional[str] = Field(None, max_length=120)


class PriceRangeIn(BaseModel):
    currency: Optional[str] = Field(None, max_length=8)
    start: Optional[int] = Field(None, ge=0, le=10_000_000)
    end: Optional[int] = Field(None, ge=0, le=10_000_000)


class PlaceIn(BaseModel):
    id: str = Field(min_length=1, max_length=320)
    name: str = Field(min_length=1, max_length=200)
    category_label: Optional[str] = Field(None, max_length=120)
    address: Optional[str] = Field(None, max_length=300)
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)
    rating: Optional[float] = Field(None, ge=0, le=5)
    rating_count: Optional[int] = Field(None, ge=0)
    price_level: Optional[str] = Field(None, max_length=40)
    price_range: Optional[PriceRangeIn] = None
    open_now: Optional[bool] = None
    distance_m: Optional[int] = Field(None, ge=0, le=25_000_000)
    is_food: bool = False
    source: Literal["live", "curated"] = "curated"


class PlanStopIn(BaseModel):
    id: Optional[str] = Field(None, max_length=320)
    name: str = Field(min_length=1, max_length=200)
    day: int = Field(1, ge=1, le=31)


class BudgetIn(BaseModel):
    total: Optional[int] = Field(None, ge=0, le=10_000_000_000)
    days: Optional[int] = Field(None, ge=1, le=365)
    planned: Optional[int] = Field(None, ge=0, le=50_000_000_000)
    remaining: Optional[int] = Field(None, ge=-50_000_000_000, le=10_000_000_000)
    food: Optional[int] = Field(None, ge=0, le=10_000_000_000)


class TurnIn(BaseModel):
    role: Literal["user", "assistant"]
    text: str = Field(min_length=1, max_length=2000)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1500)
    city: str = Field(default="Mumbai", max_length=80)
    location: Optional[LocationIn] = None
    selected_place: Optional[PlaceIn] = None
    places: list[PlaceIn] = Field(default_factory=list, max_length=30)
    plan: list[PlanStopIn] = Field(default_factory=list, max_length=40)
    budget: Optional[BudgetIn] = None
    history: list[TurnIn] = Field(default_factory=list, max_length=8)


class ChatResponse(BaseModel):
    reply: str
    mode: str
    model: Optional[str] = None
    needs_more_info: bool = False
    places: list[dict] = Field(default_factory=list)


@app.post("/chat", response_model=ChatResponse)
async def chat(body: ChatRequest, request: Request,
               settings: Settings = Depends(get_settings),
               client: httpx.AsyncClient = Depends(get_client)):
    message = body.message.strip()
    if not message:
        raise HTTPException(422, {"kind": "bad_request", "message": "Type a question first."})
    if not settings.ai_configured:
        raise HTTPException(503, {"kind": "ai_not_configured",
                                  "message": "AI is not configured on the server (GEMINI_API_KEY)."})
    limiter.check(_client_id(request), settings.rate_limit_per_minute, "chat", settings.daily_chat_limit)

    data = body.model_dump()
    data["message"] = message
    candidates: list[dict] = [p.model_dump() for p in body.places]
    notes: list[str] = []

    # Restaurant-style questions: fetch real food places around the user (or their selected stop).
    intent = assistant.detect_food_intent(message)
    if intent:
        origin = None
        if intent["near_selected"] and body.selected_place and body.selected_place.lat is not None:
            origin = (body.selected_place.lat, body.selected_place.lng)
        elif body.location:
            origin = (body.location.lat, body.location.lng)
        if origin is None:
            notes.append("No location was shared, so no live restaurant search was run.")
        elif not settings.places_configured:
            notes.append("Live restaurant search is not configured; only the listed places are available.")
        else:
            try:
                limiter.check(_client_id(request), settings.rate_limit_per_minute, "places",
                              settings.daily_places_limit)
                food, _ = await places.search_text(client, settings.places_api_key, intent["query"],
                                                   origin[0], origin[1], 3000, "all",
                                                   intent["open_now"], None, page_size=10)
                known = {p["id"] for p in candidates}
                for place in food:
                    if place["id"] not in known:
                        candidates.append(place)
                        known.add(place["id"])
                notes.append(f"Live Google Places search '{intent['query']}' within 3 km returned "
                             f"{len(food)} places" + (" (open now only)." if intent["open_now"] else "."))
            except (places.PlacesError, HTTPException) as exc:
                log.info("Food search skipped: %s", getattr(exc, "kind", exc))
                notes.append("Live restaurant search failed; only the listed places are available.")

    context = assistant.build_context(data, candidates, notes)
    payload = assistant.build_payload(data, context)
    models = [settings.gemini_model]
    if settings.gemini_fallback_model and settings.gemini_fallback_model not in models:
        models.append(settings.gemini_fallback_model)
    try:
        result, model = await assistant.call_gemini(client, settings.gemini_api_key, models, payload)
    except assistant.AssistantError as exc:
        raise HTTPException(exc.status, {"kind": exc.kind, "message": exc.message})

    return ChatResponse(
        reply=result["reply"],
        mode="gemini",
        model=model,
        needs_more_info=result["needs_more_info"],
        places=assistant.grounded_places(result["place_ids"], candidates),
    )
