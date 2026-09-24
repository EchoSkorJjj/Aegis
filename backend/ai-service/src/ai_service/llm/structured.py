"""Single orchestration point for strict schemas, validation, budgets, retries and telemetry."""

import asyncio
import json
import logging
import math
import time
from collections.abc import Callable
from typing import TypeVar

from pydantic import BaseModel, ValidationError

from ai_service.core.config import Settings
from ai_service.core.errors import BadGateway, ServiceUnavailable
from ai_service.llm.base import InvalidProviderOutput, LlmClient, RetryableProviderError
from ai_service.llm.limits import CostLimiter
from ai_service.llm.metrics import ATTEMPTS, LATENCY, REQUESTS, TOKENS

log = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


def strict_schema(model: type[BaseModel]) -> dict:
    """Use a conservative provider subset; full bounds/types still validate locally.

    Dictionary keys under properties/$defs are data, not schema keywords. Optional properties
    remain nullable but become required. Never strip refs or lose nested object constraints.
    """
    def transform(node: dict) -> dict:
        result = {k: v for k, v in node.items() if k in {"type", "$ref", "enum", "description"}}
        if "const" in node:
            result["enum"] = [node["const"]]
        for key in ("properties", "$defs"):
            if key in node:
                result[key] = {name: transform(child) for name, child in node[key].items()}
        if "items" in node:
            result["items"] = transform(node["items"])
        if "anyOf" in node:
            result["anyOf"] = [transform(child) for child in node["anyOf"]]
        if node.get("type") == "object":
            result["properties"] = result.get("properties", {})
            result["required"] = list(result["properties"])
            result["additionalProperties"] = False
        return result

    return transform(model.model_json_schema())


class StructuredGenerator:
    def __init__(self, settings: Settings, client: LlmClient, limiter: CostLimiter | None = None):
        self.settings = settings
        self.client = client
        self.limiter = limiter or CostLimiter(settings)

    async def generate(
        self, *, system: str, user: str, template_id: str, capability: str,
        output_model: type[T], validate: Callable[[T], None] | None = None,
    ) -> T:
        labels = (self.client.provider, self.client.model, capability, template_id)
        started, outcome = time.monotonic(), "error"
        try:
            # This encompasses every HTTP attempt AND backoff, not merely individual socket reads.
            async with asyncio.timeout(self.settings.llm_timeout_seconds):
                result = await self._generate(
                    system, user, output_model, validate, capability,
                )
            outcome = "success"
            return result
        except TimeoutError:
            outcome = "timeout"
            raise ServiceUnavailable("LLM generation timed out") from None
        except BadGateway:
            outcome = "invalid_output"
            raise
        except ServiceUnavailable:
            outcome = "unavailable"
            raise
        finally:
            REQUESTS.labels(*labels, outcome).inc()
            LATENCY.labels(*labels).observe(time.monotonic() - started)
            log.info(
                "LLM generation provider=%s model=%s capability=%s template=%s outcome=%s",
                *labels, outcome,
            )

    async def _generate(self, system, user, output_model, validate, capability):
        schema = strict_schema(output_model)
        # Include templates, escaped passages and schema overhead in admission estimates.
        estimate = math.ceil((len(system) + len(user) + len(json.dumps(schema))) / 4) + (
            self.settings.llm_max_output_tokens
        )
        status_retries, validation_retries = 0, 0
        while True:
            reservation = self.limiter.reserve(estimate)
            ATTEMPTS.labels(self.client.provider, self.client.model, capability).inc()
            try:
                result = await self.client.generate_json(
                    system=system, user=user, schema_name=output_model.__name__,
                    json_schema=schema, max_output_tokens=self.settings.llm_max_output_tokens,
                )
                self.limiter.reconcile(reservation, result.input_tokens + result.output_tokens)
                for direction, amount in (
                    ("input", result.input_tokens), ("output", result.output_tokens),
                ):
                    TOKENS.labels(self.client.provider, self.client.model, direction).inc(amount)
                parsed = output_model.model_validate_json(result.text, strict=True)
                if validate:
                    validate(parsed)
                return parsed
            except RetryableProviderError as exc:
                if status_retries == 2:
                    raise ServiceUnavailable("LLM provider remains unavailable or rate-limited") from None
                delay = max(0.25 * 2 ** status_retries, exc.retry_after or 0)
                status_retries += 1
                await asyncio.sleep(delay)
            except (InvalidProviderOutput, ValidationError, ValueError):
                if validation_retries == 1:
                    raise BadGateway("LLM provider returned invalid output after validation retry") from None
                validation_retries += 1
                # Do not echo invalid model content or validation messages into another prompt.
