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


def build_prompt(
    capability: Literal["assess", "draft"],
    sources: list[tuple[str, str | None]],
    *,
    draft_type: Literal["risk_ticket", "upgrade", "memo"] | None = None,
) -> Prompt:
    """Redact whole sources before splitting; citations resolve to the same protected passages."""
    sources = [(prefix, redact(text)) for prefix, text in sources]
    passages = {
        f"{prefix}{index // PASSAGE_CHARS + 1}": text[index : index + PASSAGE_CHARS]
        for prefix, text in sources
        if text
        for index in range(0, len(text), PASSAGE_CHARS)
    }
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
    return Prompt(f"{template}.v2", system, user, passages)
