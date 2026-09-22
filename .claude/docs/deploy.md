# 배포 가이드

배포 스크립트: `./scripts/deploy.sh [api|executor|web|ai-agent|channel|admin|db|minio|all]`

> `all` = **api + executor + web + ai-agent + channel** (운영 5개 앱 전부, **db·minio·admin은 제외**).
> `all` 정의는 **3곳**에서 동기화 필요: `scripts/deploy.sh`, `scripts/update.sh`, 본 문서.
> 운영 docker-compose 서비스명이 빌드 키와 다른 경우(`channel` → `firehub-channel`)는 두 스크립트의 `prod_service_name()` 헬퍼가 흡수한다.
> `db`, `minio`는 stateful/변경이 드문 서비스로 재기동 시 데이터·서비스 전체에 영향을 주므로 `all`에서 제외 — 각각 `./scripts/deploy.sh db`, `./scripts/deploy.sh minio`로 개별 배포만 가능.
> `minio`는 **public 이미지**(`minio/minio`)라 빌드/push 없이 `docker compose pull minio && docker compose up -d --force-recreate minio`만 수행한다 (`db`가 자체 Dockerfile로 빌드하는 것과 다름).
> `admin`(firehub-admin, 플랫폼 슈퍼관리자 콘솔)은 stateful은 아니지만 **권한상승 경로를 배포 수준에서도 분리**하는 설계 의도(참조: 멀티 테넌시 P7-c) 때문에 의도적으로 `all`에서 제외했다 — 항상 `./scripts/deploy.sh admin`으로 명시적으로만 배포한다.

## firehub-admin (플랫폼 슈퍼관리자 콘솔)

- 빌드 컨텍스트는 web/ai-agent와 동일하게 **프로젝트 루트**(`docker build -f apps/firehub-admin/Dockerfile .`) — 이미지 태그는 `admin`.
- 백엔드 API(`/api/platform/**`)는 firehub-api가 이미 제공하며 별도 서비스가 아니다. firehub-admin 이미지 자체 nginx.conf가 `/api/` 를 컨테이너 네트워크의 `api:8080`으로 프록시하므로, 운영 compose에는 볼륨 마운트나 별도 env 없이 이미지 그대로 등록하면 된다(web처럼 게이트웨이용 `./nginx.conf`를 덮어쓸 필요 없음).
- 운영 포트: `ADMIN_PORT`(기본 `8889`, `.env`) → 컨테이너 80. web과 동일하게 전체 공개 바인딩.
- 첫 배포 전 확인: `docker compose up -d admin` 실행 후 `http://<host>:${ADMIN_PORT:-8889}` 접속, 운영자 로그인 화면이 뜨는지 확인.

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

### DB 마이그레이션 배포 전 스냅샷 (필수 — V122 이상)

Flyway 는 community edition 이라 **undo 가 없다** — 한번 적용된 마이그레이션은 forward-only 다.
`V122__ai_credential.sql`(2026-09-19 이슈 #693, 테넌트별 AI 설정 2단계)부터 이 성질이 실제로
되돌릴 수 없는 데이터를 만든다 — opencode 자격증명(`providerId`/`baseURL`/`reasoningEffort`)은
옛 3키(`ai.api_key`/`ai.cli_oauth_token`/`ai.agent_type`) 어디에도 없던 값이라, 마이그레이션
이후 화면에서 테넌트가 저장하면 그 값을 복원할 방법이 **스냅샷 말고는 없다.**

**`api` 배포(`./scripts/deploy.sh api` 또는 `all`) 전에 매번**:

1. 운영 DB 에서 먼저 확인한다(로컬 `tenant_settings` 는 0행이라 규모를 알 수 없다):
   ```sql
   SELECT tenant_id, array_agg(key ORDER BY key) FROM tenant_settings WHERE key LIKE 'ai.%' GROUP BY tenant_id;
   SELECT count(*) FROM tenant_settings WHERE key='ai.agent_type' AND value='opencode';
   -- 플랫폼 평면(system_settings)도 반드시 같이 본다 — V122 가드는 이제 이 값도 읽는다
   -- (전체 브랜치 리뷰 C1). 배포측 PVC(opencode.jsonc)가 플랫폼 기본값을 opencode 로 강하게
   -- 시사하므로, 여기를 빼먹으면 진짜로 막아야 할 상태를 놓친다.
   SELECT value FROM system_settings WHERE key='ai.agent_type';
   ```
   두 번째 질의가 0 이 아니거나, 세 번째 질의가 `opencode` 이거나 `sdk`/`cli`/`cli-api` 중
   어느 것도 아니면(빈 문자열·오타 포함) **배포하지 않는다** — V122 는 두 평면 모두에서 이런
   값을 만나면 `RAISE EXCEPTION` 으로 자멸하도록 설계돼 있고(마이그레이션 파일 헤더 참고), 그
   상태로 배포를 강행하면 `api` 컨테이너가 부팅 시 Flyway 단계에서 그대로 실패한다 — 이것은
   배포를 막아야 할 신호이지, 넘어가서 될 경고가 아니다. 특히 플랫폼 값이 이 조건에 걸리면
   그 값을 상속하는 **모든** 테넌트가 영향을 받는다 — 테넌트 하나가 아니라 전체가 막힌다.
2. 배포 직전 **`tenant_settings` 와 `system_settings` 두 테이블 전체를 스냅샷**한다:
   ```bash
   pg_dump -h <host> -U <user> -d smartfirehub \
     -t tenant_settings -t system_settings \
     -f "snapshot-pre-v122-$(date +%Y%m%d%H%M%S).sql"
   ```
3. 무엇이 복구되고 무엇이 안 되는지: `sdk`/`cli`/`cli-api` 자격증명(암호문 그대로)은 새 `ai.credential`
   문서 형태로 옮겨진 뒤에도 스냅샷 없이 옛 3키에서 재구성 가능하다. **`opencode` 의 payload
   (providerId/baseURL/reasoningEffort)는 스냅샷 없이는 영영 복구 불가**하다 — 자세한 이유는
   `apps/firehub-api/src/main/resources/db/migration/V122__ai_credential.sql` 헤더 주석 참고.

### opencode baseURL 사설망 점검 (이슈 #698)

#693 의 SSRF 가드는 **저장 시점**에만 baseURL 을 검사한다. 그 가드가 생기기 전에 저장된 행에는
사설망 주소가 그대로 남아 있을 수 있다.

**코드 쪽은 닫혀 있다** — ai-agent 가 opencode CLI 를 띄우기 직전에 `assertSafeCompletionTarget`
으로 baseURL 을 다시 검사한다(`agent-opencode.ts`). 저장 시점과 무관하게 모든 행이 사용 시점에
검사되므로, 옛 행이 남아 있어도 실제로 사설망에 나가지는 않는다. 분류 경로는 그 전부터 같은
가드를 쓰고 있었다.

**그래도 어떤 행이 그런 상태인지는 알아야 한다** — 가드에 걸리는 테넌트는 채팅이 오류로
끝나므로, 배포 후 다음 질의로 대상을 찾아 관리자에게 정정 안내한다.

```sql
-- 테넌트 평면
SELECT tenant_id,
       value::jsonb -> 'payload' ->> 'providerId' AS provider_id,
       value::jsonb -> 'payload' ->> 'baseUrl'    AS base_url
  FROM tenant_settings
 WHERE key = 'ai.credential'
   AND value::jsonb ->> 'agentType' = 'opencode'
 ORDER BY tenant_id;

-- 플랫폼 평면
SELECT value::jsonb -> 'payload' ->> 'baseUrl' AS base_url
  FROM system_settings
 WHERE key = 'ai.credential'
   AND value::jsonb ->> 'agentType' = 'opencode';
```

`https` 가 아니거나, 포트가 443/8443 이 아니거나, 호스트가 사설 대역(루프백, 10/172.16/192.168,
169.254, 100.64/10, fc00::/7, 0.0.0.0/8)으로 해석되면 그 행은 이제 **차단된다**. 해당 테넌트에
정상 URL 로 다시 저장하도록 안내한다.

> 기동 시 자동 스캔은 **만들지 않았다**(판단 근거). `tenant_settings` 는 RLS 로 테넌트별로
> 격리돼 있어 전 테넌트를 훑으려면 RLS 를 우회하는 데이터소스를 애플리케이션 코드에 새로
> 노출해야 한다 — 경고 로그 하나를 얻자고 열기에는 그 문이 너무 크다. 사용 시점 가드가 실제
> 차단을 이미 담당하므로, 남은 것은 위 질의로 충분한 **가시성** 문제다.

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
2. **모델 인증 (테넌트별 provider, 2026-09-19 이슈 #693 — "옵션 3: 배포 측 전역 설정 상속" 폐기)**: OpenCode → 모델 provider 인증은 이제 **테넌트가 관리자 설정 화면에서 저장한 opencode 자격증명**(공급자 ID·기본 URL·API 키·추론 강도)에서 온다. firehub-api 가 저장된 자격증명을 요청 바디로 흘려보내고, ai-agent 의 `buildOpenCodeConfig`(`agent-opencode.ts`)가 요청마다 `provider` 블록을 조립해 `OPENCODE_CONFIG_CONTENT` 환경변수(자식 프로세스 전용, 디스크에 쓰지 않음)로 opencode CLI 자식에 주입한다. **배포 측 전역 opencode 설정 파일(`~/.config/opencode/opencode.json`, `OPENCODE_CONFIG`)이나 컨테이너 env(`ANTHROPIC_API_KEY` 등)는 더 이상 opencode 모델 인증의 출처가 아니다.**
   - **env 경로 (이슈 #696 — denylist → allowlist 전환됨)**: `agent-opencode.ts` 가 자식 env 를 **빈 객체에서 조립**한다(`opencode-child-env.ts`). 예전처럼 "아는 이름을 지우는" 방식이 아니라 **허용한 이름만 통과**하므로, 컨테이너 env 에 무엇이 있든 목록에 없으면 자식에 도달하지 않는다 — 새 공급자 자격증명 관례가 생겨도 자동으로 차단된다.
     - 허용되는 것: `PATH`(실측상 유일한 필수 키), `HOME`(`--session` 재개의 기준점), `USER`/`LOGNAME`/`SHELL`, `TMPDIR`/`TMP`/`TEMP`, `LANG`/`LANGUAGE`/`LC_*`/`TZ`/`TERM`, 프록시(`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`/`ALL_PROXY` 대소문자 양쪽), 사내 CA(`NODE_EXTRA_CA_CERTS`/`SSL_CERT_FILE`/`SSL_CERT_DIR`/`CURL_CA_BUNDLE`), `npm_config_*`, `XDG_DATA_HOME`/`XDG_CACHE_HOME`/`XDG_STATE_HOME`.
     - `NODE_OPTIONS` 는 **일부러 뺐다** — `--require` 로 자식에 임의 코드를 주입할 수 있다.
     - ⚠ **운영 전용 변수가 끊겨 opencode 가 깨지면** 재배포 없이 `OPENCODE_CHILD_ENV_EXTRA` 에 쉼표로 변수 **이름**을 적어 열 수 있다(값이 아니라 이름이다). 단 위 차단 목록(`ANTHROPIC_API_KEY` 등 20개, `opencode-child-env.ts` 의 `HARD_DENIED`)은 이 탈출구로도 통과하지 못하고, 시도하면 WARN 로그가 남는다. 이 탈출구를 쓰게 됐다면 그 변수를 허용 목록에 정식으로 추가하는 후속 작업이 필요하다는 신호다.
   - **파일 경로**: `OPENCODE_CONFIG` env 를 지우는 것만으로는 **부족하다**. opencode CLI 는 그 env 가 없으면 기본 경로 `$XDG_CONFIG_HOME/opencode/opencode.json`(XDG 미설정이면 `$HOME/.config/opencode/opencode.json`)을 읽는다. 그래서 `agent-opencode.ts` 가 자식에게 `XDG_CONFIG_HOME` 을 **ai-agent 프로세스당 하나인 임시 디렉터리**(OS 임시 디렉터리 아래, 첫 opencode 요청에 지연 생성 후 프로세스 수명 동안 재사용)로 준다. HOME 은 바꾸지 않는다 — opencode 세션 상태가 `~/.local/share/opencode` 에 있어 `--session` 재개가 깨진다. 실제 opencode 바이너리(v1.18.31)로 확인한 동작이다.
     - ⚠ **npm 레지스트리 egress 필요 (재검토 D1)**: opencode 는 설정을 로드할 때 그 디렉터리 아래에 플러그인 npm 트리(`@opencode-ai/plugin`, 실측 약 61MB/3,648 파일)를 **스스로 설치**한다. 따라서 **파드 수명당 첫 opencode 요청 1회**는 npm 레지스트리로 나가는 egress 가 필요하다(실측 약 +4s). 막혀 있으면 그 첫 요청이 **약 70초 지연**된 뒤 진행되고, 이후 요청은 캐시를 재사용해 warm(약 0.6s)이다. 프록시/사설 레지스트리 환경이면 컨테이너 env 로 `npm_config_registry` 를 지정하는 것을 검토한다(설치기가 이 값을 읽는 것은 확인됨). 디렉터리 경로는 `mkdtemp` 의 **무작위 이름**이라 이미지에 미리 심어 둘 자리가 없다 — 고정 경로로 바꾸면 공유 `/tmp` 에 제3자가 `opencode.json` 을 선점할 수 있어 가드가 무너진다. 콜드 부트스트랩을 더 줄이려면 프로세스 기동 시 1회 pre-warm 이 남은 선택지다(현재 범위 밖, 후속). (요청별 디렉터리였다면 이 비용이 **매 채팅**에 붙었다 — 그래서 프로세스당 1개다.)
     - **데이터 디렉터리 (재검토 D3 → 이슈 #697 에서 닫음)**: 이 XDG 가드는 **설정** 디렉터리만 끊는다. opencode 의 ambient provider 인증은 `$XDG_DATA_HOME/opencode/auth.json`(기본 `~/.local/share/opencode`)에도 있을 수 있고, 그 경로는 `--session` 재개 때문에 일부러 살려 둔다.
       - **실측(2026-09-21, v1.18.31)**: `OPENCODE_CONFIG_CONTENT` 로만 자격증명을 주는 정상 실행은 `auth.json` 을 **만들지 않는다**(생기는 것은 세션 DB·로그뿐). 테넌트 apiKey 가 데이터 디렉터리에 남지도 않는다(전체 재귀 grep 0건). 그 파일은 `opencode auth login` 의 산물이다.
       - 따라서 위험은 **미리 심어진 파일**뿐이고, 이제 ai-agent 가 opencode spawn 직전에 그 파일의 존재를 확인해 **그 요청을 실패시킨다**(`opencode-ambient-auth-guard.ts`). 기동 실패가 아니라 요청 단위인 이유는 sdk/cli 테넌트까지 멈추지 않기 위해서다.
       - ⚠ **`.local/share/opencode` 를 마운트하지 마라**. 마운트하면 위 가드에 걸려 opencode 채팅이 전부 실패한다(조용히 잘못 과금되는 것보다 낫다는 판단). 차트에도 같은 경고를 주석으로 박아 뒀다.
       - 로컬 개발 머신은 보통 개발자가 `opencode auth login` 을 한 적이 있어 이 파일이 이미 있다 — 그때만 `OPENCODE_ALLOW_AMBIENT_AUTH=1` 로 끌 수 있다(끄면 매 요청 WARN). **운영에는 절대 설정하지 않는다.**
   - ⚠ **배포 조치(필수)**: aiagent Deployment 의 `.config/opencode` subPath 마운트는 **차트에서 이미 제거했다**(`~/k8s/smart-fire-hub/templates/aiagent-deployment.yaml`, 2026-09-21, 이슈 #697). 그 마운트는 이 단계가 폐기한 "배포 측 전역 설정 상속"을 위해 존재하던 것이라 이제 쓰임이 없고, 남겨 두면 코드 가드가 (리팩터링·버전업 등으로) 무효화되는 순간 전역 provider 가 테넌트 설정을 조용히 이기는 상태로 되돌아간다.
     - **차트 파일을 고친 것과 클러스터에 적용된 것은 다르다** — `helm upgrade` 후 `kubectl get deploy aiagent -o yaml` 로 그 마운트가 실제로 사라졌는지 확인할 것.
     - **대가(재검토 D1)**: 그 PVC 가 opencode 의 플러그인 npm 캐시 자리도 겸하고 있었으므로, 제거하면 그 캐시는 PVC 수명이 아니라 **파드 수명**을 따른다 — 즉 파드 재시작마다 위 "첫 요청 1회 콜드 부트스트랩"이 다시 일어난다. 요청마다가 아니므로 수용 가능한 비용으로 판단했다.
   - **컨테이너의 `ANTHROPIC_API_KEY`/`CLAUDE_CODE_OAUTH_TOKEN` 은 두지 않는다 (#706, V126 이후).** AI 자격증명은 이제 **테넌트 전용**이다 — 플랫폼 평면(`system_settings` 의 `ai.credential`, `/api/platform/settings/ai-credential`)은 삭제됐고 V126 이 그 행과 옛 3키를 지운다(복사 마이그레이션 없음). 자기 자격증명이 없는 테넌트는 채팅·프로액티브·AI_CLASSIFY 가 API 단에서 명확한 오류로 멈추고, 테넌트 관리자가 설정 › AI 에이전트에서 저장하면 동작한다.
     - ai-agent 에는 여전히 ambient 폴백(`proactive.ts` 의 `ANTHROPIC_API_KEY`, 분류 경로 `ClaudeSdkCompletionProvider`, cli 의 keychain)이 남아 있다(로컬 dev 챗이 keychain 폴백에 의존해 범위 밖, #706 잔여). API 가 불완전 자격증명을 먼저 막으므로 지금은 도달하지 않지만, **컨테이너에 키를 넣으면 그 폴백이 살아나는 순간 다른 계정으로 조용히 과금된다** — 그래서 두지 않는다.
     - **V126 배포 직후**: 자기 행이 없는 테넌트는 AI 가 즉시 멈춘다(의도). api + web + admin 은 반드시 함께 배포한다(admin 이 삭제된 플랫폼 AI 엔드포인트를 부르면 404).
     - **V127 (AI 동작 설정도 테넌트 전용)**: `system_settings` 의 `ai.%` 행을 전부 지운다. `ai.model`/`ai.max_turns`/`ai.system_prompt`/`ai.temperature`/`ai.max_tokens`/`ai.session_max_tokens` 는 "테넌트 값 → 코드 기본값(`AiBehaviorDefaults`: claude-sonnet-5 / 10 / 슬림 프롬프트 / 1.0 / 16384 / 50000)"으로 해석되고 플랫폼 설정 화면·API 에서 사라졌다. **배포 전** prod `system_settings` 의 `ai.%` 값이 시드와 다른지 확인할 것 — 운영자가 바꿔 둔 값은 V127 이후 코드 기본값으로 대체된다(필요한 워크스페이스는 자기 설정에서 저장).
   - 테넌트가 opencode 자격증명을 저장하지 않았거나(providerId/baseUrl 미설정) 불완전하면 채팅은 명확한 `error` SSE(chat) 또는 400(proactive, missingCredential 가드)로 종료된다 — 배포 측 전역 설정으로 조용히 폴백하지 않는다(fail-closed).
   - **배포 후 필수 수동 검증 (Ruling #34)** — `OPENCODE_CONFIG_CONTENT`(요청별, 테넌트 provider)가
     PVC 전역 `opencode.json`/`OPENCODE_CONFIG` 를 실제로 **이긴다**는 것은 자동화 테스트로
     확인할 수 없다(진짜 `opencode` 바이너리가 있어야 한다 — 테스트는 그 바이너리를 띄우지
     않는다). 디스크에 있던 옛 전역 설정 경로는 이미 지워졌으므로, 병합 순서가 기대와 다르면
     **조용히 예전 방식(배포 전체가 같은 사내 계정 하나로 과금)으로 되돌아간다** — 이 단계
     자체가 없애려던 그 상태다. 첫 배포 직후, 그리고 ai-agent 이미지를 다시 빌드할 때마다
     실제 배포 환경에서 양방향으로 확인한다:
     1. PVC 전역 opencode 설정이 존재하거나(레거시 잔재) 도달 불가능한 provider 를 가리키는
        상태에서, 테넌트 자격증명을 저장한 테넌트의 채팅이 **정상 응답한다** — 전역 설정이
        끼어들지 않는다는 뜻이다.
     2. 반대로 그 테넌트의 저장된 `baseURL` 을 일부러 도달 불가능한 주소로 바꾸면, 채팅이
        `error` SSE 로 **명확히 실패한다** — 전역 설정으로 조용히 폴백해 성공한 것처럼 보이면
        병합 순서가 틀렸다는 신호다.
     3. 같은 테넌트에서 파이프라인의 `AI_CLASSIFY` 스텝을 한 번 돌려, 분류도 같은 테넌트
        provider 로 나가는지 확인한다(분류·GraphRAG 추출 경로는 채팅과 별도 코드 경로라 채팅
        확인만으로는 보증되지 않는다).
     - `opencode-ai` 패키지는 Dockerfile 에서 **버전 핀 없이** `npm install -g` 로 설치된다 —
       위 확인은 동작을 한 번 보증할 뿐 버전을 보증하지 않으므로, ai-agent 이미지를 재빌드할
       때마다(즉 `opencode-ai` 가 새 버전으로 바뀔 수 있을 때마다) 다시 확인한다.
3. **firehub 도구 인증**: 별도 조치 불필요 — ai-agent 가 요청별 config(`OPENCODE_CONFIG_CONTENT`)의 `mcp.firehub.environment` 로 `INTERNAL_SERVICE_TOKEN`/`USER_ID`/`TENANT_ID` 및 GraphRAG completion 용 `AI_CREDENTIAL_*`(테넌트의 opencode provider 자격증명, Ruling #30) 을 주입한다(사용자별 격리). opencode 본체 env 에서는 내부 토큰과 ambient Anthropic 자격증명이 모두 제거된다.
4. **도구 권한**: 요청별 config 가 빌트인 도구를 비활성(`tools`)하고 `permission` 으로 `firehub_*` 만 허용한다(채팅에서 bash/파일/네트워크 접근 차단).
5. **위임 차단 + 단일 에이전트 직접처리 (2026-06-24)**: 요청별 config 의 `agent` 블록이 메인(`build`)에서 `task` 위임을 전면 deny 하고 빌트인 `general` 서브에이전트를 disable 한다. 약한 모델(gemma)이 firehub 전용 subagent 대신 비격리 `general` 로 위임해 소스를 훑으며 멈추고(응답 지연) 내부 소스를 노출하던 문제(#0 보안)를 차단한다. opencode 경로는 위임 없이 `OPENCODE_SYSTEM_PROMPT` 로 firehub 도구를 직접 호출·요약한다(Claude SDK 경로의 위임 구조와 분리).

> ⚠ **알려진 한계 — PII 마스킹(opencode 경로)**: PII 마스킹은 프롬프트 지시에만 의존하며 코드 레벨 강제 계층이 없다. 약한 모델(gemma)은 마스킹 규칙을 따르지 않아 조회/분석 결과에 **실명·이메일 등 원본 PII 가 노출될 수 있다**(2026-06-24 실측). 강한 모델(Claude SDK 경로)은 프롬프트를 준수하나 보장은 아니다. 운영 결정으로 위험을 감수하고 배포함 — PII 민감 데이터에 opencode 옵션 사용 시 유의. 근본 해소는 MCP 도구 출력의 코드 레벨 컬럼 마스킹(후속 과제).

### 게이트웨이 스키마 호환 (`propertyNames` 자동 제거)

일부 OpenAI-호환 게이트웨이(예: Bedrock OpenAI-호환 엔드포인트)는 JSON Schema 의 **`propertyNames`** 키를 거부해, 해당 키가 포함된 도구 정의가 실린 요청을 `400 Generation failed` 로 반려한다(2026-06-24 실측: firehub 의 `z.record(z.string(), …)` 파라미터가 `propertyNames` 를 내보냄 — `add_row` 등). Anthropic API 직결(`sdk`/`cli`/`cli-api`)은 영향 없음.

→ **자동 처리됨**: OpenCode 경로는 stdio MCP 서버에 `OPENCODE_SCHEMA_COMPAT=1` 을 주입해 tools/list 응답 스키마에서 `propertyNames` 를 재귀 제거한다(`src/mcp/schema-compat.ts`). `propertyNames`(키는 문자열)는 JSON 키가 항상 문자열이라 의미상 잉여이므로 제거해도 동작 손실이 없다. 실측상 이 정제 후 firehub 전체 도구셋(88개)이 게이트웨이를 통과한다.

> 다른 게이트웨이가 `propertyNames` 외 다른 스키마 키워드를 거부할 경우, 같은 `schema-compat.ts` 의 `stripPropertyNames` 패턴을 확장하면 된다.
