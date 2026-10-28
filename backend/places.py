"""Google Places API (New) client used by the backend.

Only the fields the app actually displays are requested (field masks keep
billing predictable). Results are normalised into one flat JSON shape that the
Android app parses for Explore, Map, Plan and Chat alike.
"""
from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

log = logging.getLogger("places")

BASE_URL = "https://places.googleapis.com/v1"

# App category key -> Places (New) Table A types. The backend owns this mapping so
# clients cannot request arbitrary (and arbitrarily expensive) searches.
CATEGORY_TYPES: dict[str, list[str]] = {
    "all": ["tourist_attraction", "historical_landmark", "museum", "park", "beach",
            "restaurant", "cafe"],
    "attractions": ["tourist_attraction", "historical_landmark", "museum", "art_gallery",
                    "park", "beach", "zoo", "aquarium", "amusement_park", "monument",
                    "cultural_landmark"],
    "restaurants": ["restaurant"],
    "cafes": ["cafe", "coffee_shop", "bakery"],
    "historic": ["historical_landmark", "monument", "cultural_landmark", "museum"],
    "beach": ["beach"],
    "museum": ["museum", "art_gallery"],
    "shopping": ["shopping_mall", "market"],
    "nature": ["park", "national_park", "beach"],
}

# Types used for Text Search `includedType` (a single type is allowed there).
CATEGORY_TEXT_TYPE: dict[str, Optional[str]] = {
    "all": None,
    "attractions": "tourist_attraction",
    "restaurants": "restaurant",
    "cafes": "cafe",
    "historic": "historical_landmark",
    "beach": "beach",
    "museum": "museum",
    "shopping": "shopping_mall",
    "nature": "park",
}

FOOD_TYPES = {
    "restaurant", "cafe", "coffee_shop", "bakery", "meal_takeaway", "meal_delivery",
    "food", "bar", "fast_food_restaurant", "vegetarian_restaurant", "vegan_restaurant",
    "indian_restaurant", "food_court", "ice_cream_shop", "dessert_shop", "tea_house",
}

# Pro + Enterprise fields only; no Atmosphere fields (reviews, servesVegetarianFood…).
PLACE_FIELDS = [
    "id", "displayName", "formattedAddress", "shortFormattedAddress", "location", "types",
    "primaryType", "primaryTypeDisplayName", "rating", "userRatingCount", "priceLevel",
    "priceRange", "currentOpeningHours", "photos", "googleMapsUri", "businessStatus",
]
SEARCH_FIELD_MASK = ",".join("places." + f for f in PLACE_FIELDS)
# Area lookup only needs a name, an address and coordinates.
LOCATE_FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location,places.types"
MUMBAI_CENTRE = (19.0760, 72.8777)
DETAILS_FIELD_MASK = ",".join(PLACE_FIELDS)


class PlacesError(Exception):
    """A provider failure translated into an HTTP status + machine-readable kind."""

    def __init__(self, status: int, kind: str, message: str):
        super().__init__(message)
        self.status = status
        self.kind = kind
        self.message = message


def _price_units(money: Any) -> Optional[int]:
    if not isinstance(money, dict):
        return None
    if money.get("units") is None:
        return None
    try:
        return int(money["units"])
    except (TypeError, ValueError):
        return None


def normalize_place(raw: dict) -> Optional[dict]:
    """Flatten a Places (New) place. Returns None for unusable or permanently closed places."""
    if not isinstance(raw, dict):
        return None
    place_id = raw.get("id")
    location = raw.get("location") or {}
    lat, lng = location.get("latitude"), location.get("longitude")
    name = (raw.get("displayName") or {}).get("text")
    if not place_id or not name or lat is None or lng is None:
        return None
    if raw.get("businessStatus") == "CLOSED_PERMANENTLY":
        return None

    types = [t for t in raw.get("types") or [] if isinstance(t, str)]
    primary = raw.get("primaryType")
    hours = raw.get("currentOpeningHours") or {}
    price_range = raw.get("priceRange") or {}
    start = price_range.get("startPrice") or {}
    end = price_range.get("endPrice") or {}
    start_units, end_units = _price_units(start), _price_units(end)
    if end_units is not None and (end_units <= 0 or (start_units is not None and end_units < start_units)):
        end_units = None  # open-ended range ("from ₹2,000"), not "₹2,000–₹0"

    photo = None
    photos = raw.get("photos") or []
    if photos and isinstance(photos[0], dict) and photos[0].get("name"):
        attributions = [a.get("displayName") for a in photos[0].get("authorAttributions") or []
                        if isinstance(a, dict) and a.get("displayName")]
        photo = {"name": photos[0]["name"], "attributions": attributions}

    return {
        "id": place_id,
        "name": name,
        "address": raw.get("shortFormattedAddress") or raw.get("formattedAddress") or "",
        "lat": float(lat),
        "lng": float(lng),
        "types": types,
        "primary_type": primary,
        "category_label": (raw.get("primaryTypeDisplayName") or {}).get("text"),
        "rating": raw.get("rating"),
        "rating_count": raw.get("userRatingCount"),
        "price_level": raw.get("priceLevel"),
        "price_range": {
            "currency": start.get("currencyCode") or end.get("currencyCode"),
            "start": start_units,
            "end": end_units,
        } if (start or end) else None,
        "open_now": hours.get("openNow"),
        "hours": [h for h in hours.get("weekdayDescriptions") or [] if isinstance(h, str)],
        "photo": photo,
        "maps_uri": raw.get("googleMapsUri"),
        "business_status": raw.get("businessStatus"),
        "is_food": primary in FOOD_TYPES or any(t in FOOD_TYPES for t in types),
        "source": "live",
    }


def normalize_list(raw_places: list) -> list[dict]:
    seen: set[str] = set()
    out: list[dict] = []
    for raw in raw_places or []:
        place = normalize_place(raw)
        if place and place["id"] not in seen:
            seen.add(place["id"])
            out.append(place)
    return out


def _error_from_response(response: httpx.Response) -> PlacesError:
    status = response.status_code
    try:
        provider = (response.json().get("error") or {}).get("message", "")
    except ValueError:
        provider = ""
    log.warning("Places API error %s: %s", status, provider[:300])
    if status == 429:
        return PlacesError(429, "quota", "Google Places quota or rate limit reached. Try again later.")
    if status in (401, 403):
        return PlacesError(503, "provider_denied",
                           "Google Places rejected the server key. Check that Places API (New) "
                           "is enabled, billing is active and the key restrictions allow this server.")
    if status == 404:
        return PlacesError(404, "not_found", "This place is no longer available from Google Places.")
    if status == 400:
        return PlacesError(502, "provider_rejected", "Google Places rejected the request.")
    return PlacesError(502, "provider_error", "Google Places is unavailable right now. Try again.")


async def _send(client: httpx.AsyncClient, method: str, url: str, **kwargs) -> dict:
    try:
        response = await client.request(method, url, **kwargs)
    except httpx.TimeoutException as exc:
        raise PlacesError(504, "timeout", "Google Places took too long to respond.") from exc
    except httpx.HTTPError as exc:
        raise PlacesError(502, "provider_unreachable", "Could not reach Google Places.") from exc
    if response.status_code != 200:
        raise _error_from_response(response)
    try:
        return response.json()
    except ValueError as exc:
        raise PlacesError(502, "provider_error", "Google Places returned an invalid response.") from exc


def _headers(key: str, field_mask: str) -> dict:
    return {"X-Goog-Api-Key": key, "X-Goog-FieldMask": field_mask,
            "Content-Type": "application/json"}


def _circle(lat: float, lng: float, radius: float) -> dict:
    return {"circle": {"center": {"latitude": lat, "longitude": lng}, "radius": float(radius)}}


async def search_nearby(client: httpx.AsyncClient, key: str, lat: float, lng: float,
                        radius: int, category: str, rank: str = "popularity",
                        max_results: int = 20) -> list[dict]:
    body = {
        "includedTypes": CATEGORY_TYPES[category],
        "maxResultCount": max(1, min(20, max_results)),
        "locationRestriction": _circle(lat, lng, radius),
        "rankPreference": "DISTANCE" if rank == "distance" else "POPULARITY",
        "languageCode": "en",
        "regionCode": "IN",
    }
    data = await _send(client, "POST", f"{BASE_URL}/places:searchNearby",
                       headers=_headers(key, SEARCH_FIELD_MASK), json=body)
    return normalize_list(data.get("places") or [])


async def search_text(client: httpx.AsyncClient, key: str, query: str,
                      lat: Optional[float], lng: Optional[float], radius: int,
                      category: str = "all", open_now: bool = False,
                      page_token: Optional[str] = None, page_size: int = 20
                      ) -> tuple[list[dict], Optional[str]]:
    body: dict[str, Any] = {
        "textQuery": query,
        "pageSize": max(1, min(20, page_size)),
        "languageCode": "en",
        "regionCode": "IN",
    }
    if lat is not None and lng is not None:
        body["locationBias"] = _circle(lat, lng, radius)
    included = CATEGORY_TEXT_TYPE.get(category)
    if included:
        body["includedType"] = included
    if open_now:
        body["openNow"] = True
    if page_token:
        body["pageToken"] = page_token
    data = await _send(client, "POST", f"{BASE_URL}/places:searchText",
                       headers=_headers(key, SEARCH_FIELD_MASK + ",nextPageToken"), json=body)
    return normalize_list(data.get("places") or []), data.get("nextPageToken")


async def find_locations(client: httpx.AsyncClient, key: str, query: str,
                         lat: float, lng: float, max_results: int = 5) -> list[dict]:
    """Candidate locations for an area or landmark typed by the user, biased to (lat, lng)."""
    body = {
        "textQuery": query,
        "pageSize": max(1, min(10, max_results)),
        "languageCode": "en",
        "regionCode": "IN",
        "locationBias": _circle(lat, lng, 50000),  # the largest bias radius Places allows
    }
    data = await _send(client, "POST", f"{BASE_URL}/places:searchText",
                       headers=_headers(key, LOCATE_FIELD_MASK), json=body)
    out: list[dict] = []
    seen: set[str] = set()
    for raw in data.get("places") or []:
        if not isinstance(raw, dict):
            continue
        location = raw.get("location") or {}
        name = (raw.get("displayName") or {}).get("text")
        p_lat, p_lng = location.get("latitude"), location.get("longitude")
        place_id = raw.get("id")
        if not name or p_lat is None or p_lng is None or place_id in seen:
            continue
        seen.add(place_id)
        out.append({
            "id": place_id,
            "name": name,
            "address": raw.get("formattedAddress") or "",
            "lat": float(p_lat),
            "lng": float(p_lng),
            "types": [t for t in raw.get("types") or [] if isinstance(t, str)],
        })
    return out


async def get_details(client: httpx.AsyncClient, key: str, place_id: str) -> dict:
    data = await _send(client, "GET", f"{BASE_URL}/places/{place_id}",
                       headers=_headers(key, DETAILS_FIELD_MASK),
                       params={"languageCode": "en", "regionCode": "IN"})
    place = normalize_place(data)
    if place is None:
        raise PlacesError(404, "not_found", "This place is no longer available from Google Places.")
    return place


async def get_photo_uri(client: httpx.AsyncClient, key: str, photo_name: str,
                        max_width: int) -> str:
    data = await _send(client, "GET", f"{BASE_URL}/{photo_name}/media",
                       headers={"X-Goog-Api-Key": key},
                       params={"maxWidthPx": max_width, "skipHttpRedirect": "true"})
    uri = data.get("photoUri")
    if not uri:
        raise PlacesError(502, "provider_error", "Google Places did not return a photo.")
    return uri
