# 배포 가이드

배포 스크립트: `./scripts/deploy.sh [api|executor|web|ai-agent|channel|db|minio|all]`

> `all` = **api + executor + web + ai-agent + channel** (운영 5개 앱 전부, **db·minio는 제외**).
> `all` 정의는 **3곳**에서 동기화 필요: `scripts/deploy.sh`, `scripts/update.sh`, 본 문서.
> 운영 docker-compose 서비스명이 빌드 키와 다른 경우(`channel` → `firehub-channel`)는 두 스크립트의 `prod_service_name()` 헬퍼가 흡수한다.
> `db`, `minio`는 stateful/변경이 드문 서비스로 재기동 시 데이터·서비스 전체에 영향을 주므로 `all`에서 제외 — 각각 `./scripts/deploy.sh db`, `./scripts/deploy.sh minio`로 개별 배포만 가능.
> `minio`는 **public 이미지**(`minio/minio`)라 빌드/push 없이 `docker compose pull minio && docker compose up -d --force-recreate minio`만 수행한다 (`db`가 자체 Dockerfile로 빌드하는 것과 다름).

## Docker 빌드 규칙 (중요)

각 앱의 Dockerfile은 **서로 다른 build context**를 사용한다. 잘못된 context로 빌드하면 소스가 누락된다.

| App | Build Context | 빌드 명령 |
|-----|---------------|----------|
| **firehub-api** | `apps/firehub-api/` (자체 디렉토리) | `docker build apps/firehub-api/` |
| **firehub-web** | `.` (프로젝트 루트) | `docker build -f apps/firehub-web/Dockerfile .` |
| **firehub-ai-agent** | `.` (프로젝트 루트) | `docker build -f apps/firehub-ai-agent/Dockerfile .` |
| **db (postgres)** | `.` (프로젝트 루트) | `docker build -f docker/postgres/Dockerfile .` (이미지 태그는 `postgres`, 빌드 키는 `db`) |

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
./scripts/deploy.sh minio      # minio만 (public 이미지 pull + 재기동, 빌드 없음)
./scripts/deploy.sh all        # 전체 (api 포함, db/minio 제외)
```

> deploy.sh 는 buildx 캐시를 사용하므로 두 번째 빌드부터 단축된다.
> 이미 이미지를 push 한 경우 위의 부분 배포 방식이 더 빠르다.

## 사이트별 브랜딩 (화이트라벨) — 재빌드 없이 로고·아이콘 교체

web 이미지는 **단일 이미지**를 유지하고, 브랜딩(브랜드명·로고·파비콘)은 런타임에 주입한다.
프론트가 `<head>`에서 `/config.js`를 먼저 읽어 `window.__APP_CONFIG__`로 확정하므로 벤더 브랜드 깜빡임이 없다.

- **기본값**: 이미지에 `dist/config.js`(= `apps/firehub-web/public/config.js`)가 포함되어 있고, 미교체 시 기존 "Smart Fire Hub" 브랜드가 유지된다.
- **사이트별 override**: 그 파일만 사이트별 파일로 마운트하면 된다(재빌드 불필요). 볼륨을 **처음 추가**할 때만 `docker compose up -d --force-recreate web`가 필요하고, 이후 마운트된 `config.js` 내용 수정은 `no-store` 캐시라 새로고침으로 즉시 반영된다.

```yaml
# ~/prod/<site>/docker-compose.yml — web 서비스에 config.js 마운트
services:
  web:
    volumes:
      - ./branding/config.js:/usr/share/nginx/html/config.js:ro
```

```js
// ./branding/config.js — 사이트별 브랜딩
(function () {
  var config = {
    brandName: 'Acme Data',
    logoUrl: '/firehub-files/branding/acme-logo.svg', // null이면 기본 Flame 아이콘
    faviconUrl: '/firehub-files/branding/acme-fav.svg',
  };
  window.__APP_CONFIG__ = config;
  document.title = config.brandName;
  var l = document.querySelector("link[rel='icon']");
  if (!l) { l = document.createElement('link'); l.rel = 'icon'; document.head.appendChild(l); }
  l.href = config.faviconUrl;
})();
```

- **로고/파비콘 에셋**: 이미 동일 오리진(8888)으로 서빙되는 **MinIO 경로**(`/firehub-files/...`)에 업로드해 URL로 지정하는 방식을 권장한다(별도 마운트 불필요). 또는 nginx web root(`/usr/share/nginx/html/branding/`)에 파일을 마운트하고 `/branding/...` 경로로 참조해도 된다.
- `nginx.conf`는 `/config.js`에 `Cache-Control: no-store`를 설정해 교체가 즉시 반영된다.
- **파비콘 포맷**: `index.html`의 정적 `<link rel="icon" type="image/svg+xml">`가 남아 있어 SVG 파비콘을 권장한다. `.png`/`.ico`를 쓰려면 config.js에서 `link.type`도 함께 조정한다(대부분 브라우저는 무시하지만).
### 백엔드 브랜딩 (firehub-api / ai-agent)

웹 UI 외 **백엔드 생성 콘텐츠**의 브랜드명도 배포별 env로 주입한다(기본값 "Smart Fire Hub").

- **firehub-api**: Spring 프로퍼티 `app.branding.name` (env `APP_BRANDING_NAME`, 기본 `Smart Fire Hub`). 적용 대상:
  - 프로액티브 리포트 템플릿(`proactive-report.html`, `proactive-report-pdf.html`) — 브랜드 표기·푸터
  - 알림 채널 — 이메일 제목, Slack/Kakao 문구, 채널 연동/테스트 알림 메시지
- **firehub-ai-agent**: env `BRAND_NAME` (기본 `Smart Fire Hub`) — AI 어시스턴트 자기소개(`SYSTEM_PROMPT`/`OPENCODE_SYSTEM_PROMPT`).

docker-compose `environment:`(또는 `.env`)에 두 값을 사이트 브랜드로 지정하면 된다:

```yaml
services:
  api:
    environment:
      APP_BRANDING_NAME: "Acme Data"
  ai-agent:
    environment:
      BRAND_NAME: "Acme Data"
```

> **AI 페르소나 DB 시드(관리자 편집 영역)**: 일반 채팅 시스템 프롬프트는 `[ai-agent const(BRAND_NAME 반영)]` **뒤에** DB 설정값 `ai.system_prompt`(V69 시드)가 `[사용자 지시사항]`으로 append된다. 이 시드는 "당신은 Smart Fire Hub의 AI 어시스턴트입니다."를 담고 있으므로, `BRAND_NAME`만 바꾸면 이 문구는 기본값 그대로 남는다. 이 값은 **관리자가 설정 화면에서 직접 편집하는 DB 콘텐츠**로 설계상 env 자동 주입 대상이 아니다. 화이트라벨 시 관리자가 설정 화면에서 `ai.system_prompt`의 페르소나 문구를 사이트 브랜드로 수정한다.
- **다음 단계(멀티테넌트)**: 정적 `/config.js` 대신 서버가 요청 `Host`별로 `/config.js`를 생성하면 React 코드 변경 없이 한 배포가 여러 사이트 브랜딩을 서빙할 수 있다(소비 인터페이스 `window.__APP_CONFIG__` 동일). apple-touch-icon·theme-color·PWA manifest는 이때 함께 추가한다(현재 범위 밖).

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
5. **위임 차단 + 단일 에이전트 직접처리 (2026-06-24)**: 요청별 `opencode.json` 의 `agent` 블록이 메인(`build`)에서 `task` 위임을 전면 deny 하고 빌트인 `general` 서브에이전트를 disable 한다. 약한 모델(gemma)이 firehub 전용 subagent 대신 비격리 `general` 로 위임해 소스를 훑으며 멈추고(응답 지연) 내부 소스를 노출하던 문제(#0 보안)를 차단한다. opencode 경로는 위임 없이 `OPENCODE_SYSTEM_PROMPT` 로 firehub 도구를 직접 호출·요약한다(Claude SDK 경로의 위임 구조와 분리).

> ⚠ **알려진 한계 — PII 마스킹(opencode 경로)**: PII 마스킹은 프롬프트 지시에만 의존하며 코드 레벨 강제 계층이 없다. 약한 모델(gemma)은 마스킹 규칙을 따르지 않아 조회/분석 결과에 **실명·이메일 등 원본 PII 가 노출될 수 있다**(2026-06-24 실측). 강한 모델(Claude SDK 경로)은 프롬프트를 준수하나 보장은 아니다. 운영 결정으로 위험을 감수하고 배포함 — PII 민감 데이터에 opencode 옵션 사용 시 유의. 근본 해소는 MCP 도구 출력의 코드 레벨 컬럼 마스킹(후속 과제).

### 게이트웨이 스키마 호환 (`propertyNames` 자동 제거)

일부 OpenAI-호환 게이트웨이(예: Bedrock OpenAI-호환 엔드포인트)는 JSON Schema 의 **`propertyNames`** 키를 거부해, 해당 키가 포함된 도구 정의가 실린 요청을 `400 Generation failed` 로 반려한다(2026-06-24 실측: firehub 의 `z.record(z.string(), …)` 파라미터가 `propertyNames` 를 내보냄 — `add_row` 등). Anthropic API 직결(`sdk`/`cli`/`cli-api`)은 영향 없음.

→ **자동 처리됨**: OpenCode 경로는 stdio MCP 서버에 `OPENCODE_SCHEMA_COMPAT=1` 을 주입해 tools/list 응답 스키마에서 `propertyNames` 를 재귀 제거한다(`src/mcp/schema-compat.ts`). `propertyNames`(키는 문자열)는 JSON 키가 항상 문자열이라 의미상 잉여이므로 제거해도 동작 손실이 없다. 실측상 이 정제 후 firehub 전체 도구셋(88개)이 게이트웨이를 통과한다.

> 다른 게이트웨이가 `propertyNames` 외 다른 스키마 키워드를 거부할 경우, 같은 `schema-compat.ts` 의 `stripPropertyNames` 패턴을 확장하면 된다.
