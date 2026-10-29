# smart-fire-hub — work-loop 프로젝트 설정

genia-works work-loop 이 이 저장소의 이슈를 무인 처리할 때 참고하는 저장소별 설정이다.
경로는 모두 워크트리 루트 기준이다.

## 1. 개요

pnpm(10.11) + turbo 모노레포. 앱 목록:

| 앱 | 스택 | 기본 dev 포트 | 비고 |
|---|---|---|---|
| `apps/firehub-api` | Spring Boot + jOOQ + Flyway (Java 25 런타임, Gradle) | 5010 (JobRunr 대시보드 5040) | PostgreSQL(PostGIS+pgvector) 필요. 멀티 테넌시(RLS) |
| `apps/firehub-web` | Vite + React 19 + TanStack Query | 5173 | `/api` 를 api 로 프록시 |
| `apps/firehub-ai-agent` | Node(Express) + Claude Agent SDK + MCP | 5020 | AI 자격증명은 테넌트 설정에서 요청마다 전달된다 |
| `apps/firehub-admin` | Vite + React (플랫폼 운영자 콘솔) | 5050 | `/api/platform` 사용 |
| `apps/firehub-channel` | Node(Express) 알림 채널(메일·Slack 등) | 5030 | 라이브 스택에는 포함하지 않는다 |
| `apps/firehub-executor` | Python(파이프라인 실행기) | 8000 | local 프로필은 `app.executor.enabled=false`(직접 실행)라 라이브 스택에 불필요 |

앱별 상세 규칙: `apps/firehub-api/CLAUDE.md`, `apps/firehub-web/CLAUDE.md`, `apps/firehub-ai-agent/CLAUDE.md`.
루트 `CLAUDE.md` 규칙(한국어 주석 필수, backend/ai-agent 는 TC, frontend 는 Playwright E2E 필수)을 따른다.

## 2. 의존성 설치 (워크트리 생성 직후)

```bash
pnpm install                      # 루트에서 1회 — 모든 Node 앱 의존성
# api 는 Gradle wrapper 가 첫 빌드 때 의존성을 받는다(별도 설치 불필요)
# executor 테스트를 돌릴 때만: (cd apps/firehub-executor && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt)
```

- jOOQ 생성 소스(`apps/firehub-api/src/main/generated/`)는 **저장소에 추적**되므로 빌드 전에 `generateJooq` 가 필요 없다.
  Flyway 마이그레이션을 추가·변경한 경우에만 `cd apps/firehub-api && ./gradlew generateJooq` 를 실행하고 생성 소스 변경을 함께 커밋한다
  (Testcontainers 로 임시 DB 를 띄워 마이그레이션 후 생성 — Docker 데몬 필요, 개발 DB 불필요).
- `.env.local` 은 gitignore 대상이라 새 워크트리에는 없다. 라이브 스택 스크립트는 필요한 값을 전부 env 로 주입하므로 복사하지 않는다.

## 3. 바뀐 범위별 테스트 명령

### firehub-api (`apps/firehub-api`)
통합 테스트는 JVM 당 Testcontainers PostgreSQL 하나를 쓴다(Docker 데몬 필요, `pnpm db:up` 불필요).
```bash
cd apps/firehub-api
./gradlew test --tests "com.smartfirehub.auth.service.AuthServiceTest"      # 단일 클래스
./gradlew test --tests "*.AuthServiceTest.login_success"                    # 단일 메서드
./gradlew test --tests "com.smartfirehub.pipeline.*"                        # 패키지 단위
./gradlew test                                                              # 전체(오래 걸림 — 범위 지정 우선)
./gradlew compileJava compileTestJava                                       # 빠른 컴파일 확인
```
- 같은 테스트를 다시 돌려 "증거"로 삼을 때는 `cleanTest` 를 붙인다(`./gradlew cleanTest test --tests ...`). 안 붙이면 UP-TO-DATE 로 실제 실행이 안 될 수 있다.

### firehub-web (`apps/firehub-web`)
```bash
cd apps/firehub-web
pnpm typecheck                                   # tsc -b --noEmit + e2e tsconfig
pnpm lint                                        # ESLint (바뀐 파일만: npx eslint <파일>)
pnpm test:unit                                   # Vitest (+coverage)
pnpm exec vitest run src/lib/foo.test.ts         # 단일 단위 테스트
PW_PORT=6174 pnpm exec playwright test e2e/pages/<도메인>/<파일>.spec.ts   # E2E 범위 지정
PW_PORT=6174 pnpm exec playwright test --grep "@smoke"                     # 전역 smoke
```
- E2E 는 `page.route()` 모킹 기반이라 백엔드가 필요 없다. Vite dev 서버를 Playwright 가 직접 띄운다.
- **반드시 `PW_PORT` 를 격리 포트(예: 6174)로 지정**한다. 기본 5173 은 `reuseExistingServer` 라서 다른 세션의 dev 서버(다른 코드)를 그대로 재사용해 엉뚱한 코드를 테스트한다.

### firehub-ai-agent (`apps/firehub-ai-agent`)
```bash
cd apps/firehub-ai-agent
pnpm typecheck
pnpm lint
pnpm test                                        # Vitest 전체
pnpm test -- src/mcp/api-client.test.ts          # 단일 파일
pnpm test -- -t "should list categories"         # 단일 케이스
```

### firehub-admin (`apps/firehub-admin`)
```bash
cd apps/firehub-admin
pnpm typecheck && pnpm lint
pnpm test:unit                                   # Vitest
PW_PORT=6151 pnpm exec playwright test           # E2E (기본 5051 — 다른 세션과 겹치지 않게 격리 포트 지정)
```

### 기타
- firehub-channel: `cd apps/firehub-channel && pnpm test`
- firehub-executor: `cd apps/firehub-executor && pnpm test` (pytest)
- 커밋 시 pre-commit 훅(`scripts/husky/pre-commit.sh`)이 lint-staged + 전체 typecheck + 변경 영역별 E2E + `gradlew test` 를 돌린다.
  web 공유 영역(components/api/lib/…, vite/playwright 설정 등)을 바꾸면 전체 E2E 가 돈다.

## 4. 격리 라이브 스택

다른 세션의 dev 스택(5xxx 포트, 개발 DB)과 완전히 분리된 스택을 띄워 실제 브라우저·HTTP 로 검증한다.
**항상 `LIVE_STACK_NAME=work-loop` 를 붙여 실행한다.** 이 컴퓨터의 다른 AI 도 각자의 클론에서 같은 명령을 쓰지만,
스크립트가 클론마다 다른 이름·포트를 골라 서로 섞이지 않는다.

```bash
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh up        # 기동 (+ 테스트 계정 시드). 마지막에 STACK_ID·URL 출력
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh status    # 내 스택 헬스(비정상이면 종료코드 1) + 공유 바탕 헬스·다른 저장소 DB/버킷
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh down      # 정리(내 프로세스·내 DB·내 버킷까지 삭제. 공유 바탕은 그대로)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh seed      # 테스트 계정 재확인(멱등)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh logs      # 로그 파일 목록
```
- 마지막 인자로 워크트리 경로를 줄 수 있다(기본 = 스크립트가 속한 저장소 루트). 이슈 워크트리에서 그 워크트리의 스크립트를 실행하면 된다.
- 옵션: `--with-admin`(admin 콘솔도 기동), `--with-neo4j`(GraphRAG Neo4j 기동), `--own-db`(공유 Postgres 대신 전용 컨테이너 — 아래),
  `--skip-build`(직전 빌드 재사용 — 코드를 바꿨으면 쓰지 말 것), `--keep-data`(down 시 내 DB·버킷·볼륨 유지).
- `up` 은 매번 `./gradlew clean bootJar` 로 깨끗이 빌드한 jar(사본)를 실행하고, ai-agent 도 `pnpm build` 후 `node dist/index.js` 로 띄운다(watch/bootRun 의 stale 코드 위험 제거).
- DB 는 빈 DB 에서 시작해 api 기동 시 Flyway 가 전체 마이그레이션(현재 V137까지)을 적용한다. 개발/운영 데이터는 복사하지 않는다.
- 이미 떠 있거나 직접 준 포트(`WL_*_PORT`)·공유 바탕 포트가 남에게 쓰이고 있으면 **아무것도 죽이지 않고 실패**한다. 기동 중 실패하면 이 스택이 띄운 것만 정리한다.

### 상태·로그·이름 규칙

- 상태·로그: `<메인 저장소>/.work-loop/state/stack-work-loop/` (`stack.env`=STACK_ID·DB 이름·버킷·포트·pgid, `logs/*.log`).
  소요 시간: 첫 up 1~3분(이미지 빌드·Gradle), 이후 30초 안팎.
- **STACK_ID** = `work-loop-<메인 저장소 절대경로 sha1 앞 8자>` — 클론마다 다르다. up·status 마지막 줄에 나온다.
- **공유 바탕**: PostgreSQL 과 MinIO 는 메모리를 아끼려고 컴퓨터에 하나만 띄워 모든 클론이 함께 쓴다
  (compose project `work-loop-shared`, 파일 `scripts/work-loop/compose.shared.yml`). 없으면 첫 up 이 띄우고, 이후 up 은 재사용한다.
  어떤 저장소의 down 도 공유 바탕을 내리지 않는다.
- **내 DB** = `smartfirehub_<STACK_ID sha1 앞 8자>`, **내 버킷** = `firehub-<같은 8자>` — up 이 만들고(`CREATE DATABASE`·`mc mb`), down 이 지운다.
  `--keep-data` 로 down 하면 다음 up 이 그 DB·버킷을 재사용한다. 그 밖에 남아 있는 것은 up 이 지우고 새로 만든다.
  보관한 데이터를 버리려면 상태가 없는 채로 보통 `down` 을 한 번 더 한다. 롤은 클러스터 전역이라 지우지 않는다.
- down 이 「공유 바탕이 healthy 가 아니라 … 지우지 못했다」로 실패하면 `stack.env` 가 남는다(프로세스는 이미 정리) — 공유 바탕이 돌아온 뒤 down 을 다시 한다.
- **`--own-db`**: 이 저장소만 전용 PostgreSQL 컨테이너(compose project = STACK_ID, 포트는 6433 부터 고름)를 쓴다. 공유 서버가 고장 났을 때,
  또는 클러스터 전역 상태(롤 정의·비밀번호, `ALTER SYSTEM`)를 바꾸는 검증일 때만 쓴다. MinIO 는 그래도 공유 바탕을 쓴다.
  이미지는 공유 서버와 같은 `work-loop-firehub-postgres:local` 을 재사용하므로 `docker/postgres/Dockerfile` 변경 검증에는 맞지 않는다.
- `공용 락 대기 중`·`api 기동 락 대기 중` 로그는 다른 클론이 기동 중이라는 뜻이다 — 기다리면 된다. 락·소유 기록·롤 고정의 자세한 규칙은
  `scripts/work-loop-stack.sh` 머리 주석에 있다.
- 공유 바탕을 내리는 것은 사람만 한다: `scripts/work-loop-stack.sh shared-down` — 다른 저장소 DB·버킷이 남아 있으면 목록을 보이고 거부하며,
  공유 db·minio 가 healthy 가 아니어서 목록을 읽을 수 없을 때도 거부한다. `--force` 일 때만 내린다(볼륨까지 삭제, `--keep-data` 면 유지).
- 옛 방식(compose project `work-loop`, `work-loop-db-1` 등)이 남아 있으면 up 은 경고만 하고 지우지 않는다. 그것이 공유 포트(6432·6900·6901)를
  잡고 있으면 up 이 실패한다 — 그 스택을 쓰는 세션이 끝났는지 확인한 뒤 `docker compose -p work-loop down -v` 로 정리한다.

### 서비스 URL·포트 (모두 127.0.0.1 바인드)

내 서비스 포트는 **up 이 고른다** — 아래 기본값부터 +1 씩 올려 빈 포트를 쓴다. 실제 값은 `status` 출력 또는 `stack.env` 로 확인한다.
`WL_API_PORT` 등을 직접 주면 그 값을 그대로 쓴다(쓰는 중이면 실패).

| 서비스 | 포트 | 헬스 체크 |
|---|---|---|
| web | `$WL_WEB_PORT` (6173 부터) | `GET /` 200 (`/api` → 내 api 프록시) |
| api | `$WL_API_PORT` (6010 부터), `/api/v1` | `GET /api/v1/auth/signup-status` 200 |
| ai-agent | `$WL_AGENT_PORT` (6020 부터), `/agent` | `GET /agent/health` 200 |
| admin (`--with-admin`) | `$WL_ADMIN_PORT` (6050 부터) | `GET /` 200 |
| Neo4j (`--with-neo4j`) | `$WL_NEO4J_HTTP_PORT`·`$WL_NEO4J_BOLT_PORT` (6474·6687 부터, `neo4j/firehub-graph-dev`) | |
| PostgreSQL (공유) | **6432 고정**, db `$WL_DB_NAME`, 소유자 `app/app`(런타임 롤 `app_tenant/app_tenant`) | `docker exec work-loop-shared-db-1 psql -U app -d $WL_DB_NAME` |
| MinIO (공유) | **S3 6900 · 콘솔 6901 고정** (`minioadmin/minioadmin`, 버킷 `$WL_BUCKET`) | `/minio/health/live` |

포트·이름 읽기(다른 명령 전에 한 번):
```bash
# 메인 저장소의 stack.env — 어느 워크트리에서든 같은 파일을 가리킨다
. "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/.work-loop/state/stack-work-loop/stack.env"
echo "$STACK_ID web=$WL_WEB_PORT api=$WL_API_PORT db=$WL_DB_NAME bucket=$WL_BUCKET"
docker exec -it work-loop-shared-db-1 psql -U app -d "$WL_DB_NAME"                    # 내 DB 만 본다(--own-db 면 ${STACK_ID}-db-1)
```

격리 방식: api 는 명령행 인자로 DB·Flyway·파이프라인 URL(내 DB), 롤 비밀번호 설정, JobRunr 대시보드 비활성, agent URL·내부 토큰(STACK_ID 별),
CORS(고른 web·admin 포트), MinIO 엔드포인트·버킷을 전부 격리값으로 덮어쓴다. JWT 서명키는 up 마다 새로 만들어 격리 스택 토큰이
개발 api(5010)·다른 클론 스택에서 통하지 않는다. web/admin 은 `scripts/work-loop/vite.*.config.mts` 래퍼로 `/api` 프록시 대상만 내 api 로 바꾼다
(앱의 vite.config.ts 는 무변경). ai-agent·web·admin 은 DB·버킷 이름을 직접 쓰지 않는다(api 경유, 파일은 api 가 준 presigned URL).

### 테스트 로그인 계정

- **`workloop@example.com` / `WorkLoop1234`** (이름 `Work Loop`)
- 빈 DB 의 첫 가입자라 기본 테넌트(`Default Workspace`)의 ADMIN 이며, 시드가 플랫폼 `SUPER_ADMIN` 도 부여해 admin 콘솔 로그인도 된다.
- 로그인 실패를 반복하면 로그인 잠금 카운터가 올라간다 — 틀린 비밀번호로 루프 돌리지 말 것.
- API 직접 호출 예(먼저 위처럼 `stack.env` 를 읽는다):
  ```bash
  T=$(curl -s -H 'Content-Type: application/json' -d '{"username":"workloop@example.com","password":"WorkLoop1234"}' \
      "http://localhost:$WL_API_PORT/api/v1/auth/login" | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
  curl -H "Authorization: Bearer $T" "http://localhost:$WL_API_PORT/api/v1/auth/me"
  ```
- AI 챗: ai-agent 는 자격 없이도 기동하지만 챗 응답에는 테넌트 AI 설정(설정 › AI 에이전트)에 실제 토큰/키 저장이 필요하다.
  자격이 필요한 검증은 사람 확인 대상으로 남긴다.

### 브라우저 검증

`playwright-cli` 를 **내 STACK_ID 로 이름 붙인 세션**(`-s=wl-$STACK_ID`)으로 쓴다 — 다른 클론의 세션 브라우저를 건드리지 않도록 `kill-all` 금지,
끝나면 그 세션만 `close`.
```bash
playwright-cli -s=wl-$STACK_ID open "http://localhost:$WL_WEB_PORT/login"
playwright-cli -s=wl-$STACK_ID snapshot                      # ref 확인
playwright-cli -s=wl-$STACK_ID fill <이메일 ref> workloop@example.com
playwright-cli -s=wl-$STACK_ID fill <비밀번호 ref> WorkLoop1234
playwright-cli -s=wl-$STACK_ID click <로그인 ref>
playwright-cli -s=wl-$STACK_ID screenshot
playwright-cli -s=wl-$STACK_ID close
```
스크린샷은 `test-results/exploratory/<기능>/<timestamp>/screenshots/` 에 둔다.

## 5. 건드리면 안 되는 포트·프로세스·DB

다른 세션(사람의 dev 스택, 로컬 운영 배포, 다른 클론의 라이브 스택)이 쓴다. 죽이거나, 재시작하거나, 데이터를 바꾸지 않는다.

- **dev 포트**: 5010(api), 5040(JobRunr 대시보드), 5020(ai-agent), 5030(channel), 5050(admin), 5173(web), 5051(admin E2E)
- **dev 컨테이너/DB**: `smart-fire-hub-db-1`(5432, `smartfirehub`), `db-test`(5433), MinIO 9000/9001, Neo4j 7474/7687 — 루트 `docker-compose.yml` 의 `smart-fire-hub` project 전체
- **로컬 운영 배포**: `smart-fire-hub-prod-*` 컨테이너 전부(web 8888, admin 8889, prod DB·Neo4j·MinIO)
- **공유 바탕의 다른 저장소 것**: `work-loop-shared` 의 내 것이 아닌 DB(`smartfirehub_*`)·버킷(`firehub-*`) — `status` 의 「다른 저장소」 목록. 공유 바탕 자체를 재시작·down 하지 않는다(`shared-down` 은 사람만).
- **다른 클론의 라이브 스택**: 내 STACK_ID 가 아닌 `work-loop-*` compose project(전용 DB·Neo4j)와 그 포트, 옛 방식 project `work-loop`
- **기타**: 6768(Orca), `buildx_buildkit_*`
- 금지 명령: `pnpm dev` / `pnpm dev:full`(5xxx 포트를 `lsof | kill -9` 로 죽인다), `pnpm db:up|db:down|db:reset`, `docker compose down`(project 미지정),
  `playwright-cli kill-all`, 메인 체크아웃(`/Users/bluleo78/git/smart-fire-hub`)에서 브랜치 변경·파일 수정
- 개발 DB(5432)에 직접 마이그레이션을 적용하지 않는다 — dev DB 가 다른 브랜치의 마이그레이션보다 앞서면 dev api 가 기동하지 못한다.

## 6. 알려진 함정

- **jOOQ**: 생성 소스가 추적되므로 평소엔 재생성 불필요. 마이그레이션을 바꾼 브랜치만 `./gradlew generateJooq`(Testcontainers) 후 생성 소스 커밋.
  옛 문서의 `generateJooqSchemaSource` 는 없는 태스크다. 장기 브랜치는 병합 직전 main 의 마이그레이션 번호를 다시 확인(번호 충돌 시 체크섬 불일치로 api 기동 불가).
- **bootRun stale 바이트코드**: `pnpm dev`/`bootRun` 의 api 는 예전 클래스로 뜰 수 있다. 라이브 검증은 항상 이 스크립트(clean bootJar)로 한다.
- **JobRunr**: 임시 api 인스턴스는 SCHEDULE 트리거를 발화시킨다. 격리 스택은 별도 DB 라 개발 데이터에 영향이 없고, 대시보드(5040)는 꺼 둔다.
- **E2E 플레이크**: 전체 E2E 실패가 무관해 보이면 해당 spec 을 `--workers=1` 로 다시 돌려 판별한다(병렬 부하 플레이크가 실제 결함을 가린다).
  `ai-job-progress.spec.ts` 의 SSE 테스트는 기존부터 불안정하다.
- **Playwright 서버 재사용**: `PW_PORT` 미지정 시 다른 세션의 5173 서버를 재사용한다(§3).
- **포맷터 전체 실행 금지**: `./gradlew spotlessApply`, `prettier --write`(ai-agent `pnpm format` 포함)를 저장소 전체에 돌리지 않는다 — 무관한 파일이 대량 변경된다. 바뀐 파일만 다룬다.
- **Gradle 자원 누수**: 단언 실패가 0인데 전체 스위트 실패 목록이 매번 바뀌면 워커 JVM 커넥션 고갈이다 — `./gradlew --stop` 후 재실행.
- **`.superpowers/` 는 추적, `docs/superpowers/` 는 ignore** — 커밋은 경로를 지정해 스테이징한다(`git add -A` 금지).
- **pre-commit 훅**이 무관한 플레이크로 막히면 원인(실패 spec·로그)을 확인해 보고한다. 무관함이 명백할 때만 근거와 함께 `--no-verify`.

## 7. 대기열 라벨

다른 AI 에게 일을 넘길 때 아래 역할이면 특정 AI 를 담당으로 넣지 않고 그 라벨을 붙인다(`SKILL.md` 「이슈 만들기·담당 바꾸기」). 그 라벨로 도는 AI(`--queue "<라벨>"`) 중 할 일이 없는 AI 가 하나 가져간다. 표에 없는 역할은 설명을 보고 AI 를 고른다.

| 역할 | 라벨 |
|---|---|
| 개발 일(구현·결함 수정) | `AI 개발팀` |

## 8. PR — 기차(개발자 통합 브랜치) 흐름 (`process-issue.md` 9번·「PR 리뷰·머지」, WD-460)

- **기차: 켜짐** — 이 저장소의 기본 PR 경로는 기차다(흐름은 work-loop 스킬, WP-1216). 아래는 WD 고유 값이다.
- 원격은 **origin**(`https://github.com/bluleo78/smart-fire-hub.git`), PR 대상 브랜치는 `main`, 도구는 `gh`. gitlab 등 다른 원격에는 push 하지 않는다 — 사람이 origin main 을 받아 맞춘다.
- **feature 브랜치**: `<커밋 타입>/<소문자 이슈 키>`(예: `fix/wd-272`) — main 에서 따서 개발하고 3절의 바뀐 범위 테스트·4절 라이브 검증까지 끝낸다. **push 하지 않는다.** 커밋 훅(`.husky/pre-commit`)은 그대로 돈다.
- **통합 브랜치(기차)**: `integ/<AI 이름>-<번호>`(예: `integ/developer1-12`) — 끝난 feature 를 이슈당 커밋 하나로 스쿼시해 쌓는다. 커밋 제목은 컨벤션(`fix(web): … (WD-354)`)대로 끝에 `(<이슈 키>)`.
- **마이그레이션 번호**: 경로 `apps/firehub-api/src/main/resources/db/migration/`. 쌓을 때 `V<n>__` 가 기차 안 다른 이슈·origin/main 의 마지막과 겹치면 쌓기를 멈추고 번호를 올린 뒤 `cd apps/firehub-api && ./gradlew generateJooq` 결과(`src/main/generated/`)를 같은 이슈 커밋에 함께 넣는다(6절 jOOQ).
- **출발**: 내 앞 통합 PR 이 main 에 머지됐거나 처음일 때만 — 최신 origin/main 으로 리베이스 → push(pre-push 게이트) → PR.
  - pre-push(`.husky/pre-push`)는 늘 전체 게이트(E2E 전체 + gradle 풀 회귀)를 돈다. feature 브랜치를 push 하지 않으므로 게이트는 **기차마다 한 번**만 돈다. pre-push 자체는 바꾸지 않는다.
  - PR 제목 `통합 <키1>·<키2>·…`(예: `통합 WD-353·WD-354`), 본문 첫 줄 `개발: <개발한 AI username>`, 이어서 이슈마다 요약·바뀐 범위 테스트·라이브 검증 근거, 마지막에 pre-push 게이트 결과.
- **게이트 실패·반려**: 같은 통합 브랜치에 수정 커밋을 더한다(제목 끝에 고친 이슈 키). 빼고 다시 만들지 않는다.
- **리베이스**: 출발 전 기차는 main 이 움직일 때마다, 출발한 PR 은 main 과 **충돌할 때만**(리베이스하면 게이트를 다시 돌아야 한다).
- 이 저장소에는 GitHub Actions CI 가 없다 — 검토자는 PR 본문의 pre-push 게이트 결과·라이브 검증을 보고 머지한다.

## 9. 검토자

개발한 통합 PR 을 리뷰·머지하는 AI 팀장(`SKILL.md` 「검토자」). 비어 있으면 PR 을 사람이 리뷰·머지한다.

- username: `teamleader@iacloud.kr`(AI 팀장)
- 통합 PR 을 **이슈 단위로** 리뷰한다(이슈 커밋 하나씩). 본문의 pre-push 게이트 통과, 마이그레이션 번호(origin/main 마지막보다 큰지·기차 안에서 겹치지 않는지, `src/main/generated/` 동반), main 과 충돌 여부를 확인한다.
- 하나라도 문제면 **PR 전체를 반려**하고 어느 이슈가 왜 문제인지 적는다 — 개발자가 같은 PR 에 수정 커밋을 더한다. 검토자는 push 하지 않는다.
- 통과하면 **`--admin` 없이** `gh pr merge --merge`(지금 main 이력 모양 — 머지 커밋 제목 `merge: 통합 <키들>`). 실린 이슈마다 「<커밋> 머지 — 검토 대기」(REVIEW)로 올린다.
