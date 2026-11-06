#!/usr/bin/env bash
# Starts the whole backend for the full-stack journey, or stops it:
#   e2e-full-stack/stack.sh up      (SKIP_BUILD=1 reuses the jars and the ai-service's virtualenv)
#   e2e-full-stack/stack.sh down
# The services run on this machine, not in Compose: the real Jira adapter only accepts plain HTTP on loopback, so
# aegis-core must share a loopback address with the fake Atlassian. Playwright starts the frontend itself.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
RUN="${AEGIS_FULL_STACK_RUN_DIR:-${TMPDIR:-/tmp}/aegis-full-stack}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:3100}"
UPSTREAMS="http://127.0.0.1:${FAKE_UPSTREAMS_PORT:-9099}"
COMPOSE=(docker compose -f "$HERE/docker-compose.yml")

stop() {
  for pidfile in "$RUN"/*.pid; do
    [ -e "$pidfile" ] || continue
    kill "$(cat "$pidfile")" 2>/dev/null || true
    rm -f "$pidfile"
  done
  "${COMPOSE[@]}" down --volumes --remove-orphans
}

jar_of() {
  find "$ROOT/backend/$1/target" -maxdepth 1 -name "$1-*.jar" ! -name '*-sources.jar' | head -1
}

start() { # name, then the command
  local name="$1"; shift
  nohup "$@" > "$RUN/$name.log" 2>&1 &
  echo $! > "$RUN/$name.pid"
}

wait_for() { # name, url
  for _ in $(seq 1 120); do
    if curl -sf "$2" > /dev/null; then echo "$1 is up"; return 0; fi
    if ! kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; then break; fi
    sleep 2
  done
  echo "$1 did not start:"; tail -n 60 "$RUN/$1.log"; exit 1
}

up() {
  mkdir -p "$RUN"
  if [ "${SKIP_BUILD:-0}" != "1" ]; then
    for service in identity-service gateway aegis-core; do
      mvn -q -B -ntp -f "$ROOT/backend/$service/pom.xml" -DskipTests package
    done
    python3.12 -m venv "$RUN/ai-venv"
    "$RUN/ai-venv/bin/pip" install -q "$ROOT/backend/ai-service"
  fi

  "${COMPOSE[@]}" up -d --wait

  local db=jdbc:postgresql://127.0.0.1:55432/identity
  start fake-upstreams node "$HERE/fake-upstreams.mjs"
  wait_for fake-upstreams "$UPSTREAMS/health"

  DB_URL="$db" DB_USER=identity DB_PASSWORD=identity TRACING_SAMPLE_RATE=0 \
  SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=51025 \
  IDENTITY_FRONTEND_BASE_URL="$FRONTEND_URL" \
  IDENTITY_INTERNAL_SERVICE_TOKEN=full-stack-identity-token \
  IDENTITY_BOOTSTRAP_ADMIN_EMAIL=admin@example.com IDENTITY_BOOTSTRAP_ADMIN_PASSWORD=admin-dev-password \
    start identity-service java -jar "$(jar_of identity-service)"
  wait_for identity-service http://127.0.0.1:8081/actuator/health

  SERVICE_TOKEN=full-stack-ai-token LLM_PROVIDER=stub GROQ_API_KEY="" SERVER_PORT=8083 \
    start ai-service "$RUN/ai-venv/bin/uvicorn" ai_service.main:app --host 127.0.0.1 --port 8083 --no-access-log
  wait_for ai-service http://127.0.0.1:8083/actuator/health

  # Everything outside Aegis is faked: NVD, whose ingestion polls every few seconds so the journey isn't left
  # waiting (as for owner notifications), and Atlassian. AEGIS_SECRETS_KEY is random per run; it seals only the
  # fake's tokens.
  DB_URL="$db" DB_USER=identity DB_PASSWORD=identity TRACING_SAMPLE_RATE=0 \
  IDENTITY_JWKS_URI=http://127.0.0.1:8081/oauth2/jwks IDENTITY_BASE_URL=http://127.0.0.1:8081 \
  IDENTITY_SERVICE_TOKEN=full-stack-identity-token \
  AI_SERVICE_URI=http://127.0.0.1:8083 AI_SERVICE_TOKEN=full-stack-ai-token \
  AEGIS_INGESTION_NVD_ENABLED=true AEGIS_NVD_BASE_URL="$UPSTREAMS/nvd" AEGIS_INGESTION_NVD_ALLOW_PRIVATE_HOSTS=true \
  AEGIS_INGESTION_POLL_INTERVAL=PT3S AEGIS_NVD_NORMALIZE_INTERVAL=PT3S \
  AEGIS_JIRA_ENABLED=true AEGIS_JIRA_CLIENT_ID=full-stack-client AEGIS_JIRA_CLIENT_SECRET=full-stack-fake \
  AEGIS_JIRA_AUTH_URL="$UPSTREAMS" AEGIS_JIRA_API_URL="$UPSTREAMS" \
  AEGIS_JIRA_CALLBACK_URL="$FRONTEND_URL/admin/integrations/jira/callback" \
  AEGIS_SECRETS_KEY="$(openssl rand -base64 32)" \
  AEGIS_NOTIFICATIONS_ENABLED=true AEGIS_NOTIFICATIONS_INTERVAL=PT5S AEGIS_NOTIFICATIONS_INITIAL_DELAY=PT5S \
    start aegis-core java -jar "$(jar_of aegis-core)"
  wait_for aegis-core http://127.0.0.1:8082/actuator/health

  IDENTITY_URI=http://127.0.0.1:8081 CORE_URI=http://127.0.0.1:8082 \
  IDENTITY_JWKS_URI=http://127.0.0.1:8081/oauth2/jwks TRACING_SAMPLE_RATE=0 \
    start gateway java -jar "$(jar_of gateway)"
  wait_for gateway http://127.0.0.1:8080/actuator/health
}

case "${1:-}" in
  up) stop > /dev/null 2>&1 || true; up ;;
  down) stop ;;
  *) echo "usage: $0 up|down" >&2; exit 2 ;;
esac
