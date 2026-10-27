"""Optional AI backend for Tourist Guide MAD. Keep provider keys on the server."""
import os

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

import httpx

load_dotenv()

app = FastAPI(title="Tourist Guide AI", version="1.0.0")


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1500)
    city: str = Field(default="Mumbai", max_length=80)


class ChatResponse(BaseModel):
    reply: str
    mode: str


@app.get("/health")
def health():
    return {"status": "ok", "ai_configured": bool(os.getenv("GEMINI_API_KEY"))}


@app.post("/chat", response_model=ChatResponse)
async def chat(body: ChatRequest):
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="AI is not configured. Set GEMINI_API_KEY on the server.")
    model = os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    system = ("You are a careful travel assistant inside a tourist guide app. "
              f"The selected city is {body.city}. Give practical, concise itineraries, ask clarifying questions if needed, "
              "and distinguish estimates from verified facts. Do not invent live opening hours, prices, ratings, or availability. "
              "Encourage users to verify current details from official sources. Keep safety and accessibility in mind. "
              "Answer in the same language the user writes in. Keep answers under 150 words.")
    payload = {"system_instruction": {"parts": [{"text": system}]},
               "contents": [{"parts": [{"text": body.message}]}],
               "generationConfig": {"temperature": 0.5, "maxOutputTokens": 700}}
    try:
        async with httpx.AsyncClient(timeout=25) as client:
            response = await client.post(url, headers={"x-goog-api-key": api_key}, json=payload)
        response.raise_for_status()
        data = response.json()
        reply = data["candidates"][0]["content"]["parts"][0]["text"]
        return ChatResponse(reply=reply, mode="gemini")
    except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=502, detail="The AI provider request failed. Check server logs and provider configuration.") from exc
