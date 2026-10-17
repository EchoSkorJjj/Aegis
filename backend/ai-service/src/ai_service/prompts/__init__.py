"""Versioned package resources and exact, bounded grounding passages.

Escaping keeps external text inside its data block. It is a defence in depth measure, not a
guarantee that a model will ignore an injection: output and citations must still be validated.
"""

from dataclasses import dataclass
from html import escape
from importlib.resources import files
from typing import Literal

from ai_service.capabilities._guardrails import redact

PASSAGE_CHARS = 1000


@dataclass(frozen=True)
class Prompt:
    template_id: str
    system: str
    user: str
    passages: dict[str, str]


# A template's version moves when its wording does, so metrics and logs tell the two apart.
VERSIONS = {"assess": "v6", "look_for": "v2"}


def build_prompt(
    capability: Literal["assess", "look_for", "draft"],
    sources: list[tuple[str, str | None]],
    *,
    draft_type: Literal["risk_ticket", "upgrade", "memo"] | None = None,
    passages: list[tuple[str, str]] | None = None,
) -> Prompt:
    """Redact whole sources before splitting; citations resolve to the same protected passages.

    ``passages`` are already identified (an assessment's code excerpts, fix summary and search results, each its own
    citation): each is redacted and kept whole under its own ID, after the split sources. Splitting numbers passages per prefix, so two sources
    sharing a prefix would collide; identified passages can't.
    """
    sources = [(prefix, redact(text)) for prefix, text in sources]
    split = {
        f"{prefix}{index // PASSAGE_CHARS + 1}": text[index : index + PASSAGE_CHARS]
        for prefix, text in sources
        if text
        for index in range(0, len(text), PASSAGE_CHARS)
    }
    identified = {passage_id: redact(text) for passage_id, text in passages or []}
    if split.keys() & identified.keys():
        raise ValueError("Identified passages must not reuse a split source's IDs")
    passages = {**split, **identified}
    blocks = "\n".join(
        f'<passage id="{passage_id}">{escape(text, quote=False)}</passage>'
        for passage_id, text in passages.items()
    )
    # A risk ticket and a memo each have their own template and output; an upgrade uses the generic one.
    template = f"draft_{draft_type}" if draft_type in ("risk_ticket", "memo") else capability
    resources = files(__package__)
    system = resources.joinpath(f"{template}.system.md").read_text(encoding="utf-8")
    user = resources.joinpath(f"{template}.user.md").read_text(encoding="utf-8").format(
        passages=blocks, draft_type=draft_type
    )
    return Prompt(f"{template}.{VERSIONS.get(template, 'v2')}", system, user, passages)
