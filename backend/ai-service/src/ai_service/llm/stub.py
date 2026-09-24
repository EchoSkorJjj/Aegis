"""Offline deterministic outputs for the structured-schema subset used by capabilities."""

import json
import re

from ai_service.llm.base import LlmResult


class StubLlmClient:
    provider = "stub"
    model = "stub"

    async def generate_json(
        self, *, system: str, user: str, schema_name: str,
        json_schema: dict, max_output_tokens: int,
    ) -> LlmResult:
        def sample(node: dict):
            if "$ref" in node:
                target = json_schema
                for part in node["$ref"].removeprefix("#/").split("/"):
                    target = target[part]
                return sample(target)
            if "enum" in node:
                return "NONE" if "NONE" in node["enum"] else node["enum"][0]
            if "anyOf" in node:
                return sample(node["anyOf"][0])
            match node.get("type"):
                case "object":
                    return {key: sample(value) for key, value in node["properties"].items()}
                case "array":
                    return []
                case "boolean":
                    return False
                case "integer" | "number":
                    return 0
                case "null":
                    return None
                case _:
                    return "Development stub: no AI assessment or action has been performed."

        output = sample(json_schema)
        passages = re.findall(r'<passage id="([A-Z][0-9]+)">', user)
        if isinstance(output, dict) and "cited_passage_ids" in output and passages:
            output["cited_passage_ids"] = passages[:1]
        return LlmResult(json.dumps(output), self.provider, self.model, 0, 0)

    async def aclose(self) -> None:
        pass
