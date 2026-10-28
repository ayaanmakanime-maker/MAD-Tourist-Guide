"""Server configuration, read from environment variables (or backend/.env).

Secrets never leave the server: the Android app only talks to this backend.
"""
import os
from dataclasses import dataclass

from dotenv import load_dotenv

# backend/.env wins over variables left in the shell, so an old exported key cannot silently replace it.
load_dotenv(override=True)


def describe_key(key: str) -> str:
    """Safe summary of a key for logs: never more than its first 4 characters."""
    return f"set ({key[:4]}…, {len(key)} chars)" if key else "missing"


def _int(name: str, default: int, minimum: int = 0) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return max(minimum, int(raw))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    gemini_api_key: str
    gemini_model: str
    gemini_fallback_model: str
    places_api_key: str
    places_cache_seconds: int
    rate_limit_per_minute: int
    daily_places_limit: int
    daily_chat_limit: int

    @property
    def ai_configured(self) -> bool:
        return bool(self.gemini_api_key)

    @property
    def places_configured(self) -> bool:
        return bool(self.places_api_key)


def get_settings() -> Settings:
    """Read settings on every call so tests (and a restarted .env) are picked up."""
    return Settings(
        gemini_api_key=os.getenv("GEMINI_API_KEY", "").strip(),
        gemini_model=os.getenv("GEMINI_MODEL", "").strip() or "gemini-3.1-flash-lite",
        gemini_fallback_model=os.getenv("GEMINI_FALLBACK_MODEL", "gemini-3-flash-preview").strip(),
        places_api_key=os.getenv("PLACES_API_KEY", "").strip(),
        places_cache_seconds=_int("PLACES_CACHE_SECONDS", 600),
        rate_limit_per_minute=_int("RATE_LIMIT_PER_MINUTE", 60, minimum=1),
        daily_places_limit=_int("DAILY_PLACES_LIMIT", 1000),
        daily_chat_limit=_int("DAILY_CHAT_LIMIT", 500),
    )
