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

### SMTP 테넌트 전용 전환 (V128, 이슈 #712)

`V128__drop_platform_smtp_settings.sql` 은 `system_settings` 의 `smtp.%` 행(플랫폼 SMTP 6키)을
**복사 없이 삭제**한다. 이후 메일 발송은 각 워크스페이스가 저장한 SMTP(`tenant_settings`)만 쓰고,
플랫폼 값으로 폴백하지 않는다(테넌트 컨텍스트 없는 배경 발송 포함).

- **api + web + admin 을 반드시 함께 배포한다.** 옛 web 은 삭제된 `DELETE /api/v1/settings/overrides/{key}`
  를 부르고(404), 옛 admin 은 `PUT /api/platform/settings` 에 `smtp.*` 를 실어 보내 400 을 받는다.
- **영향**: 자기 SMTP 를 등록하지 않은 워크스페이스는 배포 직후부터 알림 메일·프로액티브 리포트 메일이
  실패한다(의도 — 오류 문구가 "워크스페이스 설정 › 이메일에서 등록"을 안내한다).
- **배포 전 확인(운영 DB)**:
  ```sql
  -- 1) 지워질 플랫폼 값 — 필요한 워크스페이스가 다시 입력할 수 있게 기록해 둔다(비밀번호는 암호문).
  SELECT key, value FROM system_settings WHERE key LIKE 'smtp.%' ORDER BY key;
  -- 2) 이미 자기 SMTP 를 가진 워크스페이스(이들은 영향 없음)
  SELECT tenant_id, array_agg(key ORDER BY key) FROM tenant_settings WHERE key LIKE 'smtp.%' GROUP BY tenant_id;
  -- 3) 메일 채널을 쓰는 워크스페이스 중 2) 에 없는 곳 = 배포 직후 메일이 멈추는 곳
  ```
  1) 에 실제 호스트가 있고 3) 이 비어 있지 않으면, 배포 전에 해당 워크스페이스 관리자에게 알리거나
  배포 직후 그 워크스페이스 설정 › 이메일에서 SMTP 를 등록한다. V128 은 되돌릴 수 없으므로 위
  "배포 전 스냅샷"(`system_settings` 포함)을 반드시 먼저 뜬다.
- 로컬 main 기준 운영 DB 는 V122 에 머물러 있어(2026-09-21 실측) V123~V127 과 V128 이 한 번에
  적용된다 — #706 의 V126/V127 노트(아래 OpenCode 절)와 함께 확인한다.

### 옛 AI 평면 3키 삭제 (V129, 이슈 #699)

`V129__drop_legacy_tenant_ai_keys.sql` 은 `tenant_settings` 의 `ai.api_key` / `ai.cli_oauth_token` /
`ai.agent_type` 행을 지운다. V122 가 이 값을 `ai.credential`(JSON 문서)로 옮긴 뒤 아무도 읽지 않는
사본이라 동작 변화는 없다. V129 자체에는 배포 순서 제약이 없다(첫 운영 배포는 V128 규칙을 따른다).

- **V122 이전 코드로 되돌릴 여지가 사라진다.** 운영은 V122 가 적용된 채 `ai.credential` 로 돌고 있고, 옛 3키
  행도 아직 남아 있어 지금은 V122 이전 이미지로 돌아가도 그 행을 읽는다. V129 가 그 행을 지우므로, 이후 되돌리려면
  위 "배포 전 스냅샷"(`tenant_settings` 포함)으로 복원해야 한다.
- 배포 전 확인(운영 DB) — **RLS 때문에 런타임 계정(`app_tenant`)으로 조회하면 0행이 나온다.** 마이그레이션을
  돌리는 테이블 소유 계정(`POSTGRES_USER`, 기본 `app`)으로 실행한다:
  ```sql
  -- 옛 3키를 가진 워크스페이스 중 ai.credential 이 없는 곳 — 0행이어야 한다(V122 가 같은 단언으로 보장했다).
  SELECT DISTINCT tenant_id FROM tenant_settings
   WHERE key IN ('ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type')
     AND tenant_id NOT IN (SELECT tenant_id FROM tenant_settings WHERE key = 'ai.credential');
  ```

### 임베딩 차원별 테이블 + 테넌트 전용 임베딩 설정 (V131, 이슈 #713·#392)

`V131__embedding_dimension_tables.sql` 은 `document_chunk`/`dataset_embedding` 의 `embedding`·`embedding_model`
컬럼을 `document_chunk_vec_1024`/`document_chunk_vec_1536`/`dataset_embedding_vec_1024`/`dataset_embedding_vec_1536`
네 테이블로 옮긴 뒤 **옛 컬럼을 DROP 한다(비가역)**. `system_settings` 의 `embedding.*` 4행도 지운다
(테넌트로 복사하지 않는다 — 의도).

- **api + web + admin 을 함께 배포한다.** `./scripts/deploy.sh all` 은 `api + executor + web + ai-agent + channel`
  만 묶고 **admin 은 의도적으로 뺀다**(위 "운영 환경" 표 참고) — **`./scripts/deploy.sh admin` 을 따로** 실행해야
  한다. 빠뜨리면 옛 admin 이미지가 이미 삭제된 `/api/platform/settings` 를 계속 부른다(#706·#713 로 제거됨).
  ai-agent 는 이번 마이그레이션과 무관해 무변경(2026-09-28 `grep -rn "1024\|1536" apps/firehub-ai-agent/src`
  결과 벡터 차원 가정 없음 — `/admin/embedding/embed` 계약 `{model, dimension, embeddings}` 그대로).
- **배포 직후 모든 테넌트가 "임베딩 미설정"이다** → `임베딩이 설정되지 않았습니다 (설정 > 임베딩)` 문구가 뜨고
  문서 검색·데이터셋 의미 검색·데이터셋 행 검색의 HYBRID 의미 부분이 멈춘다(`degraded`). 테넌트마다 설정 › 임베딩
  화면에서 Ollama · bge-m3 · 기존 Base URL 을 저장해야 정상화된다. 현재 모델 벡터는 이미 `_vec_1024` 에 있으므로
  저장 후 재임베딩은 모델이 NULL(`<unknown>`, 옛 스키마에 `embedding_model` 이 없던 시절 적재분)이던 벡터만 돈다.
- **Ollama 주소 허용 목록은 이미 배선돼 있다 — prod `.env` 변경 불필요.** `app.embedding.ollama-allowed-base-urls`
  (`apps/firehub-api/src/main/resources/application.yml:89`)는
  `${EMBEDDING_OLLAMA_ALLOWED_BASE_URLS:http://host.docker.internal:11434}` 로 기본값이 이미 운영이 쓰는 주소와
  같다(`EmbeddingTargetGuard` 가 이 값과 **정확히 일치**하는 사설망 주소만 통과시킨다 — SSRF 가드). 아래 사전
  점검 1) 의 `embedding.base_url` 이 이 기본값과 다를 때만 운영 compose 의 api `environment:` 에
  `EMBEDDING_OLLAMA_ALLOWED_BASE_URLS` 를 추가한다(`.env` 만으로는 컨테이너에 주입되지 않는다, 2026-08-31 전례).
- **JobRunr 에 옛 `DocumentChunkReembedService.reembedDataset` 잡이 남아 있으면 배포 후 실패한다.** 이 클래스는
  이번 브랜치에서 삭제됐다(`com.smartfirehub.document.service.DocumentChunkReembedService`, main 에는 존재).
  `reembedAll()` 이 `jobScheduler.enqueue(...)` 로만 넣었을 뿐 recurring 등록은 없으므로 `jobrunr_jobs`
  큐(옛 클래스 참조)만 보면 된다 — `jobrunr_recurring_jobs` 테이블에는 애초에 `jobsignature` 컬럼이 없다
  (`V85__jobrunr_tables.sql`: `id, version, jobasjson, createdat` 뿐이라 조회할 필요도 없다).
  상태별 조치: **ENQUEUED/PROCESSING** 은 배포 전 옛 api 이미지가 클래스를 가진 채로 마저 처리되길
  기다린다(급하면 배포를 잠깐 미룬다). **SCHEDULED**(재시도 대기)는 배포 전에 `DELETE` 한다 — 배포 후엔
  옛 클래스를 못 찾아 영구 실패한다. 이미 **FAILED**(재시도 소진)로 끝난 것은 무해하니 그대로 둬도 된다.
  대시보드는 꺼져 있으므로(`org.jobrunr.dashboard.enabled: false`) SQL 로만 확인·정리한다.
- **배포 전 확인(운영 DB, 소유자 롤 `app` 으로 — `app_tenant` 는 RLS 때문에 0행)**:
  ```sql
  -- 0) 최신 마이그레이션이 V130 인가
  SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
  -- 0-1) 옛 재임베딩 잡 — ENQUEUED/PROCESSING 은 옛 api 가 마저 처리하게 두고, SCHEDULED 는 DELETE,
  --     FAILED(재시도 소진)는 무해하니 무시해도 된다
  SELECT id, state, scheduledat FROM jobrunr_jobs
   WHERE jobasjson LIKE '%DocumentChunkReembedService%'
     AND state NOT IN ('SUCCEEDED', 'DELETED');
  -- 1) 지워질 플랫폼 값 — 테넌트가 다시 입력할 값이다(baseUrl 이 기본값과 다르면 위 허용목록에 추가)
  SELECT key, value FROM system_settings WHERE key LIKE 'embedding.%' ORDER BY key;
  -- 2) 옮겨질 벡터 규모와 차원(전부 1024 여야 한다 — 아니면 V131 이 RAISE 로 멈춘다)
  SELECT count(*), count(*) FILTER (WHERE vector_dims(embedding) <> 1024) FROM document_chunk WHERE embedding IS NOT NULL;
  SELECT count(*), count(*) FILTER (WHERE vector_dims(embedding) <> 1024) FROM dataset_embedding WHERE embedding IS NOT NULL;
  -- 3) 모델 NULL 벡터(설정 저장 뒤 재임베딩 대상)
  SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL AND embedding_model IS NULL;
  SELECT count(*) FROM dataset_embedding WHERE embedding IS NOT NULL AND embedding_model IS NULL;
  ```
- **배포 직전 DB 전체 덤프 필수**(컬럼 DROP 은 스냅샷 말고 복구 수단이 없다 — V122 절의 테이블 한정 덤프와
  달리 여기는 전체 덤프다):
  ```bash
  pg_dump -h <host> -U app -d smartfirehub -f "snapshot-pre-v131-$(date +%Y%m%d%H%M%S).sql"
  ```
- **배포 순서**: `./scripts/deploy.sh api` → `./scripts/deploy.sh web` → `./scripts/deploy.sh admin`
  (V131 적용 확인: `flyway_schema_history` 최신 = 131, success=t). V131 은 네 벡터 테이블을 만들고
  `INSERT INTO ... SELECT`(백필) 한 뒤 각 테이블에 HNSW 인덱스(`CREATE INDEX ... USING hnsw`)를 만들고
  나서야 옛 컬럼을 `DROP` 한다 — 벡터 건수가 크면 백필 + HNSW 빌드가 시간이 걸릴 수 있다. `api` 기동이
  느려도 곧바로 헬스체크 실패로 판단하지 말고 Flyway 로그를 먼저 본다.
- **라이브 검증**: 테넌트 설정 저장 → 재임베딩 카드 `bge-m3 · 1024차원`·진행률 100% → 문서 검색, 채팅의
  find_datasets, 데이터셋 행 검색(HYBRID 가 degraded 아님) 확인.
  **실데이터 규모에서 HNSW 인덱스가 실제로 쓰이는지 EXPLAIN 으로 확인한다** — 테스트는 소수 행이라
  `EmbeddingVectorTablesMigrationTest`/검색 테스트가 `enable_sort=off` 로 HNSW 를 강제했을 뿐, 운영 규모에서
  플래너가 실제로 HNSW 를 고르는지는 이번 계획에서 검증하지 못했다. `app_tenant` 롤로(RLS 를 실제로 태우기
  위해 `app` 으로 하지 않는다 — 전 테넌트 합계를 보는 사전 점검은 RLS 를 우회하는 `app`, 운영 검색과 같은
  RLS 조건이 붙은 계획을 봐야 하는 이 EXPLAIN 은 `app_tenant` 라 롤이 서로 반대다) 실 테넌트 컨텍스트에서, 실제 검색 SQL(`DocumentChunkRepository.semanticSql`)과
  같은 모양으로 확인한다 — 벡터 1024차원 리터럴은 손으로 못 치므로 psql `\gset` 로 실제 행에서 뽑아 쓴다:
  ```sql
  BEGIN;
  SET LOCAL app.tenant_id = '<실 테넌트 id>';
  SET LOCAL hnsw.iterative_scan = relaxed_order;
  SET LOCAL hnsw.ef_search = 200;
  SELECT embedding::text AS qv, embedding_model AS qm
    FROM document_chunk_vec_1024 WHERE tenant_id = '<실 테넌트 id>' LIMIT 1 \gset
  EXPLAIN ANALYZE
  SELECT c.id, 1 - (v.embedding <=> :'qv'::vector) AS score
    FROM document_chunk_vec_1024 v
    JOIN document_chunk c ON c.id = v.chunk_id
    JOIN document_file df ON df.id = c.document_file_id
   WHERE df.status = 'COMPLETED' AND v.embedding_model = :'qm'
   ORDER BY v.embedding <=> :'qv'::vector
   LIMIT 20;
  ROLLBACK;
  ```
  플랜에 `Index Scan using ... hnsw` 가 나와야 한다 — `Seq Scan` + `Sort` 로 떨어지면 통계 재계산
  (`ANALYZE document_chunk_vec_1024;`)이나 `ef_search` 조정을 검토한다. 확인 후 #713, #392 닫기.

### 테넌트 멤버 추가 · 공개 가입 폐쇄 (V132, WD-2)

- **api + web 동시 배포 필수.** 새 응답 필드(`TokenResponse.mustChangePassword`, `ErrorResponse.code`), 403 `PASSWORD_CHANGE_REQUIRED`, `GET /auth/signup-status` 를 웹이 처리해야 한다. api 만 먼저 나가면 구 웹의 로그인 화면이 가입 링크를 계속 보여 주고(제출 시 403), 임시 비밀번호 사용자는 원인 표시 없는 403 만 본다. ai-agent 는 이번에 바뀌지 않는다(admin-manager 의 `set_user_active` 안내 문구는 후속 이슈).
- V132 는 `"user".must_change_password BOOLEAN NOT NULL DEFAULT false` 한 컬럼 추가 — 기존 행 무영향, 잠금 짧음. 배포 전 스냅샷 규칙(V122 이상)은 그대로 따른다.
- **배포 즉시 운영의 공개 가입이 닫힌다**(사용자가 이미 있으므로). 이후 계정은 각 워크스페이스 관리자가 사용자 관리 > 멤버 추가로 만든다. 기존 계정은 영향 없음.
- 사용자 상세의 "활성" 스위치 의미가 바뀐다: 전역 계정 비활성화 → **이 워크스페이스 멤버십 정지**. 배포 후 **전역 계정 비활성화 수단이 없다**(운영자 콘솔 후속 이슈). 기존에 전역 `is_active=false` 로 막아 둔 계정은 그대로 막혀 있다.
- 정지는 그 테넌트 권한을 즉시 0 으로 만든다(권한 조회가 ACTIVE 멤버십을 조인) — 진행 중 세션도 다음 요청부터 403.
- 정지·제거의 "마지막 활성 ADMIN" 판정은 테넌트별 advisory lock 으로 직렬화된다(동시 요청으로 ADMIN 이 0명이 되는 것을 방지).
- 비밀번호 변경(`PUT /users/me/password`) 시 그 사용자의 **모든 refresh 세션**(다른 기기·운영자 콘솔 포함)이 폐기되고 호출자에게만 새 refresh 쿠키가 발급된다. 새 비밀번호가 현재(임시) 비밀번호와 같으면 400 으로 거부된다.
- 프로필 수정(`PUT /users/me`)·비밀번호 변경(`PUT /users/me/password`)은 워크스페이스 선택 없이 인증만으로 동작한다(임시 비밀번호 사용자가 `/change-password` 에서 막히지 않도록).
- 배포 직전 고아 확인(0 이 아니면 그 사용자는 배포 즉시 권한을 잃는다 — 중단 후 상의): **소유자 롤로 실행**(`docker exec <db> psql -U app -d smartfirehub`) — 런타임 롤 `app_tenant` 는 RLS(NOBYPASSRLS)라 `user_role` 이 0행으로 보여 쿼리가 공허하게 0 이 된다. 먼저 대조군 `SELECT count(*) FROM user_role;` 이 **0 보다 커야** 하고, 그 다음 `SELECT count(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE NOT EXISTS (SELECT 1 FROM membership m WHERE m.user_id=ur.user_id AND m.tenant_id=r.tenant_id AND m.status='ACTIVE');` 가 0 이어야 한다. 2026-10-02 소유자(app) 실측: dev user_role 18건 중 고아 0, prod 4건 중 고아 0
- 배포 후 확인: (1) `curl -s https://<host>/api/v1/auth/signup-status` → `{"open":false}`; (2) 관리자 계정으로 멤버 추가 → 새 계정 로그인 → `/change-password` 강제 → 변경 후 진입; (3) 정지한 멤버가 다른 워크스페이스로는 로그인되는지.

### 전역 계정 비활성화 · WD-2 후속 (#784~#788, 마이그레이션 없음)

- **api → admin → web·ai-agent 순서(같은 배포 창).** admin 은 `./scripts/deploy.sh admin` 으로 명시 배포(`all` 에 없음). admin 이 api 보다 먼저 나가면 "계정" 화면이 `/api/platform/accounts` 404 를 띄운다.
- 권한 조회가 `user.is_active` 를 조인한다 — 운영자 콘솔에서 비활성화한 사용자는 권한이 필요한 요청이 즉시 403, refresh 세션은 전부 폐기된다. 비활성 사용자 명의의 예약 실행 중 권한을 검사하는 스텝(Python·AI)과 ai-agent 대행 호출도 거부된다(SQL 스텝 등은 계속 돈다).
- 기존에 전역 `is_active=false` 로 막혀 있던 계정이 있으면 배포 즉시 권한 조회에서도 0 이 된다(원래 로그인 불가라 실영향 없음). 확인: `SELECT count(*) FROM "user" WHERE is_active=false;`
- 감사 로그(`ACCOUNT_DEACTIVATE`/`ACCOUNT_REACTIVATE`)는 tenant NULL 로 기록되어 현재 감사 화면에는 보이지 않는다.
- 배포 후 확인: admin "계정" 메뉴에서 검색 → 테스트 계정 비활성화 → 그 계정 로그인 거부 → 재활성화 후 로그인.

### 전역 비활성 표시 · 플랫폼 감사 로그 (WD-3~WD-9, 마이그레이션 없음)

- **api → admin → web 순서(같은 배포 창).** admin 은 `./scripts/deploy.sh admin` 으로 명시 배포. admin 이 api 보다 먼저 나가면 "감사 로그" 화면이 `/api/platform/audit-logs` 404 를 띄운다. web 의 `accountActive` 는 optional 이라 순서가 어긋나도 깨지지 않고 배지만 안 보인다.
- 플랫폼 감사 로그는 `tenant_id IS NULL` 행만 보인다(운영자 계정 조치 + 워크스페이스가 정해지지 않은 로그인·실패). 테넌트 생성·정지 감사는 아직 기록되지 않는다(후속).
- 배포 후 확인: admin "감사 로그" 메뉴에서 앞 절의 비활성화/재활성화 기록이 보이는지, web 사용자 관리에서 비활성화한 계정에 '계정 비활성(운영자)' 배지가 붙는지.

### 감사 시각 기준 · 테넌트 생명주기 감사 (WD-11~WD-16, 마이그레이션 없음)

- **배포 대상은 api + admin + ai-agent + web.** 마이그레이션은 없다(V133 예약 안 함).
- **api + admin 은 반드시 같은 배포 창에 동시 배포한다.** 플랫폼 감사 API 의 from/to 가 날짜(`yyyy-MM-dd`)에서 오프셋 필수 절대 시각으로 바뀌었다. 신 api 는 날짜만 담긴 구 admin 요청을 400 으로 거부하고, 구 api 는 신 admin 이 보내는 오프셋 값을 400 으로 거부한다 — 어느 쪽이든 한쪽만 나가면 admin 감사 로그의 기간 필터가 깨진다(기간 없는 목록은 동작). admin 은 `./scripts/deploy.sh admin` 으로 명시 배포.
- **ai-agent 는 api 와 동시에 또는 api 다음에 배포한다.** 감사 도구가 KST(+09:00) 오프셋을 붙여 보내므로 구 api 에는 맞지 않는다. web 은 응답 actionTime 에 오프셋이 붙어도 기존 파서가 존중해 호환된다.
- 테넌트 생성·정지·활성화가 tenant NULL 감사(TENANT_CREATE/SUSPEND/ACTIVATE)로 남는다. 배포 전 기록은 소급되지 않는다.
- WD-14 일괄 포맷(api Java 전체) 커밋은 런타임에 영향이 없다.
- 배포 후 확인:
  - admin 감사 로그에서 KST 오늘 날짜로 필터 → 오늘 새벽(00~09시 KST) 기록이 오늘로 잡히고 표시 시각이 KST 인지.
  - 테스트 테넌트를 정지·활성화한 뒤 감사 로그에 "테넌트 정지/활성화" 가 이름·slug 와 함께 보이는지.
  - admin 테넌트 목록·상세의 생성일이 KST 로 맞게 보이는지.

### 운영자 계정 생성 · 계정 전체 목록 (WD-46·WD-47, 마이그레이션 없음)

- **api + admin 은 반드시 같은 배포 창에 동시 배포한다.** `GET /api/platform/accounts` 응답이 배열에서 `PageResponse` 로 바뀌었다 — 한쪽만 나가면 admin "계정" 화면이 깨진다. admin 은 `./scripts/deploy.sh admin` 으로 명시 배포. web·ai-agent 는 영향 없음.
- 새 `POST /api/platform/accounts`(권한 `platform:tenant:create`)로 만든 계정은 멤버십이 없고 첫 로그인 시 비밀번호 변경이 강제된다. 감사 `ACCOUNT_CREATE`(tenant NULL).
- 배포 후 확인: admin "계정" 첫 진입에 전체 목록·페이지·소속 열이 보이는지 → 테넌트 생성에서 없는 이메일 검색 → "새 계정 만들기" → Owner 자동 선택 → 생성. 확인용 계정·테넌트는 정리한다.

### V133·V134 데이터셋 보안 등급 S1+S2 + 후속 수정 (계획 2026-10-07·2026-10-08 · 배포일은 배포 시점에 갱신)

- **api + web 동시 배포 필수.** S1(ID·목록 통제)과 S2(SQL 경로 통제)는 한 묶음으로만 배포한다 — 둘을 나누면 SQL 경로(애드혹·/query·차트·파이프라인)로 우회된다. web 만 배포하면 새 API 404.
- **배포 전 웹 단위 테스트를 수동으로 돌린다**: `cd apps/firehub-web && pnpm test:unit` — 커밋 훅은 vitest 를 돌리지 않는다(이 앱의 `pnpm test` 는 아무것도 하지 않는 자리표시자다).
- executor·ai-agent 는 재배포 불필요. executor 는 변경 없음. ai-agent 는 `src/mcp/api-client/analytics-api.ts` 의 **TypeScript 타입만** 바뀌었다(`Chart.savedQueryName` 을 `string | null` 로, `ChartData.denied?` 추가) — 이 필드를 읽는 런타임 코드가 없고 타입은 빌드 시 지워지므로 실행 동작이 같다.
- 마이그레이션 V133: 전 테넌트에 4등급 시드 + 기존 데이터셋·역할=내부, 시스템 ADMIN=기밀 백필 → **배포 직후 가시성은 배포 전과 같다**(내부 이하는 허용 목록 없음).
- 마이그레이션 V134(후속 수정, 같은 릴리스): GraphRAG 검수 항목 유일 인덱스 `uq_graph_review_item` 을 `(tenant_id, item_type, dedupe_key)` 에서 `(tenant_id, item_type, dataset_id, dedupe_key) NULLS NOT DISTINCT` 로 교체한다(인덱스 이름 유지). `NULLS NOT DISTINCT` 는 **PostgreSQL 15 이상** 문법이다 — 운영 DB 가 PG16 인지 배포 전에 확인한다(`select version()`). 새 키는 옛 키의 상위 집합이라 기존 행이 위반할 수 없다(아래 사전 확인 쿼리로 확인).
- 다음 마이그레이션은 V135(아래 V135 절)·V136(아래 V136 절)이다. **다음 신규 마이그레이션 번호 = V137**(V135 절과 같은 값 — 새 마이그레이션을 더하면 두 곳을 함께 갱신한다).
- **번호 확인**(V133·V134 작성 당시 기록): 2026-10-08 기준 main 의 최신 마이그레이션은 V132 였고 이 브랜치의 V133·V134 는 맞는 번호였다. **병합 직전에 실제 main 의 마이그레이션 목록을 다시 확인한다**(번호 충돌 전례 — V122). 배포 전 스냅샷 규칙(V122 이상)은 그대로 따른다.
- 아래 확인 쿼리는 전부 **소유자 롤로 실행**한다(`docker exec <db> psql -U app -d smartfirehub`) — 런타임 롤 `app_tenant` 는 RLS 라 행이 0 으로 보여 확인이 공허해진다.
- 배포 전 확인:
  - `select max(version::int) from flyway_schema_history` 가 132 인지.
  - V134 사전 확인: `SELECT tenant_id, item_type, dataset_id, dedupe_key, count(*) FROM graph_review_item GROUP BY 1,2,3,4 HAVING count(*) > 1` 가 0행인지(새 키는 구 인덱스의 상위 집합이라 0행이어야 정상 — 행이 나오면 구 인덱스가 깨진 것이므로 중단 후 상의).
  - 역할이 하나도 없는 활성 사용자는 어떤 데이터셋도 볼 수 없다(fail-closed) — `select u.id from "user" u join membership m on m.user_id=u.id and m.status='ACTIVE' where not exists (select 1 from user_role ur where ur.user_id=u.id and ur.tenant_id=m.tenant_id)` 가 0행인지.
  - 파이프라인 TEMP 판정: `source_pipeline_step_id` 가 있는 데이터셋은 스텝 TEMP 로 신뢰된다 — `SELECT id, table_name, created_by FROM dataset WHERE source_pipeline_step_id IS NOT NULL AND table_name NOT LIKE 'ptmp\_%'` 가 0행이어야 한다(아니면 중단 후 상의).
  - **FILE 데이터셋 저장 경로(prefix) 점검**(필수 — 결과가 0행이 아니면 중단 후 상의): 이번 배포부터 prefix 는 서버가 `datasets/<데이터셋 id>/` 로만 만들고 클라이언트 지정은 400 `FILE_PREFIX_NOT_ALLOWED` 로 거부된다. 그 전에 사용자가 직접 지정한 prefix 가 다른 데이터셋(다른 테넌트 포함) 경로를 덮고 있으면, 그 데이터셋 화면에서 남의 파일 목록·다운로드 URL 이 계속 발급된다. 두 쿼리 모두 소유자 롤로 실행한다(RLS 를 넘어 **모든 테넌트**를 봐야 한다). `LIKE` 를 쓰지 않는다 — prefix 의 `_` 가 와일드카드로 오탐을 낸다.
    - 서버 형식이 아닌 prefix(앞으로 생길 `datasets/<새 id>/` 와도 겹칠 수 있다): `SELECT c.dataset_id, d.tenant_id, c.bucket, c.prefix FROM file_dataset_config c JOIN dataset d ON d.id = c.dataset_id WHERE c.prefix <> 'datasets/' || c.dataset_id || '/'`
    - 같은 버킷에서 서로의 접두어가 되는 쌍: `SELECT a.dataset_id AS a_id, b.dataset_id AS b_id, a.bucket, a.prefix AS a_prefix, b.prefix AS b_prefix FROM file_dataset_config a JOIN file_dataset_config b ON a.bucket = b.bucket AND a.dataset_id < b.dataset_id WHERE starts_with(a.prefix, b.prefix) OR starts_with(b.prefix, a.prefix)`
    - 첫 쿼리에 행이 나오면 해당 데이터셋의 객체를 `datasets/<id>/` 로 옮기고(MinIO 복사) `file_dataset_config.prefix` 를 갱신하거나, 데이터셋 소유자와 정리 방법을 정한다 — 자동 이동은 하지 않는다.
  - (권장) 아래 "파이프라인 실행 주체" 변화에 걸릴 대상 — 생성자에게 ACTIVE 멤버십·역할이 없는 예약/API 트리거, 데이터셋이 아닌 data 스키마 테이블(`stg_import_*` 등)을 읽는 SQL 스텝 — 을 미리 찾아 둔다.
- 배포 후 확인: `select tenant_id, count(*) from security_level group by 1` 이 모든 테넌트 4, `select count(*) from dataset where security_level_id is null` = 0, `select count(*) from role where max_security_level_id is null` = 0, `select max(version::int) from flyway_schema_history` = 134, `select indexdef from pg_indexes where indexname = 'uq_graph_review_item'` 에 `dataset_id` 와 `NULLS NOT DISTINCT` 가 들어 있는지.
- **동작 변화**(관리자 공지에 포함):
  - 파이프라인은 실행 주체(run-as) 자격으로 판정된다. 실행 주체에게 그 테넌트의 ACTIVE 멤버십(또는 역할·활성 계정)이 없으면 SQL 스텝이 **실패한다** — 퇴사·제외된 생성자의 예약/API 트리거 포함.
  - 스텝 SQL 이 데이터셋이 아닌 테이블(`stg_import_*`·고아 테이블 등)을 읽으면 실행 시·다음 저장 시 **거부된다**(존재 은닉, fail-closed).
  - 분석(애드혹·/query·저장 쿼리)에서 숨김·다른 스키마·존재하지 않는 테이블은 예전의 200 + error 대신 **403 `DATASET_SQL_ACCESS_DENIED`**. 차트·대시보드 위젯은 200 + `denied:true`(위젯 단위).
  - PG 어휘 모호 형태(중첩 블록 주석, 백슬래시 든 E-문자열, 태그 달러 인용, `//`·백틱·q-인용 등)는 **400 으로 거부**된다 — 애드혹·파이프라인 SQL 모두.
  - 이상탐지 메트릭이 조회자(작업 소유자)가 볼 수 없는 데이터셋을 참조하면 그 메트릭은 **건너뛴다**(로그 경고).
  - 파이프라인 출력 쓰기 규칙:
    - 사용자가 지정한 출력 데이터셋에 쓰는(DML·REPLACE) 스텝은 실행 주체에게 그 출력의 VIEW 가 있어야 한다 — 없으면 실행이 **실패한다**(숨김 데이터셋을 덮어써 비우는 것을 막기 위함).
    - 재사용되는 러너 TEMP 가 실행 주체가 볼 수 없는 등급으로 이미 올라가 있으면 실행이 **실패한다**(관리자가 TEMP 등급을 내려야 다시 돈다).
    - 입력보다 낮은 등급의 출력에 쓰면(쓰기 하향) 실행이 **실패한다** — 자동 상향은 러너 TEMP 출력만이며, 일반 자동 상향은 S4 까지 없다.
    - **API_CALL·PYTHON 스텝**도 사용자가 지정한 출력 데이터셋에 쓰려면 볼 수 있어야 한다: 저장 시 편집자가 못 보면 403 `DATASET_SQL_ACCESS_DENIED`, 실행 시 실행 주체가 못 보면 출력 비우기(REPLACE)·적재 전에 **실패한다**(구분 불가 메시지). 저장 판정에서 빠지는 것은 **그 파이프라인 자신의** 러너 TEMP(편집 화면이 되돌려 보내는 출력 폴백)뿐이다 — 다른 파이프라인의 TEMP 를 출력으로 새로 지정하면 TEMP 예외 없이 판정돼, 볼 수 없으면 없는 id 와 같은 403 으로 지정할 수 없다. 이미 저장된 지정 출력도 같은 값을 다시 보내면 다시 판정된다(아래 알려진 한계). 등급 전파·하향 판정은 없다(외부 API 데이터·PYTHON 입력 미판정).
    - 러너 TEMP 의 스키마가 바뀌어 다시 만들 때(SQL·AI_CLASSIFY·**API_CALL·PYTHON**), 실행 주체가 기존 TEMP 를 볼 수 없으면 지우기 전에 **실패한다**(다른 실행 주체의 결과를 지우지 않음). API_CALL·PYTHON 이 기존 러너 TEMP 를 재사용할 때도 실행 주체가 볼 수 있어야 한다.
  - **AI_CLASSIFY 스텝**도 SQL 스텝과 같은 규칙으로 판정된다: 저장 시 편집자, 실행 시 실행 주체가 입력 데이터셋(명시 입력·의존 스텝 출력 자동 해석분 모두)을 볼 수 있어야 하고, 아니면 저장 403 `DATASET_SQL_ACCESS_DENIED` / 실행 **실패**(구분 불가 메시지). 삭제된 입력 데이터셋도 예전처럼 건너뛰지 않고 같은 거부로 **실패**한다. 출력은 SQL SELECT 스텝과 같다 — 러너 TEMP 는 입력 최대 등급으로 상향(허용 목록 등급이면 실행 주체 시드), 사용자가 지정한 출력은 실행 주체가 볼 수 있어야 하고 입력보다 낮으면 `SQL_WRITE_DOWNGRADE` 로 실패한다.
  - **FILE 데이터셋** 생성 폼의 "경로 프리픽스" 입력이 없어졌다 — 저장 경로는 서버가 `datasets/<id>/` 로 만든다(API 로 prefix 를 보내면 400 `FILE_PREFIX_NOT_ALLOWED`).
  - **GraphRAG 검수 인박스**: 출처 데이터셋을 볼 수 없는 검수 항목은 목록에서 빠지고, 근거·승인·거부는 404 `REVIEW_ITEM_NOT_FOUND` 다. 없는 항목도 이제 400 이 아니라 같은 404 다(숨김 항목과 구분되지 않게).
  - 파이프라인 SQL 스텝·컬럼 탐지는 사용자 SQL 을 JDBC 이스케이프 처리 없이 그대로 보낸다 — `{fn …}`·`{d '…'}` 같은 JDBC 이스케이프 표기는 이제 PG 문법 오류로 **실패**한다(애드혹 SQL 과 같은 동작). jsonb `?` 연산자는 이제 파이프라인 SQL 에서도 동작한다.
  - 정책 칩(내보내기·AI·공유)은 S1 에선 **표시만** — 강제는 S3/S4. 사용자가 "막혀 있다"고 오해하지 않도록 공지에 명시.
  - **이름·존재 노출 차단(후속 수정)**:
    - 홈 화면의 데이터셋 개수(전체·원본·파생)·최근 임포트·주의 항목·활동 피드·상태(health) 집계는 조회자가 볼 수 없는 데이터셋을 **뺀다**(사용자마다 숫자가 다를 수 있다).
    - 파이프라인 스텝의 입력·출력 데이터셋, 저장 쿼리의 연결 데이터셋이 숨김이면 응답의 **이름이 null** 이다(id 는 유지 — 편집 후 저장해도 참조가 보존된다). 웹은 자물쇠와 함께 "열람 권한 없음" 으로 표시한다.
    - 저장 시 **새로 지정한** 숨김 데이터셋 id 는 없는 id 와 같은 응답이다 — 파이프라인 입력·출력(생성·수정 모두), 저장 쿼리의 연결 데이터셋, 데이터셋 변경 트리거의 감시 데이터셋. 이미 저장돼 있던 id 를 그대로 다시 보내는 것은 통과한다(왕복 보존).
    - 다른 파이프라인의 러너 TEMP 를 API_CALL·PYTHON 출력으로 새로 지정할 때도 TEMP 예외 없이 판정한다(위 출력 쓰기 규칙).
    - 데이터셋 변경 SSE 는 같은 테넌트에서 그 데이터셋을 볼 수 있는 사용자에게만, API 연결 상태 SSE 는 같은 테넌트 사용자에게만 간다(예전에는 **모든 테넌트**에 방송됐다).
    - 차트·대시보드 위젯의 `denied` 응답에서 `config`(컬럼명 포함)와 저장 쿼리 이름이 빠진다(빈 객체·null).
    - **API 가져오기**(`POST /datasets/{id}/api-import`): 숨김 데이터셋은 없는 데이터셋과 같은 404 다(데이터셋 ID 경로 인터셉터 — 권한 유무와 무관하게 숨김·없음 응답이 같음을 라우트 열거 TC 로 고정).
  - **GraphRAG 검수 후속**:
    - 검수 대기 등록(POST)은 `datasetId` 가 **필수**다(없으면 400). 볼 수 없는 데이터셋이면 404, 그 데이터셋에 속하지 않은 청크를 근거로 대면 400.
    - 검수 결정 조회에서 숨김 데이터셋의 결정은 `none`(결정 없음)으로 답한다.
    - `GET /graph-ingests/stale`(재적재 필요 목록, 채팅 도구)이 조회자가 볼 수 없는 데이터셋을 뺀다.
    - 같은 이름(dedupe 키)의 검수 항목이 이제 **데이터셋마다 따로** 생긴다(V134) — 인박스에 같은 이름 항목이 여러 건 보일 수 있고, 승인·거부도 데이터셋별로 한다.
  - **실행 기록 오류 원문 가림**(WD-27): 파이프라인 실행 상세의 스텝 오류·로그 원문(PG 오류에 숨김 테이블명·행 값이 실릴 수 있음)은 조회자가 그 스텝이 다루는 데이터셋(출력·명시 입력·SQL 참조 테이블·`{{#N}}` 이 가리키는 스텝 출력)을 **전부** 볼 수 있을 때만 보인다. 아니면 오류는 "이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다." 로 바뀌고 로그는 비며 응답에 `errorMasked: true` 가 붙는다. 판정할 데이터셋이 없는 스텝(출력 없는 PYTHON 등)의 원문은 **실행 주체와 테넌트 관리자**에게만 보인다. 실행 단위 오류는 실행의 스텝 중 하나라도 조회자가 못 보면 "이 실행의 상세 오류는 …" 으로 가린다. 웹은 가렸을 때 "아래 오류 정보를 참고…" 안내를 숨긴다. 저장본은 지우지 않는다(조회 시점 판정).
  - **AI 리포트(proactive) 컨텍스트**의 홈 통계·활동은 **작업 소유자 자격**으로 걸러진다 — 같은 리포트라도 소유자에 따라 숫자가 다를 수 있다.
  - **허용 목록 동시 추가**: 같은 대상을 동시에 추가해도 멱등이다(예전에는 코드 없는 일반 409).
  - **보안 등급 이름 경합**: 같은 이름으로 동시에 등급을 만들거나 이름을 바꾸면 예전의 코드 없는 일반 409 대신 409 `SECURITY_LEVEL_NAME_DUPLICATE` 로 답한다(사전 검사와 같은 코드).
- **알려진 한계**(후속 — 괄호 안은 workplace WD 이슈 키):
  - **남은 이름 노출**(WD-31): 숨김 데이터셋의 **테이블명**이 다음 경로에는 남는다(내용·행은 아님).
    - 파이프라인 스텝 SQL 원문(`scriptContent`)·저장 쿼리 SQL 원문(`sqlText`) 안의 테이블명 — 원문을 고치면 실행이 바뀌므로 가리지 않는다.
    - `GET /charts`·`GET /charts/{id}` 의 `config`(컬럼명) — denied 데이터 응답에서만 뺐다(소유자가 재저장 시 덮어쓰지 않게 메타 조회는 유지).
    - API 가져오기가 만든 파이프라인의 기본 이름(`<데이터셋 이름> API Import`)과 감사 로그 설명문.
  - **실행 기록 원문 판정의 시점**(WD-27 잔여): 실행 단위·스텝 원문 공개는 **현재** 스텝 정의와 **현재** 등급으로 판정한다 — 실행 뒤 스텝 정의를 바꾸거나 등급을 내리면 과거 실행의 원문이 그 시점 기준으로는 못 볼 조회자에게 보일 수 있다. 판정 중 DB 예외가 나면 500 으로 끝난다(원문은 나가지 않는다).
  - **편집기 숨김 표시의 상한**(WD-31): 파이프라인 편집기·트리거 폼의 "열람 권한 없음" 잠금 표시는 데이터셋 목록(최대 1만 건) 안에서만 정확하다 — 1만 건을 넘는 테넌트에서는 볼 수 있는 데이터셋도 잠금으로 보일 수 있다(서버 판정에는 영향 없음).
  - **API_CALL·PYTHON 지정 출력은 같은 값 재전송도 판정한다**(WD-31): SQL 스텝과 달리 이미 저장된 지정 출력을 그대로 다시 보내도 편집자의 VIEW 를 본다 — 그 출력을 볼 자격을 잃은 편집자는 그 파이프라인을 저장할 수 없다(다른 편집자가 저장하거나 출력을 바꾼다).
  - **PYTHON 입력 우회**(WD-29): PYTHON 스텝의 **입력 읽기**는 SQL 관문을 거치지 않는 알려진 우회 경로다(편집 화면 경고만, 강제는 후속). 출력 쓰기는 막혔다. 이 우회로 숨김 데이터를 읽은 PYTHON 스텝이 공개 출력에 쓰면, 그 스텝의 로그·오류 원문이 출력을 볼 수 있는 조회자에게 보일 수 있다(실행 기록 원문 판정이 입력을 모르기 때문).
  - **GraphRAG 에 이미 적재된 내용과 등급 상향**(WD-28): 문서 적재·`graphrag_project_table`(표 투영)로 Neo4j 에 들어간 엔티티·관계·속성(표 행 값 포함)이 대상이다. 검수 인박스의 원문 근거는 V133·V134 배포에서 막혔다.
    - **V135 배포 전까지**: 데이터셋을 나중에 '민감'·'기밀'로 올려도 ai-agent 의 그래프 조회·채팅 검색으로 계속 노출된다(스펙 §7.5). **운영 절차**: 등급을 올리기 전에 그 데이터셋이 GraphRAG 에 적재됐는지(소유자 롤로 `SELECT * FROM dataset_graph_ingest WHERE dataset_id = <id>`) 확인하고, 적재돼 있으면 그래프에서 해당 데이터셋 유래 노드를 수동으로 정리한 뒤 올린다.
    - **V135 배포 후**: 출처 데이터셋을 볼 수 없는 사용자에게는 그 온톨로지의 그래프 읽기가 통째로 막히므로(아래 V135 절) 등급 상향 전 수동 정리는 필요 없다. 단 출처가 기록되지 않은 온톨로지는 게이트가 막지 못하므로(V135 절 알려진 한계), 그 경우에만 위 수동 정리를 한다. 삭제된 데이터셋이 출처인 온톨로지는 테넌트 관리자만 읽는다(V135 절).
  - ~~접근 거부 감사는 S4 로 이연~~ → V136 절에서 해결(WD-30·WD-44).
  - ~~러너 TEMP 허용 목록은 늘어나기만 한다~~ → V136 절에서 매 실행 재계산(WD-30).
  - 허용 목록의 사용자 항목은 `"user"` 행 삭제 시 함께 지워진다(`ON DELETE CASCADE`). **제품 코드에는 사용자 하드 삭제 경로가 없다**(멤버 제거·정지·전역 비활성은 행을 남긴다) — 그래서 역할 삭제와 달리 "유일 항목" 가드를 두지 않았다. 운영에서 사용자 행을 **수동으로** 지울 때는 먼저 `SELECT g.dataset_id FROM dataset_access_grant g JOIN dataset d ON d.id = g.dataset_id JOIN security_level l ON l.id = d.security_level_id WHERE g.user_id = <id> AND l.allowlist_required AND (SELECT count(*) FROM dataset_access_grant x WHERE x.dataset_id = g.dataset_id) = 1` (소유자 롤) 가 0행인지 확인한다. 같은 이유로, 유일 허용 항목인 사용자를 워크스페이스에서 **제거·정지**하면 그 데이터셋은 관리자 우회(admin_bypass) 외에는 아무도 못 본다 — 관리자가 허용 목록에 다른 항목을 추가해 복구한다(가드는 후속 판단).
  - **검수 결정은 데이터셋을 넘어 재사용된다**: V134 이후 검수 항목은 데이터셋마다 따로 생기지만, ingest 의 결정 조회(데이터셋 없이 이름으로 묻는 ai-agent 계약)는 조회자가 볼 수 있는 행 중 **가장 최근의 사람 결정**(승인·거부)을 따른다 — 데이터셋 Z 에서 내린 승인이 데이터셋 X 의 ingest 에도 적용된다(수용한 트레이드오프).
  - **서로 다른 이름의 등급을 동시에 만들면** 둘 다 같은 다음 순위를 잡아 순위 유일 제약(`uq_security_level_rank`) 위반이 되고, 코드 없는 일반 409 "Data integrity violation" 으로 끝난다(500 아님) — 다시 시도하면 성공한다. 이름 중복만 `SECURITY_LEVEL_NAME_DUPLICATE` 로 번역한다.
  - 역할 열람 등급 변경은 **역할의 현재 등급**과 **새 등급** 둘 다 본인 열람 등급 이하여야 한다 — 본인보다 높은 등급의 역할은 낮추는 변경도 403 `CLEARANCE_ABOVE_OWN` 으로 거부된다(최상위 자격이 아닌 `role:write` 보유자는 상위 역할을 고칠 수 없다; 최상위 자격 관리자는 영향 없음. 역할 화면은 ADMIN 전용이라 UI 에서는 사실상 닿지 않는다).
- **롤백**: V133 은 새 컬럼에 DEFAULT 함수(`tenant_default_security_level_id()`)를 두어 구 코드의 INSERT 도 통과하고, Flyway 는 기본값(`*:future` 무시)으로 앞선 마이그레이션을 무시하므로 **이미지만 이전 버전(api+web 함께)으로 되돌리면 된다** — DB 는 그대로 둔다. 이 경우 등급 통제가 사라져 배포 전 가시성으로 돌아간다(등급·허용 목록 데이터는 보존돼 재배포 시 다시 적용). V133 을 DB 에서 되돌리는 down 스크립트는 없다 — 꼭 필요하면 배포 전 스냅샷 복원으로만 한다.
  - **단, V134 는 이미지만 되돌리면 깨진다.** 구 코드의 검수 대기 등록은 `ON CONFLICT (tenant_id, item_type, dedupe_key)` 로 옛 3열 유일 인덱스를 추론하는데 V134 뒤에는 그런 인덱스가 없어 **500(PG 42P10)** 이 나고, 구 코드의 결정 조회는 키당 1행을 가정해 데이터셋별 중복 행이 생긴 뒤에는 오류가 난다. 그래서 아래 순서로 한다(전부 소유자 롤, 새 api 가 떠 있는 채로 인덱스를 바꾸면 그 사이 새 코드의 4열 `ON CONFLICT` 가 같은 42P10 을 내고 ingest 가 중복 행을 새로 만들 수 있다):
    1. **api 를 중지한다**(web 은 그대로 둬도 된다 — 검수 등록은 api 만 한다).
    2. `SELECT tenant_id, item_type, dedupe_key, count(*) FROM graph_review_item GROUP BY 1,2,3 HAVING count(*) > 1` 가 0행인지 확인한다. 행이 있으면(배포 후 데이터셋별 중복 항목이 생김) 어느 행을 남길지 상의한다 — 자동 삭제하지 않는다. 정리할 수 없으면 배포 전 스냅샷 복원으로 간다.
    3. 0행이면 **한 트랜잭션으로** 옛 인덱스를 되살리고 V134 이력을 지운다 — 트랜잭션 없이 하면 CREATE 가 실패했을 때 DROP 만 남아 유일 인덱스가 하나도 없는 상태(옛·새 코드 모두 42P10)가 된다:
       `BEGIN; DROP INDEX uq_graph_review_item; CREATE UNIQUE INDEX uq_graph_review_item ON graph_review_item (tenant_id, item_type, dedupe_key); DELETE FROM flyway_schema_history WHERE version = '134'; COMMIT;`
       V134 이력 행을 지우는 이유: 남겨 두면 재배포 때 Flyway 가 V134 를 적용된 것으로 보고 다시 돌리지 않아, 새 코드의 4열 `ON CONFLICT` 가 같은 42P10 으로 깨진다.
    4. 이전 이미지(api+web)로 기동한다.

### V135 지식그래프 읽기 게이트 (WD-28, 계획 2026-10-08 · 배포일은 배포 시점에 갱신)

- **api + ai-agent + web 동시 배포 필수.**
  - api 만 새 버전이면 시각화(`/ontology/{id}/graph`)는 막히지만 MCP `graphrag_query`·`graphrag_structured_query` 는 구 ai-agent 가 판정 없이 읽는다.
  - ai-agent 만 새 버전이면 판정 엔드포인트(`/ontology/{id}/graph-access`)가 404 다. 그러면 **모든 그래프 읽기가 막힌다**(fail-closed) — MCP 두 도구는 제한 문구, 시각화는 구 api 가 ai-agent 의 403 을 몰라 오류로 보인다. 쓰기(적재·검수 반영)는 영향 없다.
  - web 이 구버전이면 제한이 "그래프를 불러오지 못했습니다" 오류(재시도 버튼)로 보인다.
- 마이그레이션 V135: `graph_ontology_source`(tenant_id, ontology_id, dataset_id, first_written_at) + RLS(`graph_ontology_source_tenant_isolation`, FORCE 없음) + 인덱스 `idx_graph_ontology_source_ontology(ontology_id)`(판정·CASCADE 용) + 백필. 백필은 현재 `dataset_ontology` 전부 ∪ `dataset_mapping` 전부(draft 포함)다. 이후 연결(`dataset_ontology`)·매핑 저장 때마다 출처를 삽입만 한다(재연결·매핑 삭제로 지우지 않음, 온톨로지 삭제 시 CASCADE).
- **번호 확인**: 2026-10-08 기준 main 최신은 V134. **병합 직전에 실제 main 의 마이그레이션 목록을 다시 확인한다**(V122 충돌 전례). 배포 전 스냅샷 규칙(V122 이상)을 따른다. **다음 신규 마이그레이션 번호 = V137**(2026-10-10 갱신 — V136 = 데이터셋 보안 S4 `analytics_query_run`).
- 아래 쿼리는 전부 **소유자 롤로 실행**한다(`docker exec <db> psql -U app -d smartfirehub`). `app_tenant` 는 RLS 때문에 0행으로 보여 공허해진다.
- 배포 전 확인:
  - `select max(version::int) from flyway_schema_history` 가 134 인지.
  - **테넌트마다 기본 등급이 있는지** — 아래 미리 보기가 기본 등급(`is_default`)을 기준으로 하므로, 기본 등급이 없는 테넌트는 조용히 빠진다. 0행이어야 한다(행이 나오면 중단 후 상의).
    `SELECT t.id FROM tenant t WHERE NOT EXISTS (SELECT 1 FROM security_level x WHERE x.tenant_id = t.id AND x.is_default)`
  - **배포 직후 막힐 온톨로지 미리 보기** — 테넌트 기본 등급보다 rank 가 높거나 허용 목록이 필요한(`allowlist_required`) 등급의 출처를 가진 온톨로지. 등급 이름('내부' 등)은 관리 화면에서 바꿀 수 있으므로 이름으로 비교하지 않는다. 운영은 현재 데이터셋 92개가 전부 기본 등급('내부')이라 0행을 예상한다. 행이 나오면 해당 온톨로지를 그 등급을 볼 수 없는 사용자에게 막아도 되는지 상의한다.
    `SELECT s.tenant_id, s.ontology_id, d.id AS dataset_id, sl.name AS level, sl.allowlist_required FROM (SELECT tenant_id, ontology_id, dataset_id FROM dataset_ontology UNION SELECT tenant_id, ontology_id, dataset_id FROM dataset_mapping) s JOIN dataset d ON d.id = s.dataset_id AND d.tenant_id = s.tenant_id JOIN security_level sl ON sl.id = d.security_level_id JOIN security_level dl ON dl.tenant_id = s.tenant_id AND dl.is_default WHERE sl.rank > dl.rank OR sl.allowlist_required`
    이 쿼리는 근사치다 — 실제 판정은 사용자마다 역할의 최대 열람 등급·허용 목록·관리자 우회로 달라진다(기본 등급보다 낮은 역할만 가진 사용자는 0행이어도 막힐 수 있고, 높은 역할의 사용자는 행이 나와도 막히지 않는다). 역할이 없는 활성 사용자는 원래 어떤 데이터셋도 못 보므로 출처가 있는 그래프는 모두 막힌다.
  - **재연결 이력 수동 점검 후보** — 첫 적재보다 바인딩 시각이 늦은 데이터셋이다. 다른 온톨로지로 재연결됐을 수 있고, 그 경우 옛 온톨로지 출처는 백필이 복원하지 못한다. 행마다 예전 온톨로지를 확인하고, 필요하면 수동으로 `INSERT INTO graph_ontology_source (tenant_id, ontology_id, dataset_id) VALUES (…) ON CONFLICT DO NOTHING` 한다(배포 후, 테이블이 생긴 뒤).
    `SELECT g.tenant_id, g.dataset_id, min(g.ingested_at) AS first_ingest, o.ontology_id AS current_ontology, o.bound_at FROM dataset_graph_ingest g JOIN dataset_ontology o ON o.dataset_id = g.dataset_id AND o.tenant_id = g.tenant_id GROUP BY g.tenant_id, g.dataset_id, o.ontology_id, o.bound_at HAVING o.bound_at > min(g.ingested_at)`
    (`ingested_at` 은 timestamp, `bound_at` 은 timestamptz 라 세션 시간대 기준으로 비교된다 — 경계 근처 행은 오탐일 수 있으니 행마다 확인한다.)
- 배포 후 확인:
  - `select max(version::int) from flyway_schema_history` = 135.
  - 백필 건수: `SELECT count(*) FROM graph_ontology_source` ≥ `SELECT count(*) FROM (SELECT tenant_id, ontology_id, dataset_id FROM dataset_ontology UNION SELECT tenant_id, ontology_id, dataset_id FROM dataset_mapping) s`
  - 일반 사용자로 지식그래프 "그래프 탐색" 탭과 채팅의 그래프 질문이 배포 전처럼 동작하는지(전부 '내부'면 막히는 사용자 없음).
- 동작 변화:
  - 온톨로지 그래프의 출처 데이터셋 중 하나라도 볼 수 없는 사용자는 그 온톨로지의 그래프 읽기 세 경로가 모두 막힌다. 어느 데이터셋 때문인지는 밝히지 않는다.
    - 시각화("그래프 탐색" 탭): api 가 403 `GRAPH_READ_RESTRICTED` 를 돌려주고 web 은 오류색·재시도·토스트 없이 자물쇠 안내 `이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 표시할 수 없습니다.` 를 보인다. 제한 상태에선 이름 검색·타입 묶기·타입 필터를 숨긴다(온톨로지 선택기는 유지).
    - MCP 두 도구(`graphrag_query`·`graphrag_structured_query`): `이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 조회할 수 없습니다.`
    - ai-agent `/agent/graph` 도 대행 사용자 기준으로 다시 판정한다(이중 방어, 403 `GRAPH_READ_RESTRICTED`).
  - 출처 중 **삭제된 데이터셋**(출처 행은 있는데 dataset 이 없음)이 하나라도 있는 온톨로지는 **테넌트 관리자만** 그래프를 읽는다(관리자 판정은 실행 기록 원문 공개와 같은 `Clearance.tenantAdmin`; 판정 불가면 막힘). 삭제된 데이터셋은 등급이 없어 판정할 수 없는데 그 내용은 그래프에 남아 있기 때문이다. 관리자도 남은 **존재하는** 출처는 일반 규칙대로 판정한다.
  - 판정은 사용자 신원 기준이며 데이터셋 목록과 같은 규칙(`DatasetAccessGuard.visibleCondition`)을 쓴다 — 테넌트 관리자도 허용 목록이 필요한 등급에서는 그 등급에 `admin_bypass` 가 켜져 있지 않으면(V133 시드는 전부 꺼짐) 허용 목록 밖일 때 막힌다. 판정 조회(`graph-access`)는 읽기 지점(시각화·MCP 두 도구·평가 스크립트)에서만 한다 — 적재·표 투영·추론·describe·검수 반영은 판정을 묻지 않으므로 판정 장애와 무관하게 진행한다.
  - 연결만 하고 적재하지 않은 데이터셋도 출처로 잡는다(안전 쪽 판정).
  - 온톨로지 스키마(목록·요소 편집, "지식 모델" 탭)와 적재·표 투영·검수 반영은 그대로다.
  - 실행 기록 오류 가림 문구(WD-27)도 같은 자물쇠 안내 컴포넌트를 쓰게 되어 어절 단위로 줄바꿈된다(표시만 바뀜).
- 알려진 한계:
  - **삭제된 출처가 있는 온톨로지는 일반 사용자에게 계속 막힌다.** 출처 행이 지워지지 않으므로 데이터셋을 지운 뒤에도 그 온톨로지는 관리자 전용으로 남는다. 일반 사용자에게 다시 열려면 아래 "출처는 지워지지 않아" 항목의 절차(Neo4j 잔존 확인 → 출처 행 삭제)를 그 삭제된 데이터셋 id 로 밟는다. 데이터셋이 지워지면 `document_chunk` 행도 남지 않아 `<chunkIds>` 를 구할 수 없을 수 있다. 그때는 `sourceDatasetIds` 조건만으로 확인하는데, 청크 id 만 남은 적재분은 이 조건으로 잡히지 않으므로 확신이 없으면 출처 행을 지우지 않는다(관리자 전용으로 둔다). 백필은 연결·매핑(데이터셋 삭제 시 함께 지워짐)에서만 오므로 배포 직후에는 이런 온톨로지가 없고, 배포 **이후** 데이터셋을 지울 때 생긴다.
  - V135 이전에 다른 온톨로지로 적재했다가 재연결한 이력은 백필이 복원하지 못한다(위 수동 점검).
  - 판정이 거칠다. 기밀 출처 하나가 섞이면 온톨로지 전체가 막힌다(노드 단위 판정은 S3 이후).
  - **출처 기록이 없는 온톨로지는 신원이 있는 사용자 누구나 읽는다**(판정할 출처가 없으므로 통과). V135 이전 재연결로 출처가 빠진 온톨로지가 여기에 해당할 수 있다 — 위 재연결 수동 점검이 그 보완이다.
  - **출처는 지워지지 않아 과잉 차단이 영구히 남을 수 있다.** draft 매핑만 만들었거나, 잘못 연결했다가 바로 바꾼 데이터셋도 출처로 남는다. 그 데이터셋이 높은 등급이면 그 온톨로지는 해당 등급을 못 보는 사용자 전원에게 계속 막힌다. 정리 도구는 없고, 해제는 소유자 롤 수작업뿐이다. **그 데이터셋의 내용이 그래프에 실제로 남아 있지 않음을 확인한 경우에만** 지운다.
    1. Neo4j 에서 잔존 여부를 확인한다. `<O>`=온톨로지 id, `<D>`=데이터셋 id, `<chunkIds>`=`SELECT id FROM document_chunk WHERE dataset_id = <D>` 결과 목록. 두 쿼리가 모두 0 이어야 한다:
       `MATCH (n:Entity {ontologyId: <O>}) WHERE <D> IN coalesce(n.sourceDatasetIds, []) OR any(c IN coalesce(n.sourceChunkIds, []) WHERE c IN [<chunkIds>]) RETURN count(n)`
       `MATCH (a:Entity {ontologyId: <O>})-[r:REL]->(:Entity {ontologyId: <O>}) WHERE <D> IN coalesce(r.sourceDatasetIds, []) OR any(c IN coalesce(r.sourceChunkIds, []) WHERE c IN [<chunkIds>]) RETURN count(r)`
    2. 0 이면 소유자 롤로 `DELETE FROM graph_ontology_source WHERE tenant_id = <T> AND ontology_id = <O> AND dataset_id = <D>` 한다. 그 데이터셋이 아직 그 온톨로지에 연결·매핑돼 있으면 다음 연결·매핑 저장 때 다시 기록되므로, 연결·매핑을 먼저 정리한다.
  - **판정 조회 일시 장애는 읽기를 막되 "열람 권한 없음"과 구분해 알린다.** ai-agent 의 `graph-access` 호출이 네트워크·5xx·타임아웃·404 로 실패하면 fail-closed 로 읽기를 막는다. MCP 두 도구는 `지식그래프 열람 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.` 를, ai-agent `/agent/graph` 는 502 `GRAPH_READ_CHECK_FAILED` 를 돌려준다. api 는 이 502 를 403 으로 바꾸지 않고 일반 오류(502)로 전달하므로 web 은 자물쇠 안내가 아니라 재시도 가능한 오류를 보인다(단 api 프록시는 ai-agent 를 부르기 전에 자기 판정을 먼저 하므로, 시각화에서 이 경우는 api 판정 통과 후 ai-agent 재판정만 실패한 드문 경우다). 이런 오류가 반복되면 ai-agent 로그의 `[graphrag] 그래프 읽기 판정 조회 실패` 와 api 로그를 먼저 확인한다.
  - 온톨로지 스키마(타입·속성 이름)는 판정 대상이 아니다. `infer_ontology`·`infer_mapping` 은 데이터셋을 프로파일링해 이름을 만들므로, 숨김 데이터셋의 컬럼 의미가 스키마에 드러날 수 있다 → WD-31.
- **롤백**:
  - V135 는 새 테이블만 더하므로 **이미지만 이전 버전(api+ai-agent+web 함께)으로 되돌리면 된다**. DB 는 그대로 둔다(구 코드는 이 테이블을 읽지도 쓰지도 않는다). 롤백 동안은 게이트가 없어 배포 전 가시성으로 돌아간다.
  - 롤백 기간에 새로 만든 연결·매핑은 출처가 기록되지 않는다. **재배포 직후 아래 백필 INSERT 를 소유자 롤로 한 번 다시 실행**한다(V135 의 BACKFILL 구간과 같은 문, `ON CONFLICT DO NOTHING` 이라 멱등):
    `INSERT INTO graph_ontology_source (tenant_id, ontology_id, dataset_id) SELECT tenant_id, ontology_id, dataset_id FROM dataset_ontology UNION SELECT tenant_id, ontology_id, dataset_id FROM dataset_mapping ON CONFLICT DO NOTHING;`
  - 이 재실행이 복원하는 것과 못 하는 것:
    - 복원된다: 롤백 기간에 생긴 연결·매핑 중 **재배포 시점에 아직 남아 있는 것**(현재 `dataset_ontology`·`dataset_mapping` 행).
    - 복원되지 않는다: 롤백 기간 **안에서** 다른 온톨로지로 재연결됐거나 삭제된 매핑의 **옛** 온톨로지 출처. 그 사이 옛 온톨로지로 적재했다면 그래프에 내용이 남아 있는데 출처가 없어 누구나 읽는다(위 "출처 기록이 없는 온톨로지" 한계). 롤백 시작 시각 이후 `dataset_ontology.bound_at` 이 바뀐 데이터셋을 뽑아(`SELECT tenant_id, dataset_id, ontology_id, bound_at FROM dataset_ontology WHERE bound_at > '<롤백 시작 시각>'`) 그 기간의 `dataset_graph_ingest` 이력과 대조하고, 필요하면 옛 온톨로지 출처를 수동 INSERT 한다(위 재연결 점검과 같은 방식).

### S3 AI 통제 (WD-39·WD-40·WD-31⑤, 마이그레이션 없음 · 계획 2026-10-09 · 배포일은 배포 시점에 갱신)

- **api + web + ai-agent 동시 배포 필수.** api 만 올리면 ai-agent 가 POLICY_BLOCKED 를 일반 오류 문자열로 보이고, ai-agent 만 올리면 `X-AI-Purpose` 헤더를 받아 줄 api 가 없다. 흐름 B·C 와 함께 한 번에 배포한다(보충 스펙 1절).
- **A 단독 배포 금지 — A·B·C 동시 배포.** 등급 변경 이벤트(`DatasetSecurityLevelChangedEvent`·`SecurityLevelsChangedEvent`)는 흐름 B 의 `DatasetSecurityService`·`SecurityLevelService` 가 발행한다(공통 결정 R2). A 만 나가면 리스너는 있으나 발행자가 없어, 등급 상향·`ai_policy` 강화·등급 순서 변경 뒤의 외부 벡터 정리가 일어나지 않는다(기동 시 1회 정리와 임베딩 호스팅 변경 이벤트만 동작). 병합 후 main 에서 실제 등급 변경 → 벡터 정리 종단 테스트(`AiVectorPurgeTest.realLevelChange_viaDatasetSecurityService_purgesThatDatasetAfterCommit`, 추가됨)를 통과시킨 뒤에 배포한다.
- 마이그레이션 없음. 다음 신규 마이그레이션 번호는 위 V135 절의 값 그대로다.
- **배포 직후 가시성 변화(의도된 동작)**: 모든 AI 자격증명·임베딩 설정의 호스팅 위치가 기본 "외부"다. 그래서 `ai_policy = SELF_HOSTED_ONLY|DENY` 등급(기본 시드: 민감·기밀)의 데이터셋은
  - AI 채팅의 데이터셋 목록·검색·스키마 목록에서 빠지고, 상세·행·SQL 도구는 "차단됨"(POLICY_BLOCKED)이 된다.
  - **AI 채팅의 쓰기 도구도 막힌다**: `DatasetAccessInterceptor` 가 `/api/v1/datasets/{id}/**` 하위 **모든 메서드**(GET 뿐 아니라 행 추가·수정·삭제·truncate·가져오기·파일 업로드 등 POST/PUT/DELETE)에서 `requireView` 를 부르고, AI 대행 요청이면 그 안에서 AI 판정까지 한다. 그래서 민감·기밀 데이터셋은 채팅으로 쓰기도 POLICY_BLOCKED 다(웹 화면에서 사람이 직접 하는 쓰기는 영향 없음). "채팅으로 행을 넣어 달라"는 요청이 막힌다는 문의에 대비한다.
  - 시맨틱 검색·행 검색의 의미 검색 대상에서 빠진다(행 검색은 키워드 검색만, 상태 `KEYWORD_ONLY`).
  - AI_CLASSIFY 스텝은 저장·실행이 POLICY_BLOCKED 로 실패한다(분류 공급자가 외부로 선언된 동안).
  - **운영 주의 — 민감 입력 AI_CLASSIFY 가 있는 파이프라인**: 저장 판정은 AI_CLASSIFY 의 **입력 전부를 저장할 때마다** 다시 본다(새로 추가한 입력만 보는 SQL·PYTHON·API_CALL 의 왕복 보존 규칙과 다르다 — `PipelineService.saveSteps`). 그래서 그런 파이프라인은 **다른 스텝·이름·설명만 고쳐도 저장되지 않는다.** 예약·트리거 실행도 실행 시점에 같은 판정(`PipelineAsyncRunner`)을 거쳐 **매 주기 반복 실패**한다. 해결은 분류 자격증명을 자체 호스팅으로 선언(사내 게이트웨이인 경우)하거나, 그 스텝의 입력을 바꾸거나, 스텝을 지우는 것뿐이다. 배포 전에 해당 파이프라인 소유자에게 알린다.
  - **운영 주의 — Proactive 이상탐지 데이터셋 메트릭**: 수집 값이 이상 감지 시 리포트(외부 LLM·메일·Slack)에 실리므로 폴링 실행도 공유 목적 규칙(아래)으로 판정한다. 배포 전에 저장된 민감·기밀 데이터셋 메트릭도 저장 시 판정을 거친 적이 없어 여기서 막힌다 — **민감 메트릭은 채팅·임베딩을 둘 다 자체 호스팅으로 선언하기 전까지, 기밀 메트릭은 `share_policy = DENY` 라 항상 폴링 수집이 중단된다**(api 로그에 `skipped … (POLICY_BLOCKED)` 경고, 이벤트·리포트 없음). 해당 잡 소유자에게 미리 알린다.
- **기동 시 1회 정리 잡**: 기동 완료 후 백그라운드에서 테넌트별로 정책 위반 벡터(메타 임베딩·문서 청크 벡터·행 검색 벡터)를 지우고 `tenant_settings.security.ai_vector_purge_v1 = done` 을 남긴다. 예외가 나거나 행 검색 색인 정리가 하나라도 실패한 테넌트는 플래그를 남기지 않아 다음 기동에 다시 돈다(정리는 멱등). 확인(소유자 롤): `SELECT tenant_id, value, updated_at FROM tenant_settings WHERE key = 'security.ai_vector_purge_v1';` — **기동 시점에 ACTIVE 였던 테넌트**마다 한 행이 있어야 한다(잡은 기동 시 `TenantScopedRunner.forEachActiveTenant` 로 그때의 ACTIVE 테넌트만 돈다). 기동 뒤에 만들거나 다시 활성화한 테넌트, 기동 시 정지 상태였던 테넌트는 행이 없는 게 정상이다 — 다음 기동 때 처리된다. 현재 ACTIVE 수와 단순 비교하지 않는다. 모자라면 api 로그의 "배포 시점 외부 벡터 정리" 경고를 본다. 끄려면 api 컨테이너 환경에 `SECURITY_AI_VECTOR_PURGE_STARTUP_ENABLED=false`(프로퍼티 `security.ai-vector-purge.startup-enabled`, 기본 true — `.env` 에만 두면 주입되지 않으니 compose 의 `environment` 에 넣는다). 끈 채 기동하면 플래그도 남기지 않으므로, 다시 켜고 재기동하면 그때 1회 돈다.
- **배포 순서 선택지 — 운영 임베딩이 자체 호스팅(Ollama)인 경우**: 그냥 배포하면 호스팅 기본값이 "외부"라 1회 잡이 민감 데이터셋의 벡터(실제로는 사내 Ollama 가 만든 것)를 지우고, 관리자가 자체 호스팅을 선언하는 순간 전부 다시 임베딩한다(불필요한 삭제 + 대량 재임베딩). 피하려면:
  1. api 컨테이너 환경에 `SECURITY_AI_VECTOR_PURGE_STARTUP_ENABLED=false` 를 넣고 api+web+ai-agent 를 배포한다.
  2. 테넌트 관리자(`security:settings` 보유)가 **설정 › 임베딩 › 호스팅 위치 = 자체 호스팅**을 저장한다(테넌트마다).
  3. 환경 변수를 지우거나 `true` 로 바꾸고 api 를 재기동한다 — 1회 잡이 자체 호스팅 기준으로 돌아 기밀(`ai_policy = DENY`) 벡터만 지우고 플래그를 남긴다.
  - 주의: 이 환경 변수는 **기동 시 1회 잡만** 끈다. 이벤트 기반 정리(데이터셋 등급 변경·등급 정의 변경·임베딩 호스팅 선언 변경 커밋 시)는 플래그와 무관하게 그대로 돈다 — 1~2 단계 사이에 등급을 바꾸면 그 시점의 호스팅("외부") 기준으로 민감 벡터가 지워진다. 1~2 단계 사이에는 등급 변경을 미룬다. 정리 전이라도 불허 데이터셋은 검색 술어·재임베딩 판정식에서 이미 빠지므로 남아 있는 벡터가 외부로 나가지는 않는다.
- **이후 정리 트리거**: 데이터셋 등급 변경·등급 정의 변경·임베딩 호스팅 선언 변경이 커밋되면 같은 정리가 비동기로 돈다(현재 상태 기준이라 방향과 무관하게 멱등).
- **운영 조치(자체 호스팅 Ollama 를 쓰는 테넌트)**: 운영 임베딩은 호스트 Ollama(bge-m3)다. 관리자가 **설정 › 임베딩 › 호스팅 위치 = 자체 호스팅**을 저장하면 재임베딩 잡이 투입되고 행 검색 스윕이 의미 색인을 다시 만든다(수 분~). 자체 호스팅 선언에는 `security:settings` 권한이 필요하다. 채팅·분류 자격증명이 사내 opencode 게이트웨이면 같은 화면(AI 탭)에서 선언한다. **Claude 계열(sdk/cli/cli-api)은 항상 외부**라 선언할 수 없다.
- **GraphRAG 기적재분은 자동 회수되지 않는다**(스펙 §7.5) — 정리 잡은 **수동 정리 대상을 표시만** 한다. 정리(1회 잡·이벤트)가 돌 때 불허 데이터셋 중 그래프에 내용이 쓰였을 수 있는 것(`dataset_graph_ingest` 적재 이력 또는 `graph_ontology_source` 출처 기록이 있는 것)을 api 로그에 경고로 남긴다: `외부 공급자 불허 데이터셋의 GraphRAG 기적재분 — 자동 회수 불가, 수동 정리 대상: tenant=…, datasets=[…]`(데이터셋 id 만). 회수하지 않으므로 정리가 돌 때마다 다시 찍힌다. 로그 없이 확인하려면(소유자 롤, 임베딩이 외부로 선언된 테넌트 기준 — 자체 호스팅 테넌트는 `ai_policy = 'DENY'` 만 해당):
  ```sql
  SELECT d.tenant_id, d.id AS dataset_id, d.name, sl.ai_policy
  FROM dataset d JOIN security_level sl ON sl.id = d.security_level_id
  WHERE sl.ai_policy <> 'ALL'
    AND (EXISTS (SELECT 1 FROM dataset_graph_ingest g WHERE g.tenant_id = d.tenant_id AND g.dataset_id = d.id)
      OR EXISTS (SELECT 1 FROM graph_ontology_source s WHERE s.tenant_id = d.tenant_id AND s.dataset_id = d.id))
  ORDER BY d.tenant_id, d.id;
  ```
  출처 기록은 연결·매핑 저장 시점에 남아 실제 적재보다 넓을 수 있다(경고 용도라 넓게 잡음). 대상마다 그래프에서 해당 데이터셋 유래 노드를 수동으로 정리하거나, 정리 전까지 해당 온톨로지 그래프가 채팅(외부 LLM)으로 읽히는 것을 감수할지 판단한다 — V135 읽기 게이트는 VIEW 기준이라 AI 정책으로는 막지 않는다. 배포 전 이력은 V133·V134 절의 수동 점검 절차도 함께 따른다.
- **공유 목적 규칙**: GraphRAG 적재·추론과 Proactive 리포트는 채팅·임베딩이 **둘 다** 자체 호스팅으로 선언돼야 민감 데이터를 쓴다(GraphRAG 가 엔티티 이름을 임베딩 공급자로도 보내기 때문).
- **Slack 인바운드 채팅은 공유(SHARE) 목적으로 판정한다**: Slack 에서 묻고 Slack 으로 답하는 채팅은 답변이 외부 채널로 발송되므로 api(`SlackInboundService` → `AiChatRequestBuilder`)가 요청에 `aiPurpose=share` 를 싣고, ai-agent 가 그 실행의 MCP 호출에 `X-AI-Purpose: share` 를 붙인다. 그래서 채팅을 자체 호스팅으로 선언해도 `share_policy = DENY` 등급(기본 시드: 기밀) 데이터는 Slack 답변 경로에서 POLICY_BLOCKED 로 막히고, AI 판정도 공유 목적 호스팅 규칙(채팅·임베딩 모두 자체 호스팅일 때만 자체 호스팅으로 봄)을 따른다. 목적은 서버가 요청 출처로 정하며 ai-agent 는 'share' 외의 값을 버린다. 그 결과 Slack 채팅은 웹 채팅보다 좁다 — 기밀은 호스팅 선언과 무관하게 Slack 답변에서 막히고, 민감은 채팅·임베딩을 둘 다 자체 호스팅으로 선언해야 쓰인다. "웹에서는 되는데 Slack 에서는 막힌다" 문의에 대비해 Slack 연동 테넌트 관리자에게 미리 알린다. 그 밖의 알려진 한계: 정리 시점에 이미 진행 중인 임베딩 작업 전반(행 검색 동기화 주기·메타 재임베딩 배치)과 정리가 경합할 수 있다 — 진행 중 작업은 다음 배치 전에 게이트를 다시 보고 멈추며, 남은 벡터는 다음 주기(행 검색은 키워드 전용 재색인) 때 사라진다. 정리가 진행 중인 행 검색 재구축 주기와 겹치면 키워드 전용 전환이 백오프만큼(1분+) 늦어질 수 있다 — 그동안에도 노출이 늘지는 않는다(검색 술어가 불허 데이터셋을 거른다).
- 감사: 공급자 호스팅 선언 변경은 `audit_log.action_type = 'AI_PROVIDER_HOSTING_CHANGE'`(대상 슬롯·이전값·새값·사용자)로 남는다.
- 롤백: 이미지만 이전 버전으로(api+web+ai-agent 함께). DB 는 그대로 둔다 — 구 코드는 `payload.hosting`·`embedding.config.hosting`·플래그 키를 읽지 않는다. 정리된 벡터는 롤백 후 재임베딩 판정식이 다시 만든다(외부 공급자로 다시 보내짐에 유의).

### V136 데이터셋 보안 S4 — 출구·전파·감사 (WD-42·43·44·30, 계획 2026-10-09 · 배포일은 배포 시점에 갱신)

- **배포 모듈: api + web + ai-agent + executor 를 한 번에 배포한다**(흐름 A 마이그레이션 없음·B V136·C V137 일괄, 보충 스펙 §1). 두 흐름의 마이그레이션이 한 배포에서 함께 적용된다(흐름 C 의 V137 절 참고). **C 는 병합 직전 V137 로 재번호한다(현재 C 워크트리는 V138).**
  - api 와 web 은 반드시 함께 — 쿼리 결과 내보내기 엔드포인트가 바뀌었다. 구 web 은 없어진 `POST /api/v1/query-results/export` 를 불러 404 가 난다. 새 엔드포인트는 `POST /api/v1/analytics/queries/runs/{runId}/export`.
  - 흐름 C(executor 슬롯 롤 읽기 제한)와도 반드시 함께 — 아래 PYTHON 출력 등급은 C 의 슬롯 롤이 실제로 읽을 수 있는 범위를 전제로 한다. B 만 먼저 나가면 PYTHON 이 앱 연결로 더 높은 등급을 읽고도 출력은 낮게 매겨질 수 있다(과소 등급).
- **마이그레이션:** V136 `analytics_query_run`(새 테이블, RLS 형태 (a), FORCE 없음). 기존 데이터 변경 없음. 배포 전 스냅샷 규칙(V122 이상)은 그대로 따른다. **병합 직전에 실제 main 의 마이그레이션 목록을 다시 확인한다**(V122 충돌 전례).
- **동작 변화(사용자 체감):**
  - 내보내기:
    - 내보내기는 `data:export` 권한 **그리고** 등급 `export_policy` 를 모두 만족해야 한다.
    - '기밀'(export_policy=DENY) 데이터셋은 서버 내보내기·비동기 내보내기 파일 다운로드(`GET /api/v1/exports/{jobId}/file`)·파일형 데이터셋 오브젝트 다운로드(`GET /api/v1/datasets/{id}/objects/url?disposition=attachment`)가 403 `POLICY_BLOCKED` 다.
    - '민감'(PERMISSION)은 `data:export_restricted` 권한이 있어야 한다.
    - 비동기 내보내기 파일은 **다운로드 시점** 등급으로 다시 판정한다(작업 생성 뒤 등급이 오르면 받을 수 없다).
    - 오브젝트 presign 은 `disposition` 파라미터로 나뉜다. 기본값 `inline`(미리보기·열기)은 VIEW 만 보고, `attachment`(다운로드)는 내보내기 판정을 거친다.
    - 데이터셋 상세·목록·애드혹 쿼리 실행·차트 데이터 응답에 조회자별 `exportAllowed` 가 실린다. 쿼리 편집기의 내보내기 가능 여부는 애드혹 실행 응답의 `exportAllowed`+`runId` 로 정한다. `POST /api/v1/analytics/queries/export-check` 는 AI 표 위젯이 표시된 SQL 로 미리 보는 용도다(값 판정, 감사 없음).
  - 쿼리 결과 내보내기:
    - 화면의 행이 아니라 실행 기록(`analytics_query_run`, 1시간 보존)의 `runId` 로 서버가 지금 자격으로 다시 판정하고 다시 실행한다. 데이터가 그 사이 바뀌었으면 파일 내용도 바뀐다.
    - 기록이 없거나 남의 기록이거나 1시간이 지났으면 404 `QUERY_RUN_NOT_FOUND` "실행 기록을 찾을 수 없습니다. 쿼리를 다시 실행한 뒤 내보내세요." 다.
    - **저장 쿼리 실행 결과는 서버 내보내기를 할 수 없다** — 저장 쿼리 실행(`POST /api/v1/analytics/queries/{id}/execute`)은 `runId` 를 만들지 않는다. 웹은 버튼을 비활성하고 편집기에서 다시 실행하라고 안내한다.
  - 웹:
    - 내보낼 수 없는 데이터의 주 내보내기 버튼은 비활성+툴팁이다.
    - 보조 다운로드(목록 행 아이콘·선택 행 CSV·오브젝트 다운로드 아이콘·대시보드 PDF·AI 표/데이터셋 위젯 내보내기)는 숨긴다.
  - 등급 전파:
    - 파이프라인의 **지정 출력**이 입력보다 낮으면 예전에는 실패(`SQL_WRITE_DOWNGRADE`)였다. 이제 **자동 상향**하고 적재한다(대화형 SQL 의 쓰기 하향은 여전히 `SQL_WRITE_DOWNGRADE` 로 거부).
    - DML 스텝(`INSERT INTO 낮은등급 SELECT … FROM 높은등급`)도 쓰기 대상을 자동 상향한다.
    - SQL 스텝의 선언 입력도 전파 입력이다. 선언했지만 실행 주체가 볼 수 없는 입력이 있으면 실패한다.
  - **PYTHON 스텝 출력 등급**(공통 결정 R4): 실행 주체 자격 이하이면서 `allowlist_required` 가 아닌 등급 중 **최고 등급**이다(= 흐름 C 의 슬롯 롤로 실제 읽을 수 있는 최대 등급). 예: ADMIN(기밀) 트리거 → 기밀은 허용 목록 등급이라 PYTHON 이 못 읽으므로 출력은 '민감', 허용 목록 시드는 없다.
    - 그런 등급이 없는 실행 주체(역할 없음 등)가 돌리는 **출력 있는 PYTHON 스텝의 새 TEMP 는 실패한다**(fail-closed — 기본 등급 TEMP 를 볼 수 없는 실행 주체가 쓰게 두지 않는다). 실행 주체에게 역할(열람 등급)을 주면 풀린다.
    - **사전 점검(소유자 롤 app):** `SELECT p.id, p.name, s.id step_id, s.output_dataset_id, d.id temp_dataset_id FROM pipeline_step s JOIN pipeline p ON p.id = s.pipeline_id LEFT JOIN dataset d ON d.source_pipeline_step_id = s.id AND d.origin_type = 'TEMP' WHERE s.script_type = 'PYTHON' AND (s.output_dataset_id IS NOT NULL OR d.id IS NOT NULL)` — 지정 출력과 러너 TEMP(`temp_dataset_id`) 를 함께 본다. 행이 있으면 각 출력의 현재 등급을 실행 주체(트리거 생성자·수동 실행자) 자격 기준의 위 규칙 결과(자격 이하·허용 목록 아닌 최고 등급)와 비교해, 그보다 낮은 출력은 다음 실행에서 상향된다고 소유자에게 미리 알린다. 역할 없는 실행 주체의 트리거가 걸린 PYTHON 스텝도 이 목록에서 찾아 역할을 주거나 실행 주체를 바꾼다.
  - 허용 목록:
    - 러너 TEMP 의 허용 목록은 매 실행 "허용 목록 필요 입력들의 항목 교집합 ∪ {실행 주체}" 로 다시 계산된다(예전: 늘어나기만 함, WD-30).
    - 쓰기 **전**에는 좁히기만 한다(기존 ∩ 시드 ∪ {실행 주체}). 쓰기 성공 **뒤** 넓힘까지 포함해 시드로 확정하는 것은 **출력이 이번 실행으로 전부 교체된 경우**(새 TEMP·REPLACE·증분 전체 재구축)뿐이다. APPEND/MERGE 로 재사용하는 TEMP 는 이전 실행 행이 남으므로 좁히기만 한다 — 입력에 늦게 추가된 사람은 출력이 전부 교체되는 실행 전까지 그 TEMP 를 못 본다.
    - 교집합은 항목 단위다 — 한 입력엔 역할로, 다른 입력엔 사용자로 올라 있는 사람은 빠진다(보수적).
    - 지정 출력은 상향된 실행에서만 좁히고 넓히지 않는다(사용자가 관리하는 목록).
    - 자동 상향 시 상향 감사와 허용 목록 변경 감사(바뀐 항목만)가 함께 남는다.
  - 수동 등급 변경은 "자동 상향되었습니다" 배너를 지운다.
- **감사(관리자 › 감사 로그, 액션 필터 '데이터셋 보안' 묶음):**
  - `데이터셋 접근 거부`(DATASET_ACCESS_DENIED, 실패)는 VIEW(404 로 가려진 것 포함)·SQL·파이프라인·데이터셋 참조·내보내기·AI 거부를 **실제 사유**와 함께 남긴다(테이블명 포함 — 관리자 전용 화면). 내보내기 거부는 별도 액션 없이 `metadata.action=EXPORT` 다.
  - AI 거부(위 S3 절의 403 `POLICY_BLOCKED`)는 `metadata.action=AI` 이고 사유는 `AI_EXTERNAL_DENIED`(외부 공급자 불허)·`AI_DENIED`(AI 금지 등급)·`SHARE_DENIED`(공유 목적 차단) 중 하나다. 채팅 MCP 의 상세·행·SQL 도구, 온톨로지 출처·검수 근거·AI_CLASSIFY 저장/실행·메트릭 작업 저장 판정에서 남는다. 목록·검색·스키마에서 AI 불허 데이터셋이 빠지는 것(값 판정)과 메트릭 폴러의 공유 차단은 남기지 않는다.
  - `감사 등급 데이터 접근`(DATASET_ACCESS)은 `audit_access` 등급(기본: 민감·기밀)의 행 조회·SQL·파이프라인·AI 접근을 남긴다. AI 대행 요청(채팅 MCP)의 데이터셋 상세·행·SQL 은 종류 `AI` 로 남는다(같은 사용자의 웹 요청은 `ROW_VIEW`·`SQL`). AI 대행 행 조회는 `AI` 와 `ROW_VIEW` 두 행이 남을 수 있다.
  - 메트릭 SQL 거부는 **작업 생성·수정**(사용자 요청)만 감사한다. 백그라운드 메트릭 폴러의 거부는 감사하지 않는다(30초마다 반복되는 내부 판정이라 감사 폭주).
  - 같은 (사용자, 데이터셋, 동작, 사유)는 **1분에 1건**으로 합친다 — api 인스턴스 메모리 기준이라 다중 인스턴스면 인스턴스 수만큼 남을 수 있다. 등급을 감사 등급으로 **올린 직후 1분 안의 첫 접근은 빠질 수 있다**.
  - 차트·대시보드 위젯의 "열람 권한 없음" 표시와 없는 데이터셋 id 는 거부로 남기지 않는다.
  - 거부·접근 감사는 요청 트랜잭션과 분리해 쓴다. 요청이 트랜잭션 안이면 그 트랜잭션이 **끝난 뒤(커밋·롤백 무관) 전용 단일 스레드가 비동기로** 기록한다 — 요청 스레드는 커넥션을 하나만 쓰므로 풀 교착이 없다(트랜잭션 밖이면 그 자리에서 바로 기록). 대신 **api 가 비정상 종료(크래시·kill -9)되면 큐에 남은 기록은 유실**되고, 큐(1만 건)가 차면 기록을 버리며 api 로그에 `감사 기록을 버렸다 … (누적 N건)` 경고가 남는다. 정상 종료 시에는 최대 10초 동안 남은 기록을 비운다. 감사 행의 시각은 요청 트랜잭션이 끝난 뒤 기록된 시각이다(트랜잭션이 길면 그만큼 늦다). 기록 실패도 요청은 그대로 처리되고 경고만 남는다.
- **이벤트(내부 계약):**
  - `DatasetSecurityLevelChangedEvent`(MANUAL·AUTO_RAISE·PIPELINE_TEMP_ASSIGN·CLONE_INHERIT)
  - `SecurityLevelsChangedEvent`(CREATED·UPDATED·DELETED·REORDERED)
  - 흐름 C 의 PYTHON 슬롯 롤 GRANT 동기화가 구독한다.
- **배포 후 확인(소유자 롤):**
  - `select max(version::int) from flyway_schema_history` = 세 흐름 중 가장 큰 번호(흐름 C 의 V137 — C 가 빠진 배포라면 136).
  - `SELECT count(*) FROM analytics_query_run` 이 쿼리 편집기 실행 뒤 늘어나는지 본다.
  - `SELECT action_type, count(*) FROM audit_log WHERE action_type IN ('DATASET_ACCESS_DENIED','DATASET_ACCESS') AND action_time > now() - interval '1 hour' GROUP BY 1` 로 기록을 확인한다.
- **롤백:**
  - V136 은 새 테이블만 만든다. 이미지만 이전 버전(api+web 함께, 일괄 배포였으면 ai-agent·executor 도 함께)으로 되돌리면 된다(구 코드는 테이블을 모른다).
  - 되돌리면 내보내기 정책·전파·접근 감사가 사라진다. 이미 자동 상향된 등급·재계산된 허용 목록은 그대로 남는다(되돌리지 않는다).
- **알려진 한계:**
  - **UI 수준 차단**이다 — 화면 데이터의 복사·캡처는 막지 못한다(스펙 §7.4).
  - **inline presign URL 은 내보내기 판정을 거치지 않는다** — 파일형 데이터셋 상세의 오브젝트 **이름 클릭**(inline 열기)과 **ai-agent 경유**로 받은 inline URL 은 VIEW 만 보므로, 열린 파일을 브라우저에서 저장할 수 있다.
  - AI 표 위젯의 내보내기 판정은 위젯에 표시된 SQL 기준이다. LLM 이 다른 SQL 의 결과를 표에 넣었으면 판정이 어긋날 수 있다(UI 수준).
  - 대시보드 PDF 는 브라우저 인쇄라 숨김만 한다(Cmd+P 는 막지 못한다).
  - 내보내기 추정(`GET /api/v1/datasets/{id}/export/estimate`)은 VIEW 만 본다(행 수는 이미 보이는 정보).
  - **쿼리 실행 기록의 만료 행은 같은 사용자가 다시 애드혹 실행할 때만 지워진다.** 다시 실행하지 않는 사용자의 SQL 원문은 테이블에 남는다(내보내기·조회는 만료 조건으로 막히고, 소유자 조회·RLS 로 제한). 후속: 전역 정리 스케줄러.
  - PYTHON 출력 등급은 흐름 C 의 슬롯 롤 읽기 제한과 함께여야 실제 읽기와 일치한다(위 배포 모듈).
  - 지정 출력이 이미 입력과 같은(허용 목록 필요) 등급이면 상향이 없으므로 허용 목록을 좁히지 않는다 — 입력 목록에는 없고 출력 목록에만 있는 구성원이 출력을 볼 수 있다(대화형 SQL 의 rank 판정과 같은 성격).

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
     - **ai-agent 는 ambient 자격증명을 쓰지 않는다 (#708).** 채팅(sdk/cli/cli-api)·프로액티브·분류·GraphRAG completion·검증 라우트 모두 `claude-child-env.ts` 의 공용 헬퍼로 자식 env 를 만든다 — `ANTHROPIC_*`(API 키·`ANTHROPIC_AUTH_TOKEN`·`ANTHROPIC_BASE_URL` 등), `CLAUDE_CODE_OAUTH_*`, `CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY`, `AWS_*` 를 걷어내고 **요청 자격증명 하나만** 싣는다. 요청에 자격증명이 없으면 자식을 띄우기 전에 한국어 오류로 멈춘다(키체인·`~/.claude` 로그인은 env 로 가릴 수 없어 spawn 전 차단이 유일한 방어선). 그래서 컨테이너에 키를 넣어도 쓰이지 않지만, 혼란을 피하려고 여전히 두지 않는다.
     - ⚠ **로컬 dev 챗도 키체인 폴백이 없다.** 예전엔 `ai.agent_type=cli` + 토큰 미설정이면 호스트 `claude` 로그인(macOS 키체인)으로 동작했지만 이제 "AI 자격증명이 설정되지 않았습니다" 오류가 난다. 개발 테넌트의 설정 › AI 에이전트에 실제 OAuth 토큰(`claude setup-token`) 또는 API 키를 저장해야 한다.
     - **재시도 상한 (#711)**: 채팅·분류 자식은 `CLAUDE_CODE_MAX_RETRIES=2`, 검증 라우트는 `0` 으로 고정된다(컨테이너 값은 무시). Claude Code 기본값 10 은 401 까지 재시도해 잘못된 키로 약 3분 대기한 뒤에야 실패했다. 인증 실패는 이제 채팅에 "API 키 또는 OAuth 토큰을 다시 등록" 안내 `error` 로 한 번만 온다.
     - 개발 스크립트(`graphrag/dump-extraction.ts`, `graphrag/eval/run-eval.ts`)만 진입점에서 `CLAUDE_CODE_OAUTH_TOKEN`/`ANTHROPIC_API_KEY` 를 읽어 명시적으로 넘긴다(서버 경로 아님).
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
