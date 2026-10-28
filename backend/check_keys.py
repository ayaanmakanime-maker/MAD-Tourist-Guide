"""Check that the keys in backend/.env work, and print Google's reason when they don't.

Run from the backend folder:  python check_keys.py
Nothing is sent anywhere except Google, and keys are never printed.
"""
from __future__ import annotations

import sys

import httpx

import assistant
import places
from config import describe_key, get_settings


def check_gemini(client: httpx.Client, key: str, model: str) -> bool:
    payload = assistant.payload_for(model, {
        "contents": [{"role": "user", "parts": [{"text": "Reply with the word OK."}]}],
        "generationConfig": {"maxOutputTokens": 64},
    })
    try:
        response = client.post(assistant.GEMINI_URL.format(model=model), json=payload,
                               headers={"x-goog-api-key": key})
    except httpx.HTTPError as exc:
        print(f"  Gemini {model}: could not reach Google ({type(exc).__name__})")
        return False
    if response.status_code == 200:
        print(f"  Gemini {model}: OK")
        return True
    print(f"  Gemini {model}: HTTP {response.status_code} – {assistant.provider_reason(response) or 'no message'}")
    if response.status_code == 503:
        print("    (busy right now; the backend will try the other model)")
    return False


def check_places(client: httpx.Client, key: str) -> bool:
    try:
        response = client.post(f"{places.BASE_URL}/places:searchText",
                               headers=places._headers(key, "places.id"),
                               json={"textQuery": "Gateway of India Mumbai", "pageSize": 1})
    except httpx.HTTPError as exc:
        print(f"  Places: could not reach Google ({type(exc).__name__})")
        return False
    if response.status_code == 200:
        print("  Places: OK")
        return True
    print(f"  Places: HTTP {response.status_code} – {assistant.provider_reason(response) or 'no message'}")
    return False


def main() -> int:
    s = get_settings()
    print(f"GEMINI_API_KEY {describe_key(s.gemini_api_key)}")
    print(f"PLACES_API_KEY {describe_key(s.places_api_key)}")
    ok = True
    with httpx.Client(timeout=30) as client:
        if s.gemini_api_key:
            results = [check_gemini(client, s.gemini_api_key, m)
                       for m in dict.fromkeys([s.gemini_model, s.gemini_fallback_model]) if m]
            ok &= any(results)
        if s.places_api_key:
            ok &= check_places(client, s.places_api_key)
    print("All good." if ok else "Something needs fixing – see the lines above.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
