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

## OpenCode 에이전트(`ai.agent_type=opencode`) 운영 요건

설정 화면에서 AI 옵션을 **OpenCode**로 선택하면 ai-agent 컨테이너가 `opencode run` 서브프로세스로 채팅을 처리한다. 운영 시 아래가 갖춰져야 동작한다.

1. **바이너리**: ai-agent 이미지에 `opencode` CLI 포함됨 (Dockerfile 에서 `npm install -g opencode-ai`). 별도 조치 불필요.
2. **모델 인증 (옵션 3 — 앱이 키를 받지 않음)**: OpenCode → 모델 provider 인증은 **배포 환경의 전역 opencode 설정/환경변수**에 의존한다. 앱 설정 화면에는 키 입력란이 없다(의도적). 다음 중 하나로 구성한다.
   - 전역 설정 파일을 컨테이너에 마운트: `~/.config/opencode/opencode.json` (또는 `OPENCODE_CONFIG` 로 경로 지정) 에 provider/model 정의. 예(OpenAI-호환 Bedrock 게이트웨이):
     ```json
     { "provider": { "<name>": { "npm": "@ai-sdk/openai-compatible",
         "options": { "baseURL": "<gateway>/openai/v1", "apiKey": "<KEY>" },
         "models": { "<model-id>": {} } } },
       "model": "<name>/<model-id>" }
     ```
   - 또는 provider별 표준 환경변수(`ANTHROPIC_API_KEY`, `AWS_BEARER_TOKEN_BEDROCK` 등)를 컨테이너 env 로 주입.
   - 미구성 시 채팅은 명확한 `error` SSE 로 종료된다.
3. **firehub 도구 인증**: 별도 조치 불필요 — ai-agent 가 요청별 `opencode.json` 의 `mcp.firehub.environment` 로 `INTERNAL_SERVICE_TOKEN`/`USER_ID` 를 주입한다(사용자별 격리). opencode 본체 env 에서는 내부 토큰이 제거된다.
4. **도구 권한**: 요청별 `opencode.json` 이 빌트인 도구를 비활성(`tools`)하고 `permission` 으로 `firehub_*` 만 허용한다(채팅에서 bash/파일/네트워크 접근 차단).

### 게이트웨이 스키마 호환 (`propertyNames` 자동 제거)

일부 OpenAI-호환 게이트웨이(예: Bedrock OpenAI-호환 엔드포인트)는 JSON Schema 의 **`propertyNames`** 키를 거부해, 해당 키가 포함된 도구 정의가 실린 요청을 `400 Generation failed` 로 반려한다(2026-06-24 실측: firehub 의 `z.record(z.string(), …)` 파라미터가 `propertyNames` 를 내보냄 — `add_row` 등). Anthropic API 직결(`sdk`/`cli`/`cli-api`)은 영향 없음.

→ **자동 처리됨**: OpenCode 경로는 stdio MCP 서버에 `OPENCODE_SCHEMA_COMPAT=1` 을 주입해 tools/list 응답 스키마에서 `propertyNames` 를 재귀 제거한다(`src/mcp/schema-compat.ts`). `propertyNames`(키는 문자열)는 JSON 키가 항상 문자열이라 의미상 잉여이므로 제거해도 동작 손실이 없다. 실측상 이 정제 후 firehub 전체 도구셋(88개)이 게이트웨이를 통과한다.

> 다른 게이트웨이가 `propertyNames` 외 다른 스키마 키워드를 거부할 경우, 같은 `schema-compat.ts` 의 `stripPropertyNames` 패턴을 확장하면 된다.
