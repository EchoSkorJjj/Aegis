# ---- Aegis AI Service (Python / FastAPI) ----
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

# Dependencies first, read from pyproject.toml alone (its [project] dependencies and the build backend), so this layer
# stays cached until they change: a change to the code under src/ reinstalls only the service itself.
COPY pyproject.toml ./
RUN python -c "import tomllib; p = tomllib.load(open('pyproject.toml', 'rb')); \
print('\n'.join(p['project']['dependencies'] + p['build-system']['requires']))" > /tmp/requirements.txt \
    && pip install -r /tmp/requirements.txt \
    && rm /tmp/requirements.txt

COPY src ./src
RUN pip install --no-deps --no-build-isolation .

EXPOSE 8083
# Stateless: no migrations. Defaults to the offline stub; set LLM_PROVIDER=groq and GROQ_API_KEY
# for real assessment/drafting. The base install includes the HTTP client and packaged prompts.
CMD ["sh", "-c", "uvicorn ai_service.main:app --host 0.0.0.0 --port ${SERVER_PORT:-8083}"]
