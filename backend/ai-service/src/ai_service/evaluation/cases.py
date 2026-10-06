"""Versioned, synthetic golden corpus. No production estate or feed access."""

import hashlib
import json
import re
from importlib.resources import files
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from ai_service.capabilities.draft import DraftOutput, MemoOutput, TicketOutput


def fingerprint(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Checks(StrictModel):
    # Each group is one expectation; alternatives within a group allow different wording.
    facts: list[list[str]] = Field(default_factory=list)
    actions: list[list[str]] = Field(default_factory=list)
    uncertainty: list[list[str]] = Field(default_factory=list)
    forbidden: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def valid_rules(self):
        if any(not group or any(not term.strip() for term in group)
               for groups in (self.facts, self.actions, self.uncertainty) for group in groups):
            raise ValueError("Expectation groups must contain nonempty alternatives")
        for pattern in self.forbidden:
            try:
                re.compile(pattern)
            except re.error:
                raise ValueError("Forbidden-claim pattern must be a valid regular expression") from None
        return self


class GoldenCase(StrictModel):
    id: str = Field(pattern=r"^[a-z][a-z0-9-]{1,79}$")
    title: str = Field(min_length=1, max_length=160)
    tags: list[str] = Field(min_length=1)
    draft_type: Literal["risk_ticket", "upgrade", "memo"]
    context: str | None
    expected: Literal["draft", "blocked"] = "draft"
    checks: Checks = Field(default_factory=Checks)
    reference: dict | None = None

    @model_validator(mode="after")
    def valid_reference(self):
        if self.expected == "blocked":
            if self.reference is not None:
                raise ValueError("A blocked case has no reference draft")
        else:
            if self.reference is None:
                raise ValueError("A draft case requires a reference output")
            if not self.checks.facts or not self.checks.actions:
                raise ValueError("A quality case requires factual and actionable expectations")
            output_model(self.draft_type).model_validate(self.reference, strict=True)
        return self


def output_model(draft_type: str):
    return {"risk_ticket": TicketOutput, "memo": MemoOutput}.get(draft_type, DraftOutput)


class Corpus(StrictModel):
    version: str
    description: str
    cases: list[GoldenCase] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_cases(self):
        ids = [case.id for case in self.cases]
        if len(ids) != len(set(ids)):
            raise ValueError("Golden case IDs must be unique")
        return self


def load_corpus(path: Path | None = None) -> Corpus:
    source = path or files(__package__).joinpath("golden_cases.json")
    return Corpus.model_validate_json(source.read_text(encoding="utf-8"))
