"""Versioned, synthetic golden corpus. No production estate or feed access."""

import hashlib
import json
import re
from importlib.resources import files
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from ai_service.capabilities.assess import AssessmentOutput
from ai_service.capabilities.draft import DraftOutput, MemoOutput, TicketOutput
from ai_service.capabilities.look_for import LookForOutput
from ai_service.schemas import AssessmentRequest, LookForRequest


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
    # An assessment's checks must not match these: a search Aegis already ran, the package's internals.
    forbidden_checks: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def valid_rules(self):
        if any(not group or any(not term.strip() for term in group)
               for groups in (self.facts, self.actions, self.uncertainty) for group in groups):
            raise ValueError("Expectation groups must contain nonempty alternatives")
        for pattern in self.forbidden + self.forbidden_checks:
            try:
                re.compile(pattern)
            except re.error:
                raise ValueError("Forbidden-claim pattern must be a valid regular expression") from None
        return self


class GoldenCase(StrictModel):
    """A draft case (``context`` → a draft of ``draft_type``); an assess case (E11-5): an advisory, a package, what its
    fix changed, the search of the code and excerpts of it → whether the vulnerable part is used, and what to check;
    or a look-for case (pass 1): the advisory and its fix → what to search the code for."""

    id: str = Field(pattern=r"^[a-z][a-z0-9-]{1,79}$")
    title: str = Field(min_length=1, max_length=160)
    tags: list[str] = Field(min_length=1)
    capability: Literal["draft", "assess", "look_for"] = "draft"
    draft_type: Literal["risk_ticket", "upgrade", "memo"] | None = None
    context: str | None = None
    advisory: str | None = None
    package: dict | None = None
    fix_summary: str | None = None
    searches: list[dict] | None = None
    excerpts: list[dict] | None = None
    # A look-for case: the terms it must name, and any it must never keep (the pipeline drops them).
    expected_terms: list[str] = Field(default_factory=list)
    unexpected_terms: list[str] = Field(default_factory=list)
    expected_verdict: Literal["CALLED", "NOT_CALLED", "CANT_TELL"] | None = None
    expected_citations: list[str] = Field(default_factory=list)
    expected: Literal["draft", "blocked"] = "draft"
    checks: Checks = Field(default_factory=Checks)
    reference: dict | None = None

    @model_validator(mode="after")
    def valid_reference(self):
        if self.capability == "assess":
            self.assessment_request()  # the advisory, the package and the excerpts form a valid request
        elif self.capability == "look_for":
            self.look_for_request()
        elif self.draft_type is None:
            raise ValueError("A draft case names its draft type")
        if self.expected == "blocked":
            if self.reference is not None:
                raise ValueError("A blocked case has no reference draft")
        else:
            if self.reference is None:
                raise ValueError("A draft case requires a reference output")
            if self.capability == "assess":
                if self.expected_verdict is None:
                    raise ValueError("An assess case requires its expected verdict")
            elif self.capability == "look_for":
                if not self.expected_terms:
                    raise ValueError("A look-for case requires its expected terms")
            elif not self.checks.facts or not self.checks.actions:
                raise ValueError("A quality case requires factual and actionable expectations")
            output_model(self).model_validate(self.reference, strict=True)
        return self

    def assessment_request(self) -> AssessmentRequest:
        return AssessmentRequest.model_validate({
            "finding_id": self.id, "advisory_text": self.advisory or "", "package": self.package,
            "fix_summary": self.fix_summary, "searches": self.searches or [], "excerpts": self.excerpts or [],
        })

    def look_for_request(self) -> LookForRequest:
        return LookForRequest.model_validate({
            "finding_id": self.id, "advisory_text": self.advisory or "", "package": self.package,
            "fix_summary": self.fix_summary,
        })


def output_model(case: "GoldenCase"):
    if case.capability == "assess":
        return AssessmentOutput
    if case.capability == "look_for":
        return LookForOutput
    return {"risk_ticket": TicketOutput, "memo": MemoOutput}.get(case.draft_type, DraftOutput)


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
