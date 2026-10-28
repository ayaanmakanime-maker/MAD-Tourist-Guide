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
            "maxOutputTokens": 2048,
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
        salvaged = _salvage(text)
        if salvaged:
            return salvaged
        if text.lstrip().startswith("{"):
            # Broken JSON (usually cut off by the output limit): never show it to the user.
            raise AssistantError(502, "ai_truncated", "The AI answer was cut off. Try again.")
        # Model ignored the schema: keep the text but recommend no places.
        return {"reply": text, "place_ids": [], "needs_more_info": False}
    ids = parsed.get("place_ids") if isinstance(parsed.get("place_ids"), list) else []
    return {
        "reply": format_reply(parsed["reply"].strip()),
        "place_ids": [i for i in ids if isinstance(i, str)],
        "needs_more_info": bool(parsed.get("needs_more_info")),
    }


_REPLY_FIELD = re.compile(r'"reply"\s*:\s*"((?:[^"\\]|\\.)*)', re.S)
_IDS_FIELD = re.compile(r'"place_ids"\s*:\s*\[([^\]]*)\]')


def _salvage(text: str) -> Optional[dict]:
    """Recover the reply from JSON that was cut off (e.g. at the token limit)."""
    match = _REPLY_FIELD.search(text)
    if not match or not match.group(1).strip():
        return None
    raw = match.group(1)
    reply = None
    for cut in range(0, 7):  # drop a partial escape sequence at the cut-off point
        try:
            reply = json.loads('"' + raw[:len(raw) - cut] + '"')
            break
        except ValueError:
            continue
    if not reply or not reply.strip():
        return None
    reply = reply.strip()
    if text[match.end():match.end() + 1] != '"':
        reply += " …"
    ids: list[str] = []
    ids_match = _IDS_FIELD.search(text)
    if ids_match:
        try:
            ids = [i for i in json.loads("[" + ids_match.group(1) + "]") if isinstance(i, str)]
        except ValueError:
            ids = []
    return {"reply": format_reply(reply), "place_ids": ids, "needs_more_info": False}


def payload_for(model: str, payload: dict) -> dict:
    """Gemini 3 models think by default; low thinking keeps answers fast and within the output limit."""
    if not model.startswith("gemini-3"):
        return payload
    config = dict(payload["generationConfig"], thinkingConfig={"thinkingLevel": "low"})
    return dict(payload, generationConfig=config)


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


_KEYLIKE = re.compile(r"(api_key:)?(AIza[0-9A-Za-z_-]{10,}|AQ\.[0-9A-Za-z_.-]{10,})")


def provider_reason(response: httpx.Response) -> str:
    """Google's error message, with anything that looks like a key removed (it can quote it)."""
    try:
        message = str(((response.json() or {}).get("error") or {}).get("message") or "")
    except (ValueError, AttributeError):
        message = ""
    return _KEYLIKE.sub("[key]", " ".join(message.split()))[:240]


async def call_gemini(client: httpx.AsyncClient, key: str, models: list[str], payload: dict
                      ) -> tuple[dict, str]:
    """Try each model in turn. Busy, retired, rejected or cut-off answers fall through to the next
    model; a rejected key is reported (with Google's reason) only if no model answered."""
    last = AssistantError(502, "ai_error", "The AI provider request failed.")
    denied: Optional[AssistantError] = None
    for model in models:
        try:
            response = await client.post(GEMINI_URL.format(model=model), json=payload_for(model, payload),
                                         headers={"x-goog-api-key": key})
        except httpx.TimeoutException:
            log.warning("Gemini %s timed out", model)
            last = AssistantError(504, "ai_timeout", "The AI took too long to respond. Try again.")
            continue
        except httpx.HTTPError:
            log.warning("Gemini %s could not be reached", model)
            last = AssistantError(502, "ai_unreachable", "Could not reach the AI provider.")
            continue
        if response.status_code == 200:
            try:
                return parse_gemini(response.json()), model
            except AssistantError as exc:
                if exc.kind != "ai_truncated":
                    raise
                last = exc  # cut-off answer: try the next model
                continue
            except ValueError as exc:
                raise AssistantError(502, "ai_error", "The AI returned an invalid response.") from exc
        reason = provider_reason(response)
        log.warning("Gemini %s returned HTTP %s: %s", model, response.status_code, reason or "(no message)")
        status = response.status_code
        if status == 429:
            last = AssistantError(429, "quota", "The AI quota or rate limit was reached. Try again in a minute.")
        elif status in (401, 403) or (status == 400 and "api key" in reason.lower()):
            denied = AssistantError(503, "ai_denied", "Google rejected the server's Gemini key"
                                    + (f": {reason}" if reason else "."))
        elif status == 400:
            last = AssistantError(502, "ai_rejected", "The AI rejected the request. Try rephrasing it.")
        elif status == 404 or status >= 500:
            last = AssistantError(502, "ai_busy", "The AI is busy right now. Try again.")
        else:
            last = AssistantError(502, "ai_error", "The AI provider request failed.")
    raise denied or last
