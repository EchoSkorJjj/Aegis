"""Whether the service's code uses the vulnerable function, feature or condition of a package it imports (E11-5).

The answer is a suggestion beside the score, for a person to weigh; it never changes the score or decides anything.
The model sees the advisory, the package, what the advisory's fix changed, aegis-core's search of the code for names
the advisory or fix writes (pass 1, ``look_for``) and a few lines of the code — where it imports the package, uses
what it imports, or mentions a searched name — and cites them by ID.
"""

import re
from typing import TYPE_CHECKING, Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from ai_service.capabilities._grounding import (
    CitedPassageIds,
    check_input_size,
    validate_citations,
    written_as,
)
from ai_service.capabilities._guardrails import check_sources, protect_output
from ai_service.prompts import build_prompt
from ai_service.schemas import Assessment, AssessmentRequest, CodeExcerpt, Search

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator

Check = Annotated[str, StringConstraints(min_length=1, max_length=200)]
SEARCH_PASSAGE = "S1"
# A verdict, a short rationale and up to three checks. With pass 1 (look_for), one assessment fits the default
# per-minute token budget even at the request's largest.
ASSESS_OUTPUT_TOKENS = 1100


class AssessmentOutput(BaseModel):
    """Internal model output; citation IDs never become part of the wire contract as text.

    ``CANT_TELL`` is listed first: the offline stub answers an enum's first value, and it must never claim either.
    """

    model_config = ConfigDict(extra="forbid", strict=True)

    verdict: Literal["CANT_TELL", "CALLED", "NOT_CALLED"]
    rationale: str = Field(min_length=1, max_length=1200)
    checks: Annotated[list[Check], Field(max_length=3)]
    cited_passage_ids: CitedPassageIds


async def assess(request: AssessmentRequest, engine: "StructuredGenerator") -> Assessment:
    check_input_size(request, engine.settings.llm_max_input_chars)
    check_sources(
        request.advisory_text, request.package.ecosystem, request.package.name, request.fix_summary,
        *(search.term for search in request.searches),
        *(text for excerpt in request.excerpts for text in (excerpt.path, excerpt.text, excerpt.term)),
    )
    excerpts = {excerpt.id: excerpt for excerpt in request.excerpts}
    identified = [("P1", f"{request.package.ecosystem} package {request.package.name}")]
    if request.fix_summary:
        identified.append(("F1", f"What the fix changed:\n{request.fix_summary}"))
    if request.searches:
        identified.append((SEARCH_PASSAGE, _searched(request.searches, request.package.ecosystem)))
    identified += [(excerpt.id, _labelled(excerpt)) for excerpt in request.excerpts]
    prompt = build_prompt("assess", [("A", request.advisory_text)], passages=identified)

    def validate(output: AssessmentOutput) -> None:
        answered = output.verdict != "CANT_TELL"
        validate_citations(output.cited_passage_ids, prompt.passages, required=answered)
        cites_code = any(passage_id in excerpts for passage_id in output.cited_passage_ids)
        cites_search = SEARCH_PASSAGE in output.cited_passage_ids
        if output.verdict == "CALLED" and not cites_code:
            raise ValueError("A called answer must cite the code it read")
        if output.verdict == "NOT_CALLED" and not (cites_code or cites_search):
            raise ValueError("A not-called answer must cite the code it read or the search")
        protect_output(output)

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="assess",
        output_model=AssessmentOutput,
        validate=validate,
        max_output_tokens=ASSESS_OUTPUT_TOKENS,
    )
    return Assessment(
        verdict=output.verdict,
        rationale=output.rationale,
        checks=_unsettled(output.checks, request.searches),
        cited_excerpts=[passage_id for passage_id in dict.fromkeys(output.cited_passage_ids)
                        if passage_id in excerpts],
    )


_WHY = {"import": "imports the package", "use": "uses what it imports"}
# aegis-core searches a qualified type (java.nio.file.Path) through its package's wildcard import too.
_QUALIFIED_TYPE = re.compile(r"(?:[a-z_]\w*\.)+[A-Z]\w*")


def _labelled(excerpt: CodeExcerpt) -> str:
    """The excerpt under a ``path:first-last [why]`` header, so a citation names the lines it rests on."""
    last = excerpt.start_line + max(len(excerpt.text.splitlines()), 1) - 1
    why = _WHY.get(excerpt.kind) or f"mentions {excerpt.term}"
    return f"{excerpt.path}:{excerpt.start_line}-{last} [{why}]\n{excerpt.text}"


def _searched(searches: list[Search], ecosystem: str) -> str:
    """aegis-core's search, one line per term: what it found where, and whether every file was read. For Java and
    Kotlin a qualified type can only be used through its import, its package's wildcard import or its full name — all
    three searched — so finding none in a complete search means no class can use it, and the passage says so."""
    lines = ["Aegis searched the service's code for names an application writes to use what the advisory describes:"]
    for search in searches:
        if search.lines_matched == 0:
            found = f"no match in {_count(search.files_searched, 'file')}"
            if search.complete and ecosystem.lower() == "maven" and _QUALIFIED_TYPE.fullmatch(search.term):
                found += ", so no class in the service's code can use this type"
        else:
            found = (f"{_count(search.lines_matched, 'line')} in {search.files_matched} of "
                     f"{_count(search.files_searched, 'file')}")
        stopped = "" if search.complete else " (the search stopped early, so some files weren't read)"
        wildcard = " (its package's wildcard imports too)" if _QUALIFIED_TYPE.fullmatch(search.term) else ""
        lines.append(f"- {search.term}{wildcard}: {found}{stopped}")
    return "\n".join(lines)


def _unsettled(checks: list[str], searches: list[Search]) -> list[str]:
    """The checks still open: one about a name a complete search found nowhere in the code — written as the name or
    as its words ("the Cache Component") — is already settled."""
    nowhere = [re.compile(written_as(search.term).pattern, re.IGNORECASE)
               for search in searches if search.complete and search.lines_matched == 0]
    return [check for check in checks if not any(name.search(check) for name in nowhere)]


def _count(number: int, noun: str) -> str:
    return f"{number} {noun}{'' if number == 1 else 's'}"
