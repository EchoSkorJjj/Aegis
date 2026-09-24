"""Provider-compatible schema generation preserves structure; local models enforce bounds."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

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
