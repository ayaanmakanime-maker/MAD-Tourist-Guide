"""Grounded travel assistant: builds the Gemini request from app-supplied context
and validates that every recommended place really came from that context."""
from __future__ import annotations

import json
import logging
import re
from typing import Any, Optional

import httpx

log = logging.getLogger("assistant")

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

SYSTEM_PROMPT = (
    "You are the travel assistant inside a tourist guide Android app for {city}. "
    "You receive a CONTEXT JSON block with the user's selected area, the places the app has "
    "loaded (each with an id), their itinerary and their own budget numbers.\n"
    "Rules:\n"
    "1. Recommend specific places ONLY from CONTEXT.places and put their exact ids in place_ids. "
    "Never invent places, ids, ratings, review counts, opening hours, prices, menus or travel times.\n"
    "2. Only quote a rating, opening status or price when that field is present for the place. "
    "price_level is Google's relative price category, not a cost per person. price_range, when "
    "present, is Google's estimate. If the user's budget (for example 'under Rs 500') cannot be "
    "verified from the data, say so and suggest confirming prices locally.\n"
    "3. Places with source 'curated' come from the app's offline guide, not live data.\n"
    "4. If essential information is missing (for example no location for 'near me', or no matching "
    "places in CONTEXT), set needs_more_info to true and ask ONE short follow-up question.\n"
    "5. Itineraries: give a simple ordered list; do not claim optimal routes or exact journey times.\n"
    "6. Be concise (under 180 words), practical and safety-aware. Reply in the user's language. "
    "Use plain text: put each suggestion on its own line starting with '- ' (use real line breaks), "
    "no markdown bold, tables or headings."
)

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "reply": {"type": "STRING"},
        "place_ids": {"type": "ARRAY", "items": {"type": "STRING"}},
        "needs_more_info": {"type": "BOOLEAN"},
    },
    "required": ["reply", "place_ids"],
}

_FOOD_WORDS = re.compile(
    r"\b(restaurants?|food|eat|eating|lunch|dinner|breakfast|brunch|cafes?|cafés?|coffee|"
    r"veg|vegetarian|vegan|street food|snacks?|biryani|thali|meal|meals|dine|dining)\b",
    re.IGNORECASE)
_OPEN_NOW = re.compile(r"\b(open now|currently open|open right now|still open|opened now)\b",
                       re.IGNORECASE)
_NEAR_SELECTED = re.compile(
    r"\b(selected|near it|close to it|near my attraction|near the attraction|close to my|near my)\b",
    re.IGNORECASE)


class AssistantError(Exception):
    def __init__(self, status: int, kind: str, message: str):
        super().__init__(message)
        self.status = status
        self.kind = kind
        self.message = message


def detect_food_intent(message: str) -> Optional[dict]:
    """Return a Places text query for restaurant-style questions, else None."""
    if not _FOOD_WORDS.search(message):
        return None
    lower = message.lower()
    if "vegan" in lower:
        query = "vegan restaurant"
    elif re.search(r"\b(veg|vegetarian)\b", lower):
        query = "vegetarian restaurant"
    elif re.search(r"\b(cafes?|cafés?|coffee)\b", lower):
        query = "cafe"
    elif "breakfast" in lower:
        query = "breakfast restaurant"
    else:
        query = "restaurant"
    return {
        "query": query,
        "open_now": bool(_OPEN_NOW.search(message)),
        "near_selected": bool(_NEAR_SELECTED.search(message)) and "near me" not in lower,
    }


def _round(value: Optional[float], digits: int = 2) -> Optional[float]:
    return None if value is None else round(float(value), digits)


def context_place(place: dict) -> dict:
    """The subset of a place that is sent to Gemini (no photos, no raw provider payload)."""
    keys = ("id", "name", "category_label", "address", "rating", "rating_count", "price_level",
            "price_range", "open_now", "distance_m", "is_food", "source")
    return {k: place.get(k) for k in keys if place.get(k) not in (None, "", [])}


def build_context(request: dict, candidates: list[dict], notes: list[str]) -> dict:
    location = request.get("location") or None
    context: dict[str, Any] = {
        "city": request.get("city") or "Mumbai",
        # Coordinates are rounded (~1 km) before they reach the AI provider.
        "selected_area": None if not location else {
            "label": location.get("label"),
            "lat": _round(location.get("lat")),
            "lng": _round(location.get("lng")),
        },
        "selected_place": (request.get("selected_place") or {}).get("name"),
        "places": [context_place(p) for p in candidates],
        "itinerary": [{"name": p.get("name"), "day": p.get("day")} for p in request.get("plan") or []],
        "user_budget": request.get("budget"),
    }
    if notes:
        context["notes"] = notes
    return context


def build_payload(request: dict, context: dict) -> dict:
    contents = []
    for turn in request.get("history") or []:
        role = "model" if turn.get("role") == "assistant" else "user"
        contents.append({"role": role, "parts": [{"text": turn.get("text", "")}]})
    user_text = ("CONTEXT:\n" + json.dumps(context, ensure_ascii=False) +
                 "\n\nUSER QUESTION:\n" + request["message"])
    contents.append({"role": "user", "parts": [{"text": user_text}]})
    return {
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT.format(city=context["city"])}]},
        "contents": contents,
        "generationConfig": {
            "temperature": 0.4,
            "maxOutputTokens": 1024,
            "responseMimeType": "application/json",
            "responseSchema": RESPONSE_SCHEMA,
        },
    }


def parse_gemini(data: dict) -> dict:
    candidates = data.get("candidates") or []
    if not candidates:
        reason = (data.get("promptFeedback") or {}).get("blockReason")
        raise AssistantError(502, "ai_blocked" if reason else "ai_empty",
                             "The AI did not return an answer. Try rephrasing your question.")
    candidate = candidates[0] or {}
    parts = (candidate.get("content") or {}).get("parts") or []
    text = "\n".join(p.get("text", "") for p in parts
                     if isinstance(p, dict) and not p.get("thought")).strip()
    if not text:
        raise AssistantError(502, "ai_empty", "The AI returned an empty answer. Try again.")
    try:
        parsed = json.loads(text)
        if not isinstance(parsed, dict) or not isinstance(parsed.get("reply"), str):
            raise ValueError("unexpected shape")
    except ValueError:
        # Model ignored the schema: keep the text but recommend no places.
        return {"reply": text, "place_ids": [], "needs_more_info": False}
    ids = parsed.get("place_ids") if isinstance(parsed.get("place_ids"), list) else []
    return {
        "reply": format_reply(parsed["reply"].strip()),
        "place_ids": [i for i in ids if isinstance(i, str)],
        "needs_more_info": bool(parsed.get("needs_more_info")),
    }


_INLINE_BULLET = re.compile(r"([:.!?)])\s+-\s+(?=\S)")
_INLINE_NUMBER = re.compile(r"([:,.;!?])\s+(\d{1,2})[.)]\s+(?=[A-Z])")


def format_reply(text: str) -> str:
    """Models sometimes write lists inline ("Try: - A. - B." or "Try: 1. A, 2. B").
    Put each item on its own line."""
    if "\n" in text:
        return text
    if len(_INLINE_BULLET.findall(text)) >= 2:
        return _INLINE_BULLET.sub(lambda m: m.group(1) + "\n- ", text)
    if len(_INLINE_NUMBER.findall(text)) >= 2:
        return _INLINE_NUMBER.sub(lambda m: m.group(1) + "\n" + m.group(2) + ". ", text)
    return text


def grounded_places(place_ids: list[str], candidates: list[dict], limit: int = 6) -> list[dict]:
    """Keep only ids that exist in the supplied candidates, in the model's order, without repeats."""
    by_id = {p["id"]: p for p in candidates if p.get("id")}
    out: list[dict] = []
    for pid in place_ids:
        if pid in by_id and by_id[pid] not in out:
            out.append(by_id[pid])
        if len(out) >= limit:
            break
    return out


async def call_gemini(client: httpx.AsyncClient, key: str, models: list[str], payload: dict
                      ) -> tuple[dict, str]:
    """Try each model in turn; retired/overloaded models fall through to the next one."""
    last = AssistantError(502, "ai_error", "The AI provider request failed.")
    for model in models:
        try:
            response = await client.post(GEMINI_URL.format(model=model), json=payload,
                                         headers={"x-goog-api-key": key})
        except httpx.TimeoutException:
            last = AssistantError(504, "ai_timeout", "The AI took too long to respond. Try again.")
            continue
        except httpx.HTTPError:
            last = AssistantError(502, "ai_unreachable", "Could not reach the AI provider.")
            continue
        if response.status_code == 200:
            try:
                return parse_gemini(response.json()), model
            except ValueError as exc:
                raise AssistantError(502, "ai_error", "The AI returned an invalid response.") from exc
        log.warning("Gemini %s returned HTTP %s", model, response.status_code)
        if response.status_code == 429:
            last = AssistantError(429, "quota", "The AI quota or rate limit was reached. Try again in a minute.")
        elif response.status_code in (401, 403):
            raise AssistantError(503, "ai_denied", "The server's Gemini key was rejected.")
        elif response.status_code == 400:
            raise AssistantError(502, "ai_rejected", "The AI rejected the request. Try rephrasing it.")
        elif response.status_code == 404 or response.status_code >= 500:
            last = AssistantError(502, "ai_busy", "The AI is busy right now. Try again.")
        else:
            raise AssistantError(502, "ai_error", "The AI provider request failed.")
    raise last
