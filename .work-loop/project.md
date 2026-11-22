# smart-fire-hub — work-loop 프로젝트 설정

genia-works work-loop 이 이 저장소의 이슈를 무인 처리할 때 참고하는 저장소별 설정이다(`process-issue.md` 2·6·8·9번과 「PR 리뷰·머지」·「풀테스트·green (검토자)」, `SKILL.md` 「이슈 만들기·담당 바꾸기」·「검토자」).
경로는 모두 워크트리 루트 기준이다. 흐름은 smart-workplace `.work-loop/project.md` 를 따라간다 — 그쪽이 바뀌면 이 파일도 맞춘다(마지막 맞춤: WP-1959·WP-1999·WP-2003, 2026-11-22).

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
# 병렬 포크: -PtestForks=N 또는 환경변수 FIREHUB_TEST_FORKS=N(기본 2, 1 이상 정수 — 아니면 설정 단계 실패). 포크마다 힙 1.5GB+컨테이너가 따로 떠서 메모리가 작은 환경(맥 16GB 등)은 FIREHUB_TEST_FORKS=1. 실측은 「오래 걸리는 작업」 표(WD-764)
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

### 바뀐 범위 테스트를 고르는 법 (WP-2003)

개발자는 위 앱별 명령 중 **바뀐 파일(`git diff --name-only $(git merge-base origin/main HEAD) HEAD`)에 해당하는 것만** 돌리고 PR 본문에 명령과 결과를 적는다. push 게이트(`pnpm test:full`)는 돌리지 않는다 — 합친 main 의 회귀는 검토자가 머지 뒤 아래 「풀테스트」로 잡는다.
- api: 바꾼 패키지(`--tests "com.smartfirehub.<패키지>.*"`). 공용(`global`·보안 설정·마이그레이션·jOOQ)을 바꿨으면 그 기능을 쓰는 패키지까지 넓힌다. 커밋 전 바꾼 파일만 spotless(6절 「포맷터 전체 실행 금지」).
- ↳ 마이그레이션(`apps/firehub-api/src/main/resources/db/migration/`): `./gradlew generateJooq` 결과를 같은 커밋에. 번호(`V<n>__`)가 origin/main 의 마지막보다 큰지 커밋 전에 `pnpm check:migrations origin/main HEAD` 로 본다(그 뒤 main 과 겹치면 검토자가 손 머지에서 옮긴다 — 8절).
- web: `pnpm typecheck` · 바꾼 도메인 E2E(`e2e/pages/<도메인>/`) · 바꾼 단위 테스트. 공유 영역(components/api/lib/hooks/types, `e2e/` 헬퍼, `vite.config.ts`·`playwright.config.ts`)을 바꿨으면 E2E 전체.
- ai-agent·admin·channel·executor: 그 앱의 테스트 전체(위 명령). **풀테스트는 web·api 만 돈다** — 이 앱들의 회귀는 개발자 테스트가 유일한 확인이라 줄이지 않는다.
- 훅·스크립트(`scripts/husky/`·`.husky/`·`scripts/check-migration-versions*`): `pnpm test:hooks`.
- 문서(`*.md`·`docs/`·`.work-loop/`·`.claude/`)만: 테스트 없음.

### 커밋 훅·push 게이트 (WD-459)

WP-2003 뒤 이 저장소는 검토자가 있어 개발자가 push 게이트를 돌리지 않는다(`git push --no-verify`). 아래 게이트는 사람 세션과 검토자의 풀테스트가 쓴다.
- **커밋 훅**(`scripts/husky/pre-commit.sh`): lint-staged → (코드 변경이 있으면) 전체 typecheck → web 변경 영역별 E2E → api 변경분 gradle.
  - 문서만(`scripts/husky/noncode.sh` — `docs/`·루트 `.md`·`README/CLAUDE/AGENTS.md`·`.claude/`·`.work-loop/`) 바꾸면 모두 건너뛴다. `apps/` 아래 다른 `.md` 는 코드다.
  - web 코드를 바꾸면 커밋 때는 `@smoke` + 바꾼 도메인(`src/pages/<d>/`) spec + 바꾼 spec 파일(`e2e/**/*.spec.ts`)만 playwright 한 번에 돈다. 공유 영역(components/api/lib/hooks/types, `e2e/` 헬퍼, 설정)·매핑 밖 페이지도 `@smoke` 만 — 전체 E2E 는 커밋 때 돌지 않고 push 게이트가 한 번 돈다 (WD-764).
  - gradle 은 api 를 바꿨을 때만 — 바뀐 테스트 클래스와 이름 규칙(`Foo*Test`·`Foo*Tests`·`Foo*IT`) 매칭 테스트만, 없으면 `compileTestJava`. 전체는 push 게이트.
  - 훅 파일(`scripts/husky/`·`.husky/`·`scripts/check-migration-versions*`)을 바꾸면 판정 테스트 `pnpm test:hooks` 가 먼저 돈다 — `scripts/husky/*.test.sh` 전부(`hooks.test.sh`·기차 `train.test.sh`)와 `node --test scripts/check-migration-versions.test.mjs`.
- **push 게이트**(`pnpm test:full` = `scripts/husky/full-regression.sh`, pre-push 가 부른다): zod 단일 판 가드 → web vitest 전체 → web E2E 전체(preview) → api `gradlew test` 전체(JaCoCo 끔).
  - **범위**: origin/main 과 갈라진 뒤 바꾼 코드가 `apps/firehub-web` 뿐이면 gradle 을 건너뛴다(웹 E2E 는 API 를 모킹). 그 밖은 전체. 첫 줄 `[full-regression] 범위: …` 에 찍힌다. `FULL_REGRESSION_SCOPE=full pnpm test:full` 이면 전체 강제.
  - **통과 기록**: 끝까지 통과하고 작업 트리가 커밋과 같으면 그 트리를 git common dir 의 `firehub-prepush-verified-trees` 에 기록한다(모든 워크트리 공유).
  - **pre-push 생략**: 새 커밋 없음·삭제·비코드만·이미 통과한 트리(필요 범위를 덮을 때)면 회귀를 건너뛴다 — `[pre-push] 회귀 생략 — …`.
  - **미리 돌리기**: 커밋한 상태에서 `pnpm test:full` 을 bg-job 으로 띄우면, 통과한 뒤의 `git push` 는 회귀 없이 끝난다. 지금 HEAD 트리가 기록됐는지는 워크트리에서 `sh -c '. scripts/husky/noncode.sh && . scripts/husky/gate-scope.sh && . scripts/husky/verified-trees.sh && is_verified_head'`(성공이면 기록됨 — 다시 돌리지 않는다). rebase 하면 트리가 바뀌어 다시 돌아야 한다.
  - 게이트는 저장소 전역 락(`firehub-test-gate.lock`)으로 한 번에 하나만 돈다 — 락 파일을 지우거나 다른 세션의 게이트를 끝내지 않는다.

### 풀테스트 (WP-2003 — 검토자, `process-issue.md` 「풀테스트·green (검토자)」)

개발자는 push 게이트 없이(`--no-verify`) 올리고 검토자는 받는 대로 머지한다. 합친 main 은 검토자가 리뷰할 것이 없을 때 아래로 한 번에 돌리고, 통과한 커밋으로 `green` 브랜치를 옮긴다. 개발자는 `origin/green` 에서 브랜치를 딴다. **풀테스트를 띄운 뒤 green 을 옮길 때까지(실패해 고치는 동안 포함) 검토자는 PR 을 머지하지 않는다** — 리뷰·승인만 하고, green 을 옮긴 뒤 승인한 순서대로 머지한다. 그동안 main 에는 풀테스트 실패의 고침·되돌리기만 들어가, 다시 돌린 풀테스트가 고침만 검사한다.

```sh
pnpm install && FULL_REGRESSION_SCOPE=full pnpm test:full   # green 워크트리(MAIN/.claude/worktrees/green, main 의 정한 커밋에 detach)에서. 범위 판정은 origin/main 기준이라 main HEAD 에서는 빈 범위가 나오므로 전체를 강제한다
```

- 실패한 테스트만 다시 돌릴 때(불안정 확인·수정 확인): api `cd apps/firehub-api && ./gradlew cleanTest test --tests "<클래스>"`, web vitest `cd apps/firehub-web && pnpm exec vitest run <파일>`, web E2E `cd apps/firehub-web && E2E_SERVER=preview PW_PORT=<빈 포트> pnpm test:e2e <spec> --workers=1`.
- 풀테스트는 web(vitest·E2E)·api 만 본다. ai-agent·admin·channel·executor 는 들지 않는다 — 위 「바뀐 범위 테스트를 고르는 법」.
- green 의 첫 위치는 이 흐름을 들인 때의 origin/main 커밋이다(사람이 만든다 — `git push origin <sha>:refs/heads/green`).
- **생성 코드**(손 머지가 다시 만드는 것): `apps/firehub-api/src/main/generated`(jOOQ) — 충돌하면 머지 워크트리에서 `cd apps/firehub-api && ./gradlew generateJooq`(Docker 필요 — 임시 Postgres 에 마이그레이션을 적용해 만든다)로 다시 만들어 넣는다.

### 통합 PR 정리 (WP-2003 — 예전 묶음 흐름을 끔)

이 절은 `process-issue.md` 가 찾는 「기차」 절이 아니다 — 이 저장소에는 「기차」 절이 없으니 개발자는 이슈마다 PR 을 낸다.

기차(WD-460 — 이슈를 `integ/<AI 이름>-<번호>` 에 쌓아 게이트 한 번·PR 하나)는 껐다. 게이트를 묶어 돌리려던 것인데 개발자 게이트가 없어졌고, 「앞 기차가 머지돼야 다음 출발」이 줄을 하나 더 만들었다. 새 이슈는 이슈마다 PR 이다(`process-issue.md` 9번 — 브랜치 `<커밋 타입>/<소문자 이슈 키>` 를 push 하고 PR 제목 `<이슈 키> <이슈 제목>`).

- 쌓았지만 아직 출발하지 않은 내 기차가 있으면(`pnpm train next <AI 이름>`) 출발 게이트 없이 기차 워크트리에서 `git push --no-verify -u origin <기차>` 하고 통합 PR(제목 `통합 <키>·…` — `pnpm train keys <기차>`, 본문 첫 줄 `개발: <username>`, 이슈마다 요약·바뀐 범위 테스트·라이브 검증)로 올린다. rebase 하지 않는다.
- 이미 올린 통합 PR 은 검토자가 그대로 리뷰·머지한다. 반려되면 같은 기차에 수정 커밋을 더해 `--no-verify` 로 push 한다.
- 스크립트 `scripts/husky/train.sh` 는 이 정리에만 쓴다.

### 커밋 훅으로 충분한 것 (`process-issue.md` 9번 「돌릴지부터 판단」)

WP-2003 뒤 개발자는 push 게이트를 돌리지 않으므로 이 목록은 검토자가 없을 때(사람이 리뷰하는 세션)만 쓴다.
push 게이트가 커밋 훅보다 더 돌리는 것은 web vitest·E2E 전체와 api gradle 전체다. 브랜치가 바꾼 파일이 모두 아래에 들면 게이트를 돌리지 않고 근거를 PR 에 남긴다.
- 비코드(`noncode.sh` 규칙) — 문서·`.work-loop/`·`.claude/`. pre-push 도 스스로 건너뛴다.
- 그 밖의 코드 변경은 게이트를 돈다(다른 앱만 바꾼 변경을 게이트에서 빼는 것은 아직 없다).

### 오래 걸리는 작업 (`process-issue.md` 「오래 걸리는 작업」·9번, WD-506)

work-loop 는 아래 명령을 기다리지 않고 백그라운드(`bg-job`)로 돌린다. 예상 시간은 notes 의 `끝날 시각`·`nextRunAt` 을 잡는 기준이다 — 리눅스 호스트(여러 AI 클론이 함께 도는 상태, load 2~9)에서 잰 값이고, 게이트 락 대기는 빠져 있다.

| 명령 | 예상 시간 | 근거 |
|---|---|---|
| `pnpm test:full`(push 게이트) — 전체(web vitest·E2E + api gradle) | 60분 | 아래 둘의 합 |
| 풀테스트(`FULL_REGRESSION_SCOPE=full pnpm test:full`, green 워크트리 — 검토자) | 60분 | 위와 같은 단계 |
| `pnpm test:full` — web 만 바꾼 범위(gradle 빠짐) | 25분 | 기차 `integ/developer2-29` 게이트, E2E 2951건 24.1분(2026-11-17) |
| web E2E 전체(`apps/firehub-web`, `PW_PORT=<빈 포트> pnpm exec playwright test`) | 24분 | 위 게이트의 E2E 단계, WD-772 커밋 훅 2939건 23.2분 |
| `./gradlew test`(api 전체, `apps/firehub-api`) — 기본 포크 2 | 35분 | WD-764 실측 2070초(12,9xx건, 실패 0, 2026-11-17) |
| `./gradlew test -PtestForks=1`(api 전체, 포크 1) | 43분 | WD-764 같은 커밋 실측 2571초(실패 1 — 포크 2 에서는 통과) |

- 포크 2 는 포크 1 보다 남은 메모리(`free` available 최저)가 13.3GB → 11.5GB 로 1.8GB 줄었고 스왑 사용은 늘지 않았다(31GB 호스트). 메모리가 작은 환경은 `FIREHUB_TEST_FORKS=1`.
- 한 번에 `test:full` 하나만 돈다. 락(`firehub-test-gate.lock`)은 git common dir 에 있어 **한 클론(= AI 하나) 안의 워크트리·세션끼리만** 공유한다 — 다른 AI 의 클론과는 락이 아니라 CPU·메모리를 나눠 쓴다. 내 이슈의 커밋 훅·풀테스트·측정을 한꺼번에 띄우면 서로 락을 기다리느라 그만큼 늦게 끝난다.
- api 전체(`./gradlew test`)·web E2E 전체를 게이트 밖에서 직접 돌릴 때는 `-PtestForks`(또는 `FIREHUB_TEST_FORKS`)를 2 넘게 주지 않는다.

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
- **어디서 확인했나(WD-506)**: 리눅스 호스트(Ubuntu 24.04, docker 29, 여러 AI 클론이 공유 바탕 하나를 함께 씀)에서 up·브라우저 검증·down 이 그대로 돈다(2026-11-06 WD-502 검증). AI Pod 칸(dind 사이드카, uid 1001)과 그 안의 공유 바탕 Postgres·MinIO 는 아직 확인하지 못했다 — Pod 칸에서 처음 띄울 때 차이를 여기에 적는다.
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

다른 세션(사람의 dev 스택, 다른 클론의 라이브 스택, 사람의 맥이면 로컬 운영 배포)이 쓴다. 죽이거나, 재시작하거나, 데이터를 바꾸지 않는다.
이 저장소는 세 곳에서 돈다 — **사람의 맥**, 여러 AI 클론이 함께 쓰는 **리눅스 호스트**, **k8s AI Pod 칸**. 아래는 어디서든 지키고, 맥에만 있는 것은 따로 묶었다.

- **MAIN** = 메인 체크아웃 = `git rev-parse --path-format=absolute --git-common-dir` 의 부모 폴더(경로는 환경마다 다르다). MAIN 에서 브랜치를 만들거나 바꾸거나 파일을 고치지 않는다 — 모든 수정은 이슈 워크트리(검토자의 손 머지·풀테스트는 그 워크트리·green 워크트리)에서 한다.
- **dev 포트**: 5010(api), 5040(JobRunr 대시보드), 5020(ai-agent), 5030(channel), 5050(admin), 5173(web), 5051(admin E2E)
- **dev 컨테이너/DB**: `smart-fire-hub-db-1`(5432, `smartfirehub`), `db-test`(5433), MinIO 9000/9001, Neo4j 7474/7687 — 루트 `docker-compose.yml` 의 `smart-fire-hub` project 전체
- **공유 바탕의 다른 저장소 것**: `work-loop-shared` 의 내 것이 아닌 DB(`smartfirehub_*`)·버킷(`firehub-*`) — `status` 의 「다른 저장소」 목록. 공유 바탕 자체를 재시작·down 하지 않는다(`shared-down` 은 사람만). 예외는 `up` 이 멈춘(Exited) 공유 바탕을 그 포트의 유일한 주인일 때 `--no-recreate` 로 다시 띄우는 것뿐이다 — 손으로 `docker start`·`restart` 하지 않는다(WD-942).
- **다른 클론의 라이브 스택**: 내 STACK_ID 가 아닌 `work-loop-*` compose project(전용 DB·Neo4j)와 그 포트, 옛 방식 project `work-loop`
- **사람의 맥에서 돌 때만 해당 — 리눅스 호스트·AI Pod 칸에는 없다**: 로컬 운영 배포 `smart-fire-hub-prod-*` 컨테이너 전부(web 8888, admin 8889, prod DB·Neo4j·MinIO), 6768(Orca), `buildx_buildkit_*`
- 금지 명령: `pnpm dev` / `pnpm dev:full`(5xxx 포트를 `lsof | kill -9` 로 죽인다), `pnpm db:up|db:down|db:reset`, `docker compose down`(project 미지정),
  `playwright-cli kill-all`, 운영 배포(`scripts/deploy.sh`·`scripts/update.sh`·`docker-compose.prod.yml`)
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
- **셸 명령 모양(사람 없는 칸이 확인 창에 멈춤)**: 셸 함수 정의(`f(){…}`)·중괄호가 든 기본값 확장(`${x:-{…}}`)·수십 줄 `;` 연결은 쓰지 않는다 — Claude Code 안전 검사가 「Dangerous rm operation not ruled out」 같은 확인 창을 띄워, 사람이 없는 칸이 1시간 넘게 멈춘 적이 있다(WD-499). 여러 API 를 차례로 부르는 검사는 스크립트 파일(스크래치 폴더)로 써서 `sh <파일>` 로 실행한다. 공통 규칙(확인 창 자동 거부 훅 등)은 플러그인이 맡는다(WP-1398).
- **playwright-cli 브라우저가 「No usable sandbox!」로 안 뜸**: 사용자 네임스페이스를 막은 리눅스(Ubuntu 23.10+ AppArmor)·컨테이너에서 난다. 저장소의 `.playwright/cli.config.json` 은 고치지 않고, 스크래치 폴더에 `{"browser":{"browserName":"chromium","launchOptions":{"chromiumSandbox":false},"contextOptions":{"viewport":{"width":1440,"height":900}}}}` 를 두고 `playwright-cli -s=wl-$STACK_ID open --config=<그 파일> <URL>` 로 연다.

## 7. 대기열 라벨

다른 AI 에게 일을 넘길 때 아래 역할이면 특정 AI 를 담당으로 넣지 않고 그 라벨을 붙인다(`SKILL.md` 「이슈 만들기·담당 바꾸기」). 그 라벨로 도는 AI(`--queue "<라벨>"`) 중 할 일이 없는 AI 가 하나 가져간다. 표에 없는 역할은 설명을 보고 AI 를 고른다. 대기열 라벨 이름은 숫자만으로 짓지 않고 쉼표(,)를 넣지 않는다 — `list_issues` 의 label 필터가 숫자는 라벨 id 로, 쉼표는 구분자로 읽는다(WP-1386).

| 역할 | 라벨 |
|---|---|
| 개발 일(구현·결함 수정) | `AI 개발팀` |

### 선행 관계로 막힌 이슈 (WP-997 → WD-506)

이슈 DONE 은 운영 배포 뒤에 사람이 처리하므로, 서버의 `blocked`(선행이 DONE·CANCELED 가 아님)는 선행이 main 에 들어간 뒤에도 배포 전까지 켜져 있다. 후속 개발은 main 위에서 하므로 배포를 기다리지 않는다 — 내 이슈든 대기열 이슈든 `blocked` 를 아래 기준으로 다시 판정한다.

- 모든 선행(`get_issue_detail` 의 `blockedBy`)이 다음 중 하나면 착수할 수 있다: DONE·CANCELED / 선행 PR(제목이 `<선행 키> ` 로 시작하는 것, 또는 남은 통합 PR 제목 `통합 …` 에 `<선행 키>` 가 든 것 중 가장 최근 것 — `process-issue.md` 맨 위 갈래와 같은 조회)이 `MERGED` / 선행의 AI 작업이 「<커밋> 머지 — 검토 대기」(REVIEW). 선행이 머지됐어도 아직 green 에 없으면(풀테스트 전) 새 브랜치를 `origin/green` 에서 따면 선행이 빠진다 — 그때는 `origin/main` 에서 딴다.
- 선행 본문이 사람 확인을 확정 조건으로 걸면(예: 「규칙안은 사람 확인을 받아야 확정」) 그 확인 글(사람이 쓴 이슈 챗·댓글)이 선행 이슈에 있어야 한다. 없으면 착수하지 않는다.
- 후속 본문의 착수 조건 「WD-N 완료」는 이 기준으로 읽는다. 본문이 다른 조건(시안 확인, 같은 파일을 고치는 이슈 대기 등)을 더 걸었으면 그것도 따른다.
- 하나라도 맞지 않으면 대기열에서 가져가지 않는다(가져간 뒤 막혀 있으면 `BLOCKED` + 기다리는 선행). 서버의 `blocked` 표시·DONE 처리 방식은 바꾸지 않는다.

## 8. PR (`process-issue.md` 9번·「PR 리뷰·머지」, WP-2003)

- 원격은 **origin**(`https://github.com/bluleo78/smart-fire-hub.git`), PR 대상 브랜치는 `main`, 도구는 `gh`(work-loop 는 스킬의 `gw-repo` 로 부른다). gitlab 등 다른 원격에는 push 하지 않는다 — 사람이 origin main 을 받아 맞춘다.
- 브랜치: 이슈마다 `<커밋 타입>/<소문자 이슈 키>`(예: `fix/wd-272`)를 `origin/green` 에서 따서(없으면 `origin/main`) 개발하고, 3절 바뀐 범위 테스트·4절 라이브 검증까지 끝낸 뒤 **커밋 하나로 합쳐**(제목 끝 `(<이슈 키>)`, 예: `fix(web): … (WD-354)`) push 하고 PR 로 올린다(기차는 껐다 — 3절 「통합 PR 정리」). 커밋 훅(`.husky/pre-commit`)은 그대로 돈다.
- PR 제목 `<이슈 키> <이슈 제목>`, 본문 첫 줄 `개발: <개발한 AI username>`, 이어서 요약·「절차」 줄·바뀐 범위 테스트(명령과 결과)·라이브 검증 근거. 머지 커밋 제목 `merge: <이슈 키> <이슈 제목>`(`gh pr merge --merge` 또는 검토자의 손 머지 — 지금 main 이력 모양을 지킨다). 남은 통합 PR(제목 `통합 <키1>·<키2>·…`)은 머지 커밋 `merge: 통합 <키1>·<키2>·…` 로 그대로 리뷰·머지한다.
- **통합 PR 리뷰(WD-460)**: 이슈 단위로 리뷰하고(이슈 하나 = 커밋 하나 + 그 키를 단 수정 커밋) 마이그레이션 번호(아래 0번)·테스트 근거(아래 1번)를 확인한다. 하나라도 문제면 PR 전체를 반려하고 어느 이슈가 왜 문제인지 적는다 — 개발자는 같은 PR 에 수정 커밋을 더한다. 통과하면 머지하고 실린 이슈마다 검토 대기(REVIEW)로 올린다 — 머지 커밋은 `message` 에.
- **바로 머지(WP-2003)**: 개발자는 바뀐 범위 테스트(3절)만 돌리고 `git push --no-verify` 로 올리며 rebase 하지 않는다. 검토자는 리뷰를 통과한 PR 을 받는 대로 머지한다 — 충돌 없으면 `gh pr merge --merge`(gw-repo), main 과 충돌하거나 마이그레이션 번호가 겹치면 검토자가 이슈 워크트리에서 origin/main 에 머지 커밋을 만들어 풀고 `git push --no-verify origin HEAD:main`(손 머지 — GitHub 가 PR 을 머지됨으로 닫는다). 양쪽 뜻을 함께 살릴 수 없는 「뜻 충돌」만 개발자에게 rebase 로 돌려보낸다. 합친 결과의 회귀는 3절 「풀테스트」가 잡는다. 풀테스트를 띄워 green 을 옮길 때까지는 머지하지 않고 승인만 한다(3절).
- 머지는 **`--admin` 없이** `gh pr merge --merge …` 로만 한다. AI 들이 관리자 계정을 함께 쓰므로 `--admin` 을 붙이면 보호가 뚫린다 — 어떤 경우에도 붙이지 않는다(거절되면 이유를 보고 고치거나 BLOCKED). AI 의 main 직접 push 는 검토자의 세 예외(손 머지·풀테스트 수정·되돌리기 — `green` 은 main 이 아니다)뿐이다.
- **CI 없음**: 이 저장소에는 GitHub Actions CI 가 없다 — `gh pr checks`·`verify` 단계는 건너뛴다. 머지 전 자동 확인은 아래 0번 마이그레이션 번호뿐이고, 합친 main 의 회귀는 풀테스트가 잡는다.
- 마이그레이션 번호가 main 과 겹치면 검토자가 손 머지에서 PR 쪽 파일 이름의 번호를 옮긴다(내용 그대로 — jOOQ 생성 코드는 스키마가 같아 바뀌지 않는다). 두 마이그레이션이 같은 테이블을 고쳐 순서가 결과를 바꾸면 「뜻 충돌」로 돌려보낸다.

### 검토자 머지 판단 (WP-1081 → WP-1959)

**검토자는 테스트를 직접 돌리지 않는다(WP-1959)** — 로컬 합친 검사·격리 스택·vitest·gradle 어느 것도 검토자가 리뷰 중에 돌리지 않는다. 테스트는 개발자의 바뀐 범위 테스트와 머지 뒤 검토자의 풀테스트(3절 — 리뷰할 것이 없을 때)가 돌리고, 검토자는 리뷰 때 그 결과와 근거를 보고 머지하거나 반려한다. 예전에는 main 이 같은 앱을 고쳤으면 검토자가 최신 main 과 합친 상태로 그 앱 테스트를 돌렸는데(WD-506 조건부 합친 검사·묶음), PR 마다 api 전체(약 35분)를 검토자 한 명이 줄지어 돌아 모든 개발자가 함께 멈췄다(smart-workplace 2026-11-22).

0. **마이그레이션 번호 확인(늘, 머지 직전)**: `git fetch origin main <PR 브랜치>` 뒤 `pnpm check:migrations origin/main origin/<PR 브랜치>`(테스트가 아니라 몇 초 걸리는 번호 비교다). PR 이 더한 `V<n>__*.sql` 이 origin/main 의 가장 큰 번호보다 크고 서로·main 과 겹치지 않는지 본다 — 두 PR 이 같은 번호를 각자 더하면 git 충돌 없이 합친 main 에서 Flyway 가 깨진다. 실패(종료 코드 1)면 손 머지(위 「바로 머지」)에서 PR 쪽 번호를 옮겨 머지한다(개발자에게 돌려보내지 않는다). 마이그레이션을 더한 PR 이 둘 이상이면 하나씩 머지하며, 앞의 것이 머지된 뒤 다음 PR 을 다시 확인한다.
1. **테스트 근거 확인**: PR 본문의 바뀐 범위 테스트 결과(3절 「바뀐 범위 테스트를 고르는 법」대로 돌렸는지)를 본다. push 게이트 결과는 요구하지 않는다(WP-2003). 함께 들어가 깨지는 의미 충돌은 머지 뒤 풀테스트(3절)가 잡는다. 다만 풀테스트가 보지 않는 앱(ai-agent·admin·channel·executor)을 바꾼 PR 은 그 앱의 테스트 전체 결과가 있어야 한다.
2. **테스트가 필요하면 반려한다**: 검토자가 보기에 테스트가 더 필요하면 직접 돌리지 않고 무엇을 왜 돌려야 하는지 적어 반려한다 — 개발자가 돌리고 결과를 PR 에 적어 다시 부탁한다. 완료 기준의 근거(테스트·캡처·라이브 검증)가 모자라거나 의심스러울 때 — 어느 기준에 무엇이 모자란지 적는다. 「push 게이트를 돌려 주세요」로는 반려하지 않는다.
3. **하나씩**: 같은 파일·영역(DB 마이그레이션, 공통 컴포넌트·토큰, 공통 유틸·테스트 헬퍼)을 고치는 PR 은 앞의 것이 머지된 뒤 다음 PR 의 충돌·마이그레이션 번호를 다시 보고 머지한다.
4. **main 이 깨졌을 때**: 풀테스트(3절)가 실패하면 검토자가 `process-issue.md` 「풀테스트·green (검토자)」 4번대로 원인 머지를 찾아 작게 고치거나 되돌린다. 개발자는 green 에서 시작하므로 main 이 깨져 있어도 그 실패를 고치지 않는다 — 내가 바꾸지 않은 곳의 테스트가 실패하면 PR 본문에 적고 넘어간다.
5. 검토자의 push 는 손 머지·풀테스트 수정·되돌리기·green 옮기기뿐이다. 리뷰 보고에 테스트 근거를 어떻게 확인했는지 한 줄 남긴다(예: 「바뀐 범위 테스트(api pipeline 패키지·web pipeline E2E) 통과·마이그레이션 번호 이상 없음」).

## 9. 검토자

개발한 PR 을 리뷰·머지하는 AI 팀장(`SKILL.md` 「검토자」). 비어 있으면 PR 을 사람이 리뷰·머지한다.

- username: `teamleader2@iacloud.kr`(AI 팀장 — 2026-11-22 teamleader 에서 바뀜)
- **머지 조건(WP-1717)** — 리뷰 글에 아래가 없으면 머지하지 않는다. 리뷰 시간·비용이 늘어도 규정대로 한다.
  - 개발자 PR 본문의 「절차」 줄을 확인한다(`process-issue.md` 5번 형식, 흉내만 낸 줄이면 반려).
  - 브랜치 전체에 `/code-review medium` 을 실제로 돌리고(다시 부탁받으면 달라진 부분 — `process-issue.md` 「PR 리뷰·머지」 3번) 그 결과를 적는다.
  - 완료 기준 대조표를 적는다(미충족·확인 불가가 있으면 반려, 통합 PR 은 이슈마다).
- **바로 머지·풀테스트(WP-2003)** — 리뷰를 통과한 PR 은 받는 대로 머지한다(순서 머지 WP-1720·앞지르기 WP-1999 는 없앴다). 충돌은 검토자가 손 머지로 푼다(8절). 리뷰할 것이 없으면 main 을 풀테스트하고 통과한 커밋으로 `green` 을 옮긴다 — 풀테스트를 띄운 뒤 green 을 옮길 때까지는 머지를 멈추고 승인만 한다(3절 「풀테스트」, `process-issue.md` 「풀테스트·green (검토자)」).

## 10. 할 일 없을 때 (`SKILL.md` 3번 「할 일 없음」)

- 검토자는 3절 「풀테스트」를 본다(`process-issue.md` 「풀테스트·green (검토자)」 1번 — main 이 green 보다 앞서 있으면 띄운다).
- main CI 는 없어 확인할 실패 실행이 없다(CI 를 들이면 smart-workplace 처럼 main CI 실패를 확인해 알리는 내용을 여기에 적는다).
- 분류 역할 AI·기획 역할 AI 는 이 저장소에 두지 않는다 — 맡은 이슈는 모두 `process-issue.md` 로 처리한다.
