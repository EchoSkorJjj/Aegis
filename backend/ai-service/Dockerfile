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
# Stateless: no migrations. Defaults to the offline stub; set LLM_PROVIDER=groq and GROQ_API_KEY
# for real assessment/drafting. The base install includes the HTTP client and packaged prompts.
CMD ["sh", "-c", "uvicorn ai_service.main:app --host 0.0.0.0 --port ${SERVER_PORT:-8083}"]
