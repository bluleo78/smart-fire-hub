# 배포 가이드

배포 스크립트: `./scripts/deploy.sh [api|web|ai-agent|all]`

## Docker 빌드 규칙 (중요)

각 앱의 Dockerfile은 **서로 다른 build context**를 사용한다. 잘못된 context로 빌드하면 소스가 누락된다.

| App | Build Context | 빌드 명령 |
|-----|---------------|----------|
| **firehub-api** | `apps/firehub-api/` (자체 디렉토리) | `docker build apps/firehub-api/` |
| **firehub-web** | `.` (프로젝트 루트) | `docker build -f apps/firehub-web/Dockerfile .` |
| **firehub-ai-agent** | `.` (프로젝트 루트) | `docker build -f apps/firehub-ai-agent/Dockerfile .` |

- **firehub-api**: Dockerfile 내부에서 `COPY src/ src/` 상대 경로 → context가 `apps/firehub-api/`여야 함
- **firehub-web/ai-agent**: `COPY apps/firehub-web/ ...` 절대 경로 → context가 프로젝트 루트(`.`)여야 함
- **절대로** `docker build -f apps/firehub-api/Dockerfile .`으로 빌드하지 않는다 (소스 누락)
- 반드시 `--no-cache` 옵션을 사용한다

## 운영 환경

- 이미지 레지스트리: `ghcr.io/bluleo78/smart-fire-hub/{api,web,ai-agent}:latest`
- 운영 디렉토리: `~/prod/smart-fire-hub/` (docker-compose.yml + nginx.conf + .env)
- 배포 후: `docker compose up -d --force-recreate {app}`
