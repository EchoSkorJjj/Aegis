"""What a material finding means for one service, read by the AI with nobody asking.

Matching and scoring say a change reaches a service; this says why that matters there, what is likely to happen and
what to do, from the change's own text and the facts Aegis already holds. It never reads code (``assess`` does, when
someone asks) and it never changes a score or decides anything.

Overstating is the failure that hurts, in either direction: calling a real problem noise, or treating "the code imports
it" as proof that the bug applies, since most advisories also need a setting, a proxy or a particular use. So the model
must name the condition, and its labels are limited by what is actually known. A code assessment someone already asked
for outranks it. These limits are applied after it answers rather than refused, since the rest of the answer holds.
"""

from typing import TYPE_CHECKING, Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from ai_service.capabilities._grounding import (
    CitedPassageIds,
    check_input_size,
    resolve_citations,
    validate_citations,
)
from ai_service.capabilities._guardrails import check_sources, protect_output
from ai_service.prompts import build_prompt
from ai_service.schemas import Interpretation, InterpretationRequest

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator

Assumption = Annotated[str, StringConstraints(min_length=1, max_length=200)]
FACTS_PASSAGE = "F1"
# A short reading: this runs on every material finding and shares the per-minute budget with drafts.
INTERPRET_OUTPUT_TOKENS = 800

_CODE = {
    "IMPORTED": "imports this package",
    "NOT_IMPORTED": "does not import this package, nor anything that pulls it in",
    "UNKNOWN": "was not read, or Aegis can't tell whether it uses this package",
}
_ASSESSED = {
    "CALLED": "CALLED: the code uses the vulnerable part",
    "NOT_CALLED": "NOT_CALLED: the code does not use the vulnerable part",
    "CANT_TELL": "CANT_TELL: reading the code could not settle whether it uses the vulnerable part",
}


class InterpretationOutput(BaseModel):
    """The model's answer. The first value of each choice is the most cautious: the offline stub answers it, and it
    must claim nothing."""

    model_config = ConfigDict(extra="forbid", strict=True)

    signal: Literal["UNCERTAIN", "LIKELY_REAL", "LIKELY_NOISE"]
    confidence: Literal["LOW", "MEDIUM", "HIGH"]
    what_changed: str = Field(min_length=1, max_length=600)
    why_it_matters: str = Field(min_length=1, max_length=900)
    what_next: str = Field(min_length=1, max_length=600)
    suggested_action: Literal["VERIFY", "ACT_NOW", "SCHEDULE", "MONITOR"]
    action_detail: str = Field(min_length=1, max_length=400)
    condition: str = Field(min_length=1, max_length=400)
    condition_met: Literal["UNKNOWN", "YES", "NO"]
    assumptions: Annotated[list[Assumption], Field(max_length=3)]
    cited_passage_ids: CitedPassageIds


async def interpret(request: InterpretationRequest, engine: "StructuredGenerator") -> Interpretation:
    check_input_size(request, engine.settings.llm_max_input_chars)
    check_sources(
        request.change_text, request.vendor, request.installed_version, request.fixed_version, *request.dependency_path,
        request.code_assessment.rationale if request.code_assessment else None,
        *((request.package.ecosystem, request.package.name) if request.package else ()),
    )
    prompt = build_prompt("interpret", [("A", request.change_text)], passages=[(FACTS_PASSAGE, _facts(request))])

    def validate(output: InterpretationOutput) -> None:
        validate_citations(output.cited_passage_ids, prompt.passages, required=False)
        if output.signal != "UNCERTAIN" and not _advisory(output.cited_passage_ids):
            raise ValueError("A real or noise label must cite the change's own text")
        protect_output(output)

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="interpret",
        output_model=InterpretationOutput,
        validate=validate,
        max_output_tokens=INTERPRET_OUTPUT_TOKENS,
    )
    signal = _signal(output, request)
    return Interpretation(
        signal=signal,
        confidence=_confidence(output, request, signal),
        what_changed=output.what_changed,
        why_it_matters=output.why_it_matters,
        what_next=output.what_next,
        suggested_action="VERIFY" if signal == "UNCERTAIN" and output.suggested_action == "ACT_NOW"
        else output.suggested_action,
        action_detail=output.action_detail,
        condition=output.condition,
        condition_met=output.condition_met,
        assumptions=output.assumptions,
        citations=resolve_citations(_advisory(output.cited_passage_ids), prompt.passages),
    )


def _signal(output: InterpretationOutput, request: InterpretationRequest) -> str:
    """The label the evidence allows. A code assessment that answered decides which way it can go, and one that
    couldn't tell leaves it uncertain. Without one, noise needs Aegis to have found no import, and real needs the
    sources to show the condition holds."""
    verdict = request.code_assessment.verdict if request.code_assessment else None
    if verdict == "CANT_TELL":
        return "UNCERTAIN"
    if verdict == "CALLED":
        return "UNCERTAIN" if output.signal == "LIKELY_NOISE" else output.signal
    if verdict == "NOT_CALLED":
        return "UNCERTAIN" if output.signal == "LIKELY_REAL" else output.signal
    if output.signal == "LIKELY_NOISE" and request.import_status != "NOT_IMPORTED":
        return "UNCERTAIN"
    if output.signal == "LIKELY_REAL" and output.condition_met != "YES":
        return "UNCERTAIN"
    return output.signal


def _confidence(output: InterpretationOutput, request: InterpretationRequest, signal: str) -> str:
    """High confidence needs the question settled: for a vulnerability, by someone's code assessment that answered.
    An outage has no code to read."""
    answered = request.code_assessment is not None and request.code_assessment.verdict != "CANT_TELL"
    settled = request.kind == "VENDOR_OUTAGE" or answered
    return "MEDIUM" if output.confidence == "HIGH" and (signal == "UNCERTAIN" or not settled) else output.confidence


def _advisory(cited: list[str]) -> list[str]:
    return [passage_id for passage_id in cited if passage_id != FACTS_PASSAGE]


def _facts(request: InterpretationRequest) -> str:
    """What Aegis knows about this finding, one fact to a line. Passed as data, so the model can cite it."""
    lines = []
    if request.package:
        installed = f" {request.installed_version}" if request.installed_version else ""
        lines.append(f"Affected package: {request.package.ecosystem} {request.package.name}{installed}")
        lines.append(f"Fixed in: {request.fixed_version or 'no fixed version known'}")
        if request.dependency_path:
            lines.append("Comes in through: " + " -> ".join(request.dependency_path))
        else:
            lines.append("Declared directly by the service")
        lines.append(f"The service's code {_CODE[request.import_status]}")
    if request.code_assessment:
        lines.append(f"A code assessment already concluded {_ASSESSED[request.code_assessment.verdict]}. "
                     f"Its reasoning: {request.code_assessment.rationale}")
    if request.vendor:
        lines.append(f"Vendor with the outage: {request.vendor}")
    if request.severity:
        lines.append(f"Published severity: {request.severity}")
    lines.append(f"The service's exposure: {request.exposure}")
    lines.append(f"The service's business criticality: {request.criticality}")
    lines.append(f"Impact tier Aegis scored: {request.tier}")
    return "\n".join(lines)
