"""Aegis AI Service — the internal, Python-only AI capability.

Two grounded endpoints, called by aegis-core over REST with a service token: **assess** (signal vs
noise, reachability/exposure reasoning, suggested severity) and **draft** (risk ticket, upgrade
suggestion, memo), each with RAG + citations. Stateless for the MVP; gains its own vector store when
the evidence corpus grows. See the root ARCHITECTURE.md.
"""

__version__ = "0.1.0"
