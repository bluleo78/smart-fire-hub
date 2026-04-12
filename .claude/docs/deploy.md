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
- 운영 디렉토리: `~/prod/smart-fire-hub/` — **로컬 머신** (`$HOME/prod/smart-fire-hub/`). SSH 불필요.
- 배포 후: `docker compose up -d --force-recreate {app}`

### 부분 배포 (빌드+push 완료 후 컨테이너만 재시작)

```bash
cd ~/prod/smart-fire-hub
docker compose pull ai-agent web      # 이미지 갱신
docker compose up -d --force-recreate ai-agent web
docker compose ps                      # 상태 확인
```

### deploy.sh 사용 (빌드+push+배포 한번에)

```bash
./scripts/deploy.sh ai-agent   # ai-agent만
./scripts/deploy.sh web        # web만
./scripts/deploy.sh all        # 전체 (api 포함)
```

> **주의**: deploy.sh는 `--no-cache` multiplatform 빌드를 수행하므로 시간이 오래 걸린다.
> 이미 이미지를 push한 경우 위의 부분 배포 방식이 더 빠르다.
