"""The request/response contract — the OpenAPI schema aegis-core's AI-port DTOs mirror.

Kept at the top level because it is the service's public contract, shared by the API layer and the
capabilities. Snake_case on the wire.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class PackageRef(ContractModel):
    ecosystem: str = Field(min_length=1, max_length=32)
    name: str = Field(min_length=1, max_length=214)


class CodeExcerpt(ContractModel):
    """A few lines of the service's code — never a whole file — and why they were sent: where it imports the package,
    where it uses what it imports, or where it mentions ``term``, something the advisory or its fix names."""

    id: str = Field(pattern=r"^E[1-6]$")
    path: str = Field(min_length=1, max_length=300)
    start_line: int = Field(ge=1)
    text: str = Field(min_length=1, max_length=600)
    kind: Literal["import", "use", "match"]
    term: str | None = Field(default=None, min_length=1, max_length=64)

    @model_validator(mode="after")
    def a_match_names_its_term(self):
        if (self.kind == "match") != (self.term is not None):
            raise ValueError("A match names the term it matched, and only a match does")
        return self


class Search(ContractModel):
    """What aegis-core's search of the service's code for one term found: a fact, not code."""

    term: str = Field(min_length=1, max_length=64)
    files_searched: int = Field(ge=0)
    files_matched: int = Field(ge=0)
    lines_matched: int = Field(ge=0)
    complete: bool


class AssessmentRequest(ContractModel):
    """Whether the service's code uses the vulnerable function, feature or condition of a package it imports (E11-5)."""

    finding_id: str
    advisory_text: str = Field(max_length=2500)
    package: PackageRef
    fix_summary: str | None = Field(default=None, max_length=1200)
    searches: list[Search] = Field(default_factory=list, max_length=5)
    excerpts: list[CodeExcerpt] = Field(min_length=1, max_length=6)

    @model_validator(mode="after")
    def unique_excerpts(self):
        if len({excerpt.id for excerpt in self.excerpts}) != len(self.excerpts):
            raise ValueError("Excerpt IDs must be unique")
        return self


class Assessment(ContractModel):
    """A suggestion for a person, never a decision; the excerpts it cites are named by the caller's own IDs, and
    ``checks`` are what a reviewer should verify that the code it read can't show."""

    verdict: Literal["CANT_TELL", "CALLED", "NOT_CALLED"]
    rationale: str
    checks: list[str] = Field(default_factory=list)
    cited_excerpts: list[str] = Field(default_factory=list)


class LookForRequest(ContractModel):
    """Pass 1 of an assessment: what to search the service's code for, from the advisory and what its fix changed."""

    finding_id: str
    advisory_text: str = Field(max_length=2500)
    package: PackageRef
    fix_summary: str | None = Field(default=None, max_length=1200)


class LookFor(ContractModel):
    """Names the advisory or its fix writes — functions, classes, settings — for aegis-core to search the code for."""

    terms: list[str] = Field(default_factory=list)


class CodeAssessmentFact(ContractModel):
    """What ``/v1/assess`` already concluded about this package in this service's code, when someone has asked."""

    verdict: Literal["CANT_TELL", "CALLED", "NOT_CALLED"]
    rationale: str = Field(min_length=1, max_length=1200)


class InterpretationRequest(ContractModel):
    """A material finding, for the AI to read with nobody asking: the change itself and what Aegis already knows about
    it. Facts only: never code, the service's name or its owners, which is why extra fields are refused."""

    finding_id: str
    kind: Literal["VULNERABILITY", "VENDOR_OUTAGE"]
    change_text: str = Field(min_length=1, max_length=2500)
    package: PackageRef | None = None
    installed_version: str | None = Field(default=None, max_length=64)
    fixed_version: str | None = Field(default=None, max_length=64)
    dependency_path: list[str] = Field(default_factory=list, max_length=8)
    import_status: Literal["IMPORTED", "NOT_IMPORTED", "UNKNOWN"] | None = None
    vendor: str | None = Field(default=None, max_length=200)
    severity: str | None = Field(default=None, max_length=32)
    code_assessment: CodeAssessmentFact | None = None
    exposure: str = Field(min_length=1, max_length=32)
    criticality: str = Field(min_length=1, max_length=32)
    tier: str = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def a_vulnerability_names_its_package(self):
        if self.kind == "VULNERABILITY" and (self.package is None or self.import_status is None):
            raise ValueError("A vulnerability names its package and what is known about its import")
        return self


class Interpretation(ContractModel):
    """What a finding means for one service, as a suggestion for a person: it never changes a score or decides
    anything. ``citations`` are the advisory's own passages, resolved by the server."""

    signal: Literal["UNCERTAIN", "LIKELY_REAL", "LIKELY_NOISE"]
    confidence: Literal["LOW", "MEDIUM", "HIGH"]
    what_changed: str
    why_it_matters: str
    what_next: str
    suggested_action: Literal["VERIFY", "ACT_NOW", "SCHEDULE", "MONITOR"]
    action_detail: str
    condition: str
    condition_met: Literal["UNKNOWN", "YES", "NO"]
    assumptions: list[str] = Field(default_factory=list)
    citations: list[str] = Field(default_factory=list)


class DraftRequest(ContractModel):
    finding_id: str
    draft_type: Literal["risk_ticket", "upgrade", "memo"]
    context: str | None = None


class TicketSections(ContractModel):
    """A risk ticket's narrative fields. Its priority, due date and facts are aegis-core's, not the model's."""

    summary: str
    risk: str
    impact: str
    actions: list[str]


class MemoSections(ContractModel):
    """A DORA notification memo's narrative sections; aegis-core's template supplies every fact around them."""

    summary: str
    impact: str
    measures: str
    next_steps: str


class Draft(ContractModel):
    draft_type: str
    # The whole draft as readable text, whatever its type; the typed sections below are for the types
    # whose document aegis-core assembles itself.
    content: str
    ticket: TicketSections | None = None
    memo: MemoSections | None = None
    citations: list[str] = Field(default_factory=list)
