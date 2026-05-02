# 배포 가이드

배포 스크립트: `./scripts/deploy.sh [api|executor|web|ai-agent|channel|all]`

> `all` = **api + executor + web + ai-agent + channel** (운영 5개 앱 전부).
> `all` 정의는 **3곳**에서 동기화 필요: `scripts/deploy.sh`, `scripts/update.sh`, 본 문서.
> 운영 docker-compose 서비스명이 빌드 키와 다른 경우(`channel` → `firehub-channel`)는 두 스크립트의 `prod_service_name()` 헬퍼가 흡수한다.

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
- 빌드 캐시는 buildx 가 자동 사용 (525849d 이후). 캐시를 강제로 무시할 필요가 있을 때만 `--no-cache` 추가.

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

> deploy.sh 는 buildx 캐시를 사용하므로 두 번째 빌드부터 단축된다.
> 이미 이미지를 push 한 경우 위의 부분 배포 방식이 더 빠르다.
