#!/usr/bin/env bash
# Starts Postgres and the application with a known-good JDK and the demo profile.
#
# Lombok cannot run under JDK 23 (the default on our machines), which makes `mvn compile`
# fail with a wall of "cannot find symbol" errors. Pin the JDK here so a cold terminal works.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export JAVA_HOME
echo "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# A container called aml-postgres may already exist from an earlier manual `docker run`.
# Compose pins the same container_name, so it would refuse to start. Adopt the running one
# if it is healthy; otherwise hand it over to Compose.
if docker ps --format '{{.Names}}' | grep -qx 'aml-postgres'; then
  echo "Postgres: reusing running container aml-postgres"
elif docker ps -a --format '{{.Names}}' | grep -qx 'aml-postgres'; then
  managed="$(docker inspect aml-postgres --format '{{index .Config.Labels "com.docker.compose.project"}}')"
  if [ -z "$managed" ]; then
    echo "Postgres: removing stopped unmanaged container so Compose can own it"
    docker rm -f aml-postgres >/dev/null
  fi
  docker compose up -d postgres
else
  docker compose up -d postgres
fi

until docker exec aml-postgres pg_isready -U aml -d aml >/dev/null 2>&1; do
  echo "waiting for postgres..."; sleep 2
done
echo "Postgres: ready"

# The demo profile is what exposes POST /api/demo/reset.
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
