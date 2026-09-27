"""Provider-compatible schema generation preserves structure; local models enforce bounds."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from ai_service.capabilities.draft import MemoOutput, TicketOutput
from ai_service.llm.structured import strict_schema


class Nested(BaseModel):
    model_config = ConfigDict(extra="forbid")
    # These names must not be mistaken for metadata/keywords by recursive transformation.
    title: str = Field(min_length=1, max_length=10)
    default: str | None = None


class StructuredOutput(BaseModel):
    children: list[Nested]
    choice: Nested | None = None
    kind: Literal["sample"] = "sample"
    count: int = Field(default=1, ge=1, le=10)


def test_nested_refs_arrays_nullable_and_keyword_property_names():
    schema = strict_schema(StructuredOutput)
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"children", "choice", "kind", "count"}
    nested = schema["$defs"]["Nested"]
    assert nested["additionalProperties"] is False
    assert nested["required"] == ["title", "default"]
    assert nested["properties"]["title"] == {"type": "string"}
    assert nested["properties"]["default"]["anyOf"] == [
        {"type": "string"}, {"type": "null"},
    ]
    assert schema["properties"]["children"]["items"] == {"$ref": "#/$defs/Nested"}
    assert schema["properties"]["kind"]["enum"] == ["sample"]
    assert schema["properties"]["count"] == {"type": "integer"}


def test_transform_does_not_mutate_model_schema():
    before = StructuredOutput.model_json_schema()
    strict_schema(StructuredOutput)
    assert StructuredOutput.model_json_schema() == before


def test_ticket_and_memo_schemas_require_every_section_and_leave_bounds_to_local_validation():
    for model, sections in [(TicketOutput, {"summary", "risk", "impact", "actions", "cited_passage_ids"}),
                            (MemoOutput, {"summary", "impact", "measures", "next_steps", "cited_passage_ids"})]:
        schema = strict_schema(model)
        assert set(schema["required"]) == sections
        assert schema["additionalProperties"] is False
        assert "maxLength" not in str(schema) and "minItems" not in str(schema)
