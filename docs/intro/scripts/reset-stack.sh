#!/usr/bin/env bash
# 격리 촬영 스택의 DB·그래프 DB 를 비우고 API 를 다시 띄운다(시드는 빈 DB 에서만 돈다).
# 사용: JAR=<api jar> LOG=<로그 경로> docs/intro/scripts/reset-stack.sh
# DB 이미지는 운영과 같은 ghcr 이미지 — docker/postgres/Dockerfile 의 pgvector 버전 핀이 apt 에서 사라져 로컬 빌드가 안 된다.
set -euo pipefail
docker rm -f intro-data-db intro-data-neo4j >/dev/null 2>&1 || true
docker run -d --name intro-data-db -e POSTGRES_DB=smartfirehub -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app \
  -p 5452:5432 ghcr.io/bluleo78/smart-fire-hub/postgres:latest >/dev/null
docker run -d --name intro-data-neo4j -e NEO4J_AUTH=neo4j/firehub-graph-dev \
  -p 7484:7474 -p 7697:7687 neo4j:5.26-community >/dev/null
sleep 5
"$(dirname "$0")/start-api.sh"
echo "stack ready"
