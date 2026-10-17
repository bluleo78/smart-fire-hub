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
**항상 `LIVE_STACK_NAME=work-loop` 를 붙여 실행한다.**

```bash
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh up        # 기동 (+ 테스트 계정 시드)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh status    # 서비스별 헬스(비정상이면 종료코드 1)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh down      # 정리(컨테이너·볼륨까지 삭제)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh seed      # 테스트 계정 재확인(멱등)
LIVE_STACK_NAME=work-loop scripts/work-loop-stack.sh logs      # 로그 파일 목록
```
- 마지막 인자로 워크트리 경로를 줄 수 있다(기본 = 스크립트가 속한 저장소 루트). 이슈 워크트리에서 그 워크트리의 스크립트를 실행하면 된다.
- 옵션: `--with-admin`(admin 콘솔도 기동), `--with-neo4j`(GraphRAG Neo4j 기동), `--skip-build`(직전 빌드 재사용 — 코드를 바꿨으면 쓰지 말 것), `--keep-data`(down 시 볼륨 유지).
- `up` 은 매번 `./gradlew clean bootJar` 로 깨끗이 빌드한 jar(사본)를 실행하고, ai-agent 도 `pnpm build` 후 `node dist/index.js` 로 띄운다(watch/bootRun 의 stale 코드 위험 제거).
- DB 는 빈 격리 볼륨에서 시작해 api 기동 시 Flyway 가 전체 마이그레이션(현재 V137까지)을 적용한다. 개발/운영 데이터는 복사하지 않는다.
- 이미 떠 있거나 포트가 쓰이고 있으면 **아무것도 죽이지 않고 실패**한다. 기동 중 실패하면 이 스택이 띄운 것만 정리한다.
- 상태·로그: `<메인 저장소>/.work-loop/state/stack-work-loop/` (`stack.env`=pgid·포트, `logs/*.log`). 소요 시간: 첫 up 1~3분(이미지 빌드·Gradle), 이후 30초 안팎.

### 서비스 URL·포트 (모두 127.0.0.1 바인드, `WL_*_PORT` 환경변수로 변경 가능)

| 서비스 | URL | 헬스 체크 |
|---|---|---|
| web | http://localhost:6173 | `GET /` 200 (`/api` → 6010 프록시) |
| api | http://localhost:6010/api/v1 | `GET /api/v1/auth/signup-status` 200 |
| ai-agent | http://localhost:6020/agent | `GET /agent/health` 200 |
| admin (`--with-admin`) | http://localhost:6050 | `GET /` 200 (`/api` → 6010 프록시) |
| PostgreSQL | 127.0.0.1:6432, db `smartfirehub`, 소유자 `app/app`(런타임 롤 `app_tenant/app_tenant`) | `docker exec work-loop-db-1 psql -U app -d smartfirehub` |
| MinIO | S3 127.0.0.1:6900, 콘솔 6901 (`minioadmin/minioadmin`, 버킷 `firehub-files`) | `/minio/health/live` |
| Neo4j (`--with-neo4j`) | http 6474, bolt 6687 (`neo4j/firehub-graph-dev`) | |

격리 방식: docker compose project `work-loop`(파일 `scripts/work-loop/compose.yml`, 명명 볼륨), api 는 명령행 인자로
DB·Flyway·파이프라인 URL, JobRunr 대시보드 비활성, agent URL·내부 토큰, CORS, MinIO 엔드포인트를 전부 격리값으로 덮어쓴다.
JWT 서명키는 up 마다 새로 만들어 격리 스택 토큰이 개발 api(5010)에서 통하지 않는다. web/admin 은 `scripts/work-loop/vite.*.config.mts`
래퍼로 `/api` 프록시 대상만 격리 api 로 바꾼다(앱의 vite.config.ts 는 무변경).

### 테스트 로그인 계정

- **`workloop@example.com` / `WorkLoop1234`** (이름 `Work Loop`)
- 빈 DB 의 첫 가입자라 기본 테넌트(`Default Workspace`)의 ADMIN 이며, 시드가 플랫폼 `SUPER_ADMIN` 도 부여해 admin 콘솔 로그인도 된다.
- 로그인 실패를 반복하면 로그인 잠금 카운터가 올라간다 — 틀린 비밀번호로 루프 돌리지 말 것.
- API 직접 호출 예:
  ```bash
  T=$(curl -s -H 'Content-Type: application/json' -d '{"username":"workloop@example.com","password":"WorkLoop1234"}' \
      http://localhost:6010/api/v1/auth/login | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
  curl -H "Authorization: Bearer $T" http://localhost:6010/api/v1/auth/me
  ```
- AI 챗: ai-agent 는 자격 없이도 기동하지만 챗 응답에는 테넌트 AI 설정(설정 › AI 에이전트)에 실제 토큰/키 저장이 필요하다.
  자격이 필요한 검증은 사람 확인 대상으로 남긴다.

### 브라우저 검증

`playwright-cli` 를 **이름 있는 세션**으로 쓴다(다른 세션 브라우저를 건드리지 않도록 `kill-all` 금지, 끝나면 그 세션만 `close`).
```bash
playwright-cli -s=wl open http://localhost:6173/login
playwright-cli -s=wl snapshot                      # ref 확인
playwright-cli -s=wl fill <이메일 ref> workloop@example.com
playwright-cli -s=wl fill <비밀번호 ref> WorkLoop1234
playwright-cli -s=wl click <로그인 ref>
playwright-cli -s=wl screenshot
playwright-cli -s=wl close
```
스크린샷은 `test-results/exploratory/<기능>/<timestamp>/screenshots/` 에 둔다.

## 5. 건드리면 안 되는 포트·프로세스·DB

다른 세션(사람의 dev 스택, 로컬 운영 배포)이 쓴다. 죽이거나, 재시작하거나, 데이터를 바꾸지 않는다.

- **dev 포트**: 5010(api), 5040(JobRunr 대시보드), 5020(ai-agent), 5030(channel), 5050(admin), 5173(web), 5051(admin E2E)
- **dev 컨테이너/DB**: `smart-fire-hub-db-1`(5432, `smartfirehub`), `db-test`(5433), MinIO 9000/9001, Neo4j 7474/7687 — 루트 `docker-compose.yml` 의 `smart-fire-hub` project 전체
- **로컬 운영 배포**: `smart-fire-hub-prod-*` 컨테이너 전부(web 8888, admin 8889, prod DB·Neo4j·MinIO)
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
