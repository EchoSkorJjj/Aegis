"""The AI capabilities Aegis exposes: assess and draft. Each is a thin module today (a stub result);
in BUILD_PLAN step 7 each grows the same shape — run guardrails on the input, retrieve grounding
(RAG), call the LLM via the provider port, and return a structured, cited result."""
