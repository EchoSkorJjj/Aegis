# ---- Aegis AI Service (Python / FastAPI) ----
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

COPY pyproject.toml ./
COPY src ./src
RUN pip install .

EXPOSE 8083
# Stateless: no migrations. (Add `.[llm]` to the install and set LLM_PROVIDER=anthropic to enable
# real drafting once the prompts are implemented.)
CMD ["sh", "-c", "uvicorn ai_service.main:app --host 0.0.0.0 --port ${SERVER_PORT:-8083}"]
