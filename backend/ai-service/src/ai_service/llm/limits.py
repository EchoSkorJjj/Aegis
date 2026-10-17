"""Process-local admission control, shared across capabilities and concurrent requests.

Reserve before each attempt; reconcile when usage is known, retain estimates on ambiguous failures.
The minute window rolls; daily usage resets at UTC midnight and on process restart. Distributed
deployments need a shared limiter. Character/4 estimates are not a tokenizer or a billing guarantee.
"""

import math
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime
from threading import Lock

from ai_service.core.config import Settings
from ai_service.core.errors import RateLimited, ServiceUnavailable


@dataclass
class Reservation:
    created: float
    day: date
    tokens: int
    settled: bool = False


class CostLimiter:
    def __init__(
        self, settings: Settings, *, clock: Callable[[], float] = time.monotonic,
        utcnow: Callable[[], datetime] = lambda: datetime.now(UTC),
    ):
        self._settings = settings
        self._clock = clock
        self._utcnow = utcnow
        self._lock = Lock()
        self._minute: deque[Reservation] = deque()
        self._day = utcnow().date()
        self._daily_tokens = 0

    def reserve(self, tokens: int) -> Reservation:
        if tokens < 0:
            raise ValueError("Token reservation cannot be negative")
        with self._lock:
            now, day = self._clock(), self._utcnow().date()
            if day != self._day:
                self._day, self._daily_tokens = day, 0
            while self._minute and self._minute[0].created <= now - 60:
                self._minute.popleft()
            if tokens > self._settings.llm_max_tokens_per_minute:
                raise ServiceUnavailable("LLM request exceeds the per-minute token limit")
            if len(self._minute) >= self._settings.llm_max_requests_per_minute:
                raise RateLimited("LLM request rate limit reached; try again later",
                                  retry_after=self._expires(self._minute[0], now))
            used = sum(entry.tokens for entry in self._minute)
            if used + tokens > self._settings.llm_max_tokens_per_minute:
                # When enough of the oldest reservations leave the rolling minute for this one to fit.
                for entry in self._minute:
                    used -= entry.tokens
                    if used + tokens <= self._settings.llm_max_tokens_per_minute:
                        break
                raise RateLimited("LLM token rate limit reached; try again later",
                                  retry_after=self._expires(entry, now))
            if self._daily_tokens + tokens > self._settings.llm_daily_token_budget:
                raise ServiceUnavailable("LLM daily token budget exhausted")
            entry = Reservation(now, day, tokens)
            self._minute.append(entry)
            self._daily_tokens += tokens
            return entry

    @staticmethod
    def _expires(entry: Reservation, now: float) -> int:
        """Whole seconds until ``entry`` leaves the rolling minute, at least one."""
        return max(1, math.ceil(entry.created + 60 - now))

    def reconcile(self, entry: Reservation, actual_tokens: int) -> None:
        if actual_tokens < 0:
            raise ValueError("Token usage cannot be negative")
        with self._lock:
            if entry.settled:
                raise ValueError("Token reservation already settled")
            if entry.day == self._day:
                self._daily_tokens += actual_tokens - entry.tokens
            entry.tokens, entry.settled = actual_tokens, True
