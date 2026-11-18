#!/usr/bin/env bash
# 격리 촬영 스택의 DB·그래프 DB 를 비우고 API 를 다시 띄운다(시드는 빈 DB 에서만 돈다).
# 사용: JAR=<api jar> LOG=<로그 경로> docs/intro/scripts/reset-stack.sh
# DB 이미지는 운영과 같은 ghcr 이미지 — docker/postgres/Dockerfile 의 pgvector 버전 핀이 apt 에서 사라져 로컬 빌드가 안 된다.
set -euo pipefail
docker rm -f intro-data-db intro-data-neo4j intro-data-minio >/dev/null 2>&1 || true
docker run -d --name intro-data-db -e POSTGRES_DB=smartfirehub -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app \
  -p 5452:5432 ghcr.io/bluleo78/smart-fire-hub/postgres:latest >/dev/null
docker run -d --name intro-data-neo4j -e NEO4J_AUTH=neo4j/firehub-graph-dev \
  -p 7484:7474 -p 7697:7687 neo4j:5.26-community >/dev/null
# 파일 데이터셋용 오브젝트 저장소(MinIO). 개발 기본 9000 과 겹치지 않게 9010 을 쓴다.
# 앱은 버킷을 만들지 않는다 — 최상위 폴더가 곧 버킷이라 기동 전에 firehub-files 폴더를 만들어 둔다.
# 이미지는 개발 compose 와 같은 Chainguard 빌드(공식 minio 이미지는 배포 중단). 기본 사용자가 nonroot 라 root 로 돌린다.
docker run -d --name intro-data-minio -p 9010:9000 --user 0:0 -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  --entrypoint sh cgr.dev/chainguard/minio:latest -c 'mkdir -p /data/firehub-files && exec minio server /data' >/dev/null
sleep 5
"$(dirname "$0")/start-api.sh"
echo "stack ready"
