"""Pass 1 of an assessment (E11-5): what to search the service's code for.

The model names functions, classes, types or settings from the advisory and what its fix changed, each labelled with
what it is to an application: a name the service's own code would write to reach the vulnerable part (``application``:
an API it calls, a type it declares or binds, an option it sets) or the package's own code (``internal`` — most of a
fix commit's names). Only application names are searched: a service's code never writes the package's internals, so
not finding them would prove nothing (call-graph tools follow internals through the library; a text search can't).
aegis-core then searches the service's code and sends what it finds to pass 2 (``assess``). A name is kept only if one
of those sources writes it, whole, and it is a plain name that asks for nothing secret — so the model can point the
search only at what the advisory itself names. Anything else is dropped, not refused: the remaining names still help.
"""

import re
from typing import TYPE_CHECKING, Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from ai_service.capabilities._grounding import check_input_size, written_as
from ai_service.capabilities._guardrails import check_sources, protect_output, redact
from ai_service.prompts import build_prompt
from ai_service.schemas import LookFor, LookForRequest

if TYPE_CHECKING:
    from ai_service.llm.structured import StructuredGenerator

MAX_TERMS = 5
# A handful of names: a short answer, so pass 1 leaves pass 2 its share of the per-minute token budget.
LOOK_FOR_OUTPUT_TOKENS = 400
_NAME = re.compile(r"[A-Za-z_$@][A-Za-z0-9_$.:/@-]{3,63}")
# Names that would point the search at credentials rather than at the vulnerable code.
_SECRET = re.compile(r"(?i)password|passwd|secret|credential|token|api[_-]?key|private[_-]?key|access[_-]?key")

class Term(BaseModel):
    """One name, and what it is to an application: ``application`` is listed first, so the offline stub's sample is
    one — and still dropped, since no source writes it."""

    model_config = ConfigDict(extra="forbid", strict=True)

    name: Annotated[str, StringConstraints(min_length=1, max_length=200)]
    role: Literal["application", "internal"]


class LookForOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    terms: Annotated[list[Term], Field(max_length=MAX_TERMS)]


async def look_for(request: LookForRequest, engine: "StructuredGenerator") -> LookFor:
    check_input_size(request, engine.settings.llm_max_input_chars)
    check_sources(request.advisory_text, request.package.ecosystem, request.package.name, request.fix_summary)
    identified = [("P1", f"{request.package.ecosystem} package {request.package.name}")]
    if request.fix_summary:
        identified.append(("F1", f"What the fix changed:\n{request.fix_summary}"))
    prompt = build_prompt("look_for", [("A", request.advisory_text)], passages=identified)

    output = await engine.generate(
        system=prompt.system,
        user=prompt.user,
        template_id=prompt.template_id,
        capability="look_for",
        output_model=LookForOutput,
        validate=protect_output,
        max_output_tokens=LOOK_FOR_OUTPUT_TOKENS,
    )
    # The sources as the model saw them (redacted, so a name redaction removed can't come back), whole: a name may
    # straddle two passages.
    sources = [redact(text) for text in (request.advisory_text, request.fix_summary) if text]
    package = request.package.name.strip().lower()
    names = (term.name.strip() for term in output.terms if term.role == "application")
    kept = [name for name in dict.fromkeys(names)
            if _NAME.fullmatch(name) and not _SECRET.search(name) and _written(name, sources)
            and name.lower() not in {package, package.rpartition(":")[2]}]
    return LookFor(terms=kept[:MAX_TERMS])


def _written(term: str, sources: list[str]) -> bool:
    """Whether a source writes the term — whole, or its words in prose ("Cache Component" for cacheComponents)."""
    pattern = written_as(term)
    return any(pattern.search(text) for text in sources)
