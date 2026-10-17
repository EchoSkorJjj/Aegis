"""Budget admission under concurrency, retries, actual usage and clock boundaries."""

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import pytest
from pydantic import BaseModel

from ai_service.core.config import Settings
from ai_service.core.errors import BadGateway, RateLimited, ServiceUnavailable
from ai_service.llm.base import LlmResult
from ai_service.llm.limits import CostLimiter
from ai_service.llm.structured import StructuredGenerator


class Clock:
    seconds = 0.0
    now = datetime(2026, 9, 24, 23, 59, 30, tzinfo=UTC)

    def tick(self, seconds):
        self.seconds += seconds
        self.now += timedelta(seconds=seconds)


def limiter(clock, **overrides):
    config = Settings(_env_file=None, **overrides)
    return CostLimiter(config, clock=lambda: clock.seconds, utcnow=lambda: clock.now)


def test_rolling_request_limit_and_expiry():
    clock = Clock()
    budget = limiter(clock, llm_max_requests_per_minute=2)
    budget.reserve(1)
    clock.tick(10)
    budget.reserve(1)
    with pytest.raises(ServiceUnavailable, match="request rate"):
        budget.reserve(1)
    clock.tick(50)
    budget.reserve(1)  # only the first request expires
    with pytest.raises(ServiceUnavailable, match="request rate"):
        budget.reserve(1)


def test_token_limit_reconciles_reported_usage_and_keeps_failed_estimates():
    budget = limiter(Clock(), llm_max_tokens_per_minute=100)
    reservation = budget.reserve(90)
    with pytest.raises(ServiceUnavailable, match="token rate"):
        budget.reserve(11)
    budget.reconcile(reservation, 20)
    budget.reserve(80)  # failed/unknown calls deliberately remain charged
    with pytest.raises(ServiceUnavailable, match="token rate"):
        budget.reserve(1)


def test_a_full_minute_says_when_it_will_have_room():
    # One assessment is two calls; the second can find the minute full. Saying when it frees lets the caller wait.
    clock = Clock()
    budget = limiter(clock, llm_max_tokens_per_minute=100, llm_max_requests_per_minute=3)
    budget.reserve(60)
    clock.tick(10)
    budget.reserve(30)
    with pytest.raises(RateLimited, match="token rate") as tokens:
        budget.reserve(20)
    assert tokens.value.retry_after == 50  # the first reservation leaves the window then
    budget.reserve(5)
    with pytest.raises(RateLimited, match="request rate") as requests:
        budget.reserve(1)
    assert requests.value.retry_after == 50


def test_what_could_never_fit_a_minute_and_the_daily_budget_are_no_wait():
    clock = Clock()
    clock.now = datetime(2026, 9, 24, 12, 0, tzinfo=UTC)
    budget = limiter(clock, llm_max_tokens_per_minute=100, llm_daily_token_budget=150)
    with pytest.raises(ServiceUnavailable) as never:
        budget.reserve(101)
    assert not isinstance(never.value, RateLimited)
    budget.reserve(100)
    clock.tick(61)  # a new minute, the same UTC day
    with pytest.raises(ServiceUnavailable, match="daily token budget") as daily:
        budget.reserve(60)
    assert not isinstance(daily.value, RateLimited)


def test_actual_usage_above_estimate_blocks_further_calls():
    budget = limiter(Clock(), llm_max_tokens_per_minute=100)
    reservation = budget.reserve(50)
    budget.reconcile(reservation, 120)
    with pytest.raises(ServiceUnavailable, match="token rate"):
        budget.reserve(1)


def test_daily_budget_survives_minute_expiry_but_resets_at_utc_midnight():
    clock = Clock()
    clock.now = datetime(2026, 9, 24, 10, tzinfo=UTC)
    budget = limiter(clock, llm_daily_token_budget=100)
    budget.reserve(100)
    clock.tick(61)
    with pytest.raises(ServiceUnavailable, match="daily token budget"):
        budget.reserve(1)
    clock.tick(24 * 3600)
    budget.reserve(100)


def test_inflight_old_day_reconciliation_does_not_credit_new_day():
    clock = Clock()
    budget = limiter(clock, llm_daily_token_budget=100)
    old = budget.reserve(100)
    clock.tick(61)
    budget.reserve(100)
    budget.reconcile(old, 0)
    with pytest.raises(ServiceUnavailable, match="daily token budget"):
        budget.reserve(1)


def test_concurrent_requests_cannot_overbook_budget():
    budget = limiter(Clock(), llm_daily_token_budget=100, llm_max_requests_per_minute=100)

    def reserve(_):
        try:
            budget.reserve(10)
            return True
        except ServiceUnavailable:
            return False

    with ThreadPoolExecutor(max_workers=16) as workers:
        assert sum(workers.map(reserve, range(100))) == 10


class Output(BaseModel):
    value: str


class InvalidClient:
    provider = "test"
    model = "invalid"
    calls = 0

    async def generate_json(self, **kwargs):
        self.calls += 1
        return LlmResult("not json", self.provider, self.model, 90, 10)


def test_invalid_model_outputs_are_counted_against_daily_budget():
    config = Settings(
        _env_file=None, llm_max_output_tokens=1, llm_daily_token_budget=200,
    )
    client = InvalidClient()
    engine = StructuredGenerator(config, client)
    with pytest.raises(BadGateway):
        asyncio.run(engine.generate(
            system="s", user="u", template_id="test.v1", capability="test", output_model=Output,
        ))
    assert client.calls == 2
    with pytest.raises(ServiceUnavailable, match="daily token budget"):
        asyncio.run(engine.generate(
            system="s", user="u", template_id="test.v1", capability="test", output_model=Output,
        ))
    assert client.calls == 2


def test_concurrent_capabilities_share_same_engine_budget():
    config = Settings(_env_file=None, llm_max_requests_per_minute=1)
    from ai_service.llm.stub import StubLlmClient

    engine = StructuredGenerator(config, StubLlmClient())

    async def run():
        return await asyncio.gather(*(
            engine.generate(
                system="s", user="u", template_id=f"{name}.v1", capability=name,
                output_model=Output,
            )
            for name in ("assess", "draft")
        ), return_exceptions=True)

    results = asyncio.run(run())
    assert sum(isinstance(result, Output) for result in results) == 1
    assert sum(isinstance(result, ServiceUnavailable) for result in results) == 1
