"""Runs the real backend with Google Places and Gemini replaced by canned responses.

Used for the Android <-> backend contract test (no real keys, no billing):
    python tests/fake_provider_server.py            # serves on 127.0.0.1:8765
    ./gradlew testDebugUnitTest -PcontractBackendUrl=http://127.0.0.1:8765
"""
import json
import os
import sys

import httpx
import uvicorn

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("PLACES_API_KEY", "fake-places-key")
os.environ.setdefault("GEMINI_API_KEY", "fake-gemini-key")
os.environ.setdefault("GEMINI_MODEL", "fake-model")

import main  # noqa: E402
from test_backend import GOOGLE_PLACE, RESTAURANT  # noqa: E402


def handler(request: httpx.Request) -> httpx.Response:
    if request.url.host == "places.googleapis.com":
        if request.url.path.endswith("/media"):
            return httpx.Response(200, json={"photoUri": "https://example.invalid/photo.jpg"})
        if request.url.path.startswith("/v1/places/") and request.method == "GET":
            return httpx.Response(200, json=GOOGLE_PLACE)
        return httpx.Response(200, json={"places": [GOOGLE_PLACE, RESTAURANT]})
    # Gemini: recommend the restaurant fetched by the backend plus one invented id.
    reply = {"reply": "Try Sample Veg Kitchen for lunch.",
             "place_ids": ["ChIJ-food-000002", "invented-id"], "needs_more_info": False}
    return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": json.dumps(reply)}]}}]})


fake = httpx.AsyncClient(transport=httpx.MockTransport(handler))
main.app.dependency_overrides[main.get_client] = lambda: fake

if __name__ == "__main__":
    uvicorn.run(main.app, host="127.0.0.1", port=int(os.getenv("PORT", "8765")), log_level="warning")
