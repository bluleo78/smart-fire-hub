---
name: ai-driven-explorer
description: >
  AI 주도 탐색적 테스트 스킬. playwright-cli로 실제 브라우저를 headed 모드로 열어
  기능/페이지를 직접 탐색하며 버그를 발견한다.
  사용자가 "playwright로 테스트해줘", "UI 탐색해줘", "버그 찾아줘", "기능 검증해줘",
  "headed 모드로 테스트", "탐색적 테스트", "exploratory test" 등을 요청할 때 반드시 이 스킬을 사용한다.
  관점(perspective)별 패스도 지원: "디자인 검증/UX 검토" → design, "접근성 검증/a11y" → a11y, "성능 검증/perf" → perf.
  관점 미지정 시 기본 bug 패스(security/error/async/critical 등).
  또한 "크로스체크해줘", "fix 확인해줘", "이슈 수정 검증해줘",
  "resolved 라벨 검증" 같이 ai-driven-solver가 resolved 처리한 GitHub Issue를 재검증하는 요청에도 반드시 이 스킬을 사용한다.
  TC 명세서 기반 자동화 회귀 테스트가 아닌, 탐색적/인간적 관점의 자유로운 테스트에 특화되어 있다.
---

# Playwright 탐색적 UI 테스트

이 스킬은 두 가지 모드로 동작한다:

- **탐색 모드** (기본): 사람이 브라우저를 직접 쓰듯이 자유롭게 탐색하며 새 버그를 발견하고 GitHub Issues에 등록한다.
- **크로스 체크 모드**: `ai-driven-solver`가 수정한 이슈를 독립 fresh 세션에서 재검증한다.

**모드 결정**: 사용자 요청에 "크로스체크", "이슈 수정 검증", "fix 확인", "resolved 라벨" 키워드가 있으면 크로스 체크 모드로 진입한다. 아니면 탐색 모드.
("수정 확인" 같이 solver의 일상 작업과 겹치는 모호한 표현은 트리거에서 제외한다.)

> 이슈 라이프사이클 전체 다이어그램·라벨 정의는 `.claude/docs/issue-lifecycle.md` 참조.

> **[필수 원칙] Explorer는 발견과 등록만 한다.**
> 버그 발견 → `gh issue create` 등록 → 탐색 계속 → 보고서 작성 → 종료.
> `ai-driven-solver`는 절대 자동으로 시작하지 않는다. Solver는 사용자가 명시적으로 별도 요청할 때만 독립 실행한다.
> 이유: 같은 사이클에서 탐색+수정을 섞으면 컨텍스트가 오염되고 하네스 관점(발견 vs 수정)이 무너진다.

> **함정 목록**: playwright-cli 사용 중 막히면 `references/pitfalls.md`를 읽는다.

## 1. 브라우저 실행

> **도구 선택**: 브라우저 자동화는 반드시 `playwright-cli` CLI를 사용한다.
> Playwright MCP(`mcp__plugin_playwright_playwright__browser_*`) 도구는 사용하지 않는다.

**세션 이름 규약**:
- 사용자 직접 호출 (탐색 모드): `SESSION="explorer#$(openssl rand -hex 3)"` (random)
- Pilot subagent 호출 (탐색 모드): `SESSION="pe$(openssl rand -hex 2)"` (예: `pe1a2b`). 짧게 유지 — 소켓 경로 한도 이유
- 환경변수 `SESSION_NAME`이 있으면 그 값 그대로 사용 (pilot이 전달):

```bash
SESSION="${SESSION_NAME:-explorer#$(openssl rand -hex 3)}"
```

이후 모든 명령에 `$SESSION`을 사용한다:
```bash
playwright-cli -s=$SESSION --headed open <URL>
playwright-cli -s=$SESSION snapshot
playwright-cli -s=$SESSION click "button:has-text('저장')"
playwright-cli -s=$SESSION close
```

세션이 죽었으면 해당 세션만 닫고 재시작한다. `kill-all`은 절대 사용하지 않는다.

업로드 테스트용 임시 파일은 **`.playwright-cli/`** 안에 생성한다 (루트 밖 `/tmp/` 등은 접근 거부됨).

### 인증 상태 관리

저장된 상태가 있으면 먼저 로드하되, **로드 후 반드시 로그인 여부를 확인**한다:
```bash
playwright-cli -s=$SESSION state-load .playwright-cli/state.json
playwright-cli -s=$SESSION --raw snapshot > /tmp/snap.yml
grep -i "email\|로그인\|login" /tmp/snap.yml | head -3
# → 이메일 인풋이 보이면 state 만료 → 수동 로그인 필요
```

수동 로그인:
```bash
playwright-cli -s=$SESSION fill "input[placeholder*='email']" "<email>"
playwright-cli -s=$SESSION fill "input[type='password']" "<password>"
playwright-cli -s=$SESSION click "button:has-text('로그인')"
sleep 2
playwright-cli -s=$SESSION state-save .playwright-cli/state.json
```

### SPA 내 페이지 이동

`open <URL>`은 새 브라우저를 여는 것이므로 로그인 상태가 끊긴다.
로그인 상태를 유지하며 이동할 때는:
```bash
playwright-cli -s=$SESSION eval "window.location.href='/target-path'"
sleep 1.5
```

## 2. 테스트 대상 파악 — 컴포넌트 인벤토리 구성

탐색적 테스트는 랜덤 클릭이 아니다. **"어디를 테스트할지"는 계획, "어떻게 테스트할지"는 자유**다.

> 컨텍스트가 컴팩션되어 세션이 재시작된 경우 → `references/session-recovery.md` 참고.

### 0단계: Perspective 결정

탐색은 **관점(perspective)**별로 분리해 진행한다. 같은 화면도 관점이 다르면 검증할 시나리오가 다르므로, 매트릭스도 관점별로 따로 둔다.

| 사용자 발화 키워드 | perspective | 매트릭스 파일 | 시나리오 가이드 |
|------|----|----|----|
| 없음 / "탐색" / "버그 찾아줘" | `bug` (default) | `.coverage-matrix-bug.md` | `references/perspectives/bug.md` |
| "디자인", "design", "UX 검증" | `design` | `.coverage-matrix-design.md` | `references/perspectives/design.md` |
| "접근성", "a11y", "스크린리더" | `a11y` | `.coverage-matrix-a11y.md` | `references/perspectives/a11y.md` |
| "성능", "perf", "느려요" | `perf` | `.coverage-matrix-perf.md` | `references/perspectives/perf.md` |

진입 시 사용자 발화로 perspective 결정 → 해당 perspective 가이드 파일을 먼저 읽고 → 해당 매트릭스만 로드. 다른 perspective의 매트릭스는 건드리지 않는다.

### 세션 시작 결정 흐름

```
세션 시작
├── perspective 결정 (위 표)
│
├── test-results/exploratory/.component-tree.md 있음?
│   ├── YES → 로드 (perspective 무관, 공유). 테스트 대상 섹션이 트리에 있나?
│   │         ├── YES (상세 내용 있음) → 재사용
│   │         └── NO (⬜ 미탐색 표시 또는 섹션 자체 없음)
│   │               → 소스 읽어서 해당 섹션 상세 작성 후 진행
│   └── NO  → 앱 전체 뼈대 신규 작성 (메뉴 단위 섹션 + ⬜ 미탐색 표시)
│             그 후 테스트 대상 섹션만 상세 작성
│
├── test-results/exploratory/.coverage-matrix-<perspective>.md 있음?
│   ├── YES → 로드. ⬜(미시작) 항목만 이번 세션 대상
│   └── NO  → 트리의 해당 섹션에서 파생 + perspective 가이드의 시나리오 템플릿으로 신규 생성
│
└── 탐색 시작 → ⬜ 항목 순서대로 진행
```

### Step 1: 페이지/라우트 파악

라우팅 파일의 위치는 프로젝트마다 다르므로 먼저 찾는다:
```bash
find . -name "router*" -o -name "routes*" | grep src | head -5
find . -name "App.tsx" -o -name "App.jsx" | grep src | head -3
```

### Step 2: 컴포넌트 트리 구성 (정적 인벤토리)

**트리는 메뉴 단위 섹션으로 관리한다.** `test-results/exploratory/.component-tree.md`에 저장.
- 파일 없으면: 앱 전체 메뉴 뼈대를 `⬜ 미탐색` 표시로 작성
- 파일 있으면: 테스트 대상 섹션이 `⬜ 미탐색`이면 해당 섹션만 상세 작성. 나머지는 건드리지 않는다.

소스를 읽어 **모든 interactive element**를 계층적으로 열거한다.
"무엇이 존재하는가"를 정의하는 단계 — 테스트 여부와 무관하다.

트리 뼈대 예시 (신규 작성 시):
```
## [공통] AppLayout ⬜ 미탐색
## [메뉴A] /path-a/* ⬜ 미탐색
## [메뉴B] /path-b/* ⬜ 미탐색
```

상세 작성 시:
```
페이지: /example/:id — ExampleDetailPage
│
├── 탭 네비게이션: [탭] 정보 / 상세 / 이력
├── InfoTab
│   ├── [버튼] 수정 / 삭제
│   └── [입력] 이름, 설명 (수정 모드)
└── DetailTab
    ├── [버튼] 추가 / 내보내기
    └── [테이블] 행 클릭 → 수정 다이얼로그
```

### Step 3: 커버리지 매트릭스 (실행 추적)

트리에서 파생. **`.coverage-matrix-<perspective>.md`에 저장**하고 세션을 이어받는다 (예: `bug`면 `.coverage-matrix-bug.md`).

**매트릭스는 반드시 시나리오 레벨로 작성한다.** "버튼이 존재하는가"가 아니라 "이 입력을 주면 무슨 일이 벌어지는가"를 열거해야 한다.

❌ 잘못된 예 (컴포넌트 레벨):
```
| DataTab > 버튼    | 행추가/내보내기/삭제 | ⬜ |
```

✅ 올바른 예 (시나리오 레벨, bug perspective):
```
| 저장 > 이름 빈값/공백만      | 저장 버튼 비활성 여부              | ⬜ |
| 저장 > 이름 특수문자(<>'"&)  | 저장·표시 깨짐 여부, XSS 여부      | ⬜ |
| 저장 > 200자 초과            | 클라이언트/서버 검증 존재 여부     | ⬜ |
| 저장 > 버튼 중복 클릭        | 중복 요청 방지 (disabled/Loader)  | ⬜ |
```

**시나리오 템플릿·질문 리스트는 perspective 가이드 파일에서 가져온다** (Section 0의 표 참조). 같은 화면도 perspective가 다르면 시나리오가 다르다.

매트릭스에 새 항목을 추가할 때는 **반드시 해당 섹션 테이블 안에 삽입**한다.
파일 하단에 append하면 섹션 구조가 깨져 추적이 어려워진다.

상태: ⬜ 미시작 → 🔄 진행 중 → ✅ 완료 → 🔴 버그 발견

### Step 4: 사전 제외 확인

```bash
grep -ri "TODO\|hidden\|disabled\|coming soon\|미구현" src/ | head -10
cat CLAUDE.md
gh issue list --state open --limit 30 --json number,title,labels  # 이미 알려진 이슈 제외
```

### 탐색 범위·우선순위·시나리오 질문

Perspective별로 시나리오 템플릿이 다르다. **Section 0에서 결정한 perspective의 가이드 파일**(`references/perspectives/<perspective>.md`)을 읽어 시나리오 질문과 우선순위를 따른다.

- `bug` (default): Security/Error/Async/Critical/State/A11y(간단) — **개발자 관점의 기능 결함**
- `design`: 시각 위계/카피/간격/컬러/상태 표현/밀도/모션 — **전문 디자이너 관점**
- `a11y`: 키보드/포커스/스크린리더/대비/모션 민감성 — **WCAG AA 본격 검증**
- `perf`: LCP/INP/CLS/메모리/번들 — **체감 성능과 자원**

## 3. API 직접 테스트 패턴

UI로는 발견하기 어려운 Critical 버그(보안, 권한, 데이터 노출)는 curl로 API를 직접 찌른다.
탐색 범위 1순위(Security)에 해당하는 검증이다.

### 토큰 획득

```bash
TOKEN=$(curl -s -X POST http://localhost:5173/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user@example.com","password":"password"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
```

> 로그인 필드명이 `email`이 아닌 `username`일 수 있다. 400이 오면 CLAUDE.md나 소스를 먼저 확인.

### 보안 테스트 체크리스트

```bash
# 1. SQL/DML 실행 차단 여부 — 쿼리 에디터에 DELETE/UPDATE 전송
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sql":"DELETE FROM some_table WHERE 1=0"}' \
  "http://localhost:5173/api/v1/analytics/queries/execute"
# → 허용 시 Critical (버그 #66/#96 패턴)

# 2. 크로스 스키마 접근 — public 시스템 테이블 조회
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sql":"SELECT * FROM public.\"user\" LIMIT 3"}' \
  "http://localhost:5173/api/v1/analytics/queries/execute"
# → 비밀번호 해시 반환 시 Critical (버그 #33/#70 패턴)

# 3. IDOR — 타 사용자 리소스 직접 접근
curl -s -o- -w " [%{http_code}]" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:5173/api/v1/users/1"
# → 200 반환 시 Critical; 403이어야 정상 (권한 없는 USER 토큰으로 테스트)

# 4. 권한 경계 — USER 토큰으로 관리자 엔드포인트 접근
curl -s -o- -w " [%{http_code}]" -H "Authorization: Bearer $USER_TOKEN" \
  "http://localhost:5173/api/v1/users"
# → 403이어야 정상

# 5. 대량 할당 — 프로필 수정 시 roles 필드 주입
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"test","roles":["ADMIN"]}' \
  "http://localhost:5173/api/v1/users/me"
# → 역할 변경되지 않아야 정상

# 6. 미인증/만료 토큰
curl -s -o- -w " [%{http_code}]" "http://localhost:5173/api/v1/datasets"
# → 401이어야 정상
```

### DB 직접 조회 (버그 확인 / 복구)

특히 권한 변경, 역할 수정 등 DB 상태를 직접 확인해야 할 때:

```bash
# 로컬 Docker Compose DB (프로젝트별 컨테이너명/유저는 CLAUDE.md 참조)
docker exec <db-container> psql -U <user> -d <dbname> -c "SELECT ..."

# 예: 특정 사용자의 역할 확인
docker exec smart-fire-hub-db-1 psql -U app -d smartfirehub \
  -c "SELECT r.name FROM role r JOIN user_role ur ON r.id=ur.role_id WHERE ur.user_id=1;"
```

> DB 접속 정보(컨테이너명, 유저, DB명)는 앱별 CLAUDE.md에 정의되어 있다.

## 4. 탐색 명령어

### 기본 탐색
```bash
playwright-cli -s=$SESSION click "button:has-text('버튼명')"
playwright-cli -s=$SESSION click "[aria-label*='키워드']"
playwright-cli -s=$SESSION fill "input[placeholder*='입력']" "테스트값"
playwright-cli -s=$SESSION press "Tab"
playwright-cli -s=$SESSION press "Enter"
playwright-cli -s=$SESSION mousemove 600 400   # hover 해제
```

### 스냅샷 (가장 중요한 분석 도구)
```bash
playwright-cli -s=$SESSION --raw snapshot > /tmp/snap.yml
grep -i "키워드" /tmp/snap.yml
grep "button\|dialog\|alert\|error" /tmp/snap.yml
```

### 스크린샷
```bash
playwright-cli -s=$SESSION screenshot
ls .playwright-cli/page-*.png | tail -1  # 최신 파일 경로 확인
# 보관이 필요한 스크린샷은 세션 보고서 디렉토리로 복사
cp .playwright-cli/page-TIMESTAMP.png test-results/exploratory/<YYYY-MM-DDTHH-MM>/screenshots/<설명>.png
# Read("test-results/exploratory/...") 로 이미지 직접 확인 가능
```

### eval vs run-code 구분

| 명령 | 컨텍스트 | 사용 가능 | 사용 불가 |
|------|---------|----------|----------|
| `eval` | 브라우저 | `document`, `window`, DOM API | `page` 객체 |
| `run-code` | Node.js | `page` (Playwright API) | `document`, DOM API |

```bash
# DOM 조작 → eval (단일 표현식 또는 IIFE)
playwright-cli -s=$SESSION eval "(()=>{ document.querySelector('button').click(); })()"

# Playwright API → run-code (단일 await 표현식)
playwright-cli -s=$SESSION run-code "await page.waitForSelector('.loaded')"
```

### 네트워크 / 콘솔 캡처
```bash
# 콘솔 에러/경고 확인
playwright-cli -s=$SESSION run-code "
  const logs = [];
  page.on('console', msg => logs.push({ type: msg.type(), text: msg.text() }));
  await page.waitForTimeout(2000);
  return JSON.stringify(logs);
"

# API 요청 캡처 (리스너 먼저 등록, 그다음 액션 실행)
playwright-cli -s=$SESSION run-code "page.on('response', async r => { if(r.url().includes('/api/')) console.log(r.status(), r.url()) })"
```

## 5. 탐색 패턴

각 기능을 테스트할 때 이 관점으로 확인한다:

1. **정상 동작** — 기본 기능이 의도대로 작동하는가?
2. **엣지 케이스** — 빈 값, 긴 입력, 특수문자는?
3. **상태 전환** — 열기/닫기, 활성/비활성 전환이 깔끔한가?
4. **오류 처리** — 잘못된 입력, 서버 오류 시 사용자에게 명확한 피드백이 오는가?
5. **시각적 일관성** — 로딩 상태, 완료 상태 표시가 정확한가?
6. **UX 명확성** — 동일한 레이블의 버튼이 여러 개 있지는 않은가?

스냅샷을 자주 찍어서 진행 상황을 기록한다. 버그 발견 시 바로 스크린샷.

## 6. 버그 판정 기준

- **Critical**: 데이터 유실, 기능 완전 불가, 보안 이슈
- **Major**: 핵심 기능 일부 오동작, 데이터 오류
- **Minor**: 비핵심 기능 오동작, 처리되지 않는 예외
- **UX**: 혼란스러운 UI, 잘못된 레이블, 시각적 이상

## 7. 버그 문서화

발견된 버그는 GitHub Issues에 즉시 등록 후 **프로젝트 보드에도 추가**한다 (사람이 보드에서 즉시 볼 수 있도록).

**Perspective별 라벨 매핑** (반드시 perspective에 맞는 라벨을 부착해야 pilot이 올바르게 라우팅):

| Perspective | 기본 라벨 (심각도는 케이스별 조정) | 본문 형식 (Section 7 템플릿) |
|---|---|---|
| `bug` (default) | `bug,severity:critical\|major\|minor\|ux` | 아래 템플릿 그대로 (현상/재현/원인/수정 방향/메타) |
| `design` | `bug,severity:ux,design` (또는 `severity:major,design`) | `references/perspectives/design.md` §6 (현상/영향/비교/수정 방향/메타) |
| `a11y` | `bug,severity:ux,a11y` (또는 `severity:critical,a11y`) | `references/perspectives/a11y.md` §7 (WCAG SC 명시) |
| `perf` | `bug,severity:major,perf` (또는 `severity:critical,perf`) | `references/perspectives/perf.md` §7 (측정값·DevTools 캡처 첨부) |
| security 결함 | `bug,severity:critical,security` (perspective와 무관, 발견 즉시 부착) | bug 본문 + 보안 영향 항목 |

> **이 라벨이 pilot 라우팅의 핵심**: design/a11y/perf/security 라벨이 부착되면 pilot 자율 사이클이 자동으로 사람 큐로 빼서 디자인 토큰·SR 청취·측정값 같은 사람 영역 작업을 보호한다.

```bash
# bug perspective 기본 템플릿 (다른 perspective는 perspectives/<name>.md 참조)
ISSUE_URL=$(gh issue create \
  --title "컴포넌트명 — 한 줄 요약" \
  --label "bug,severity:critical" \
  --body "$(cat <<'EOF'
## 현상
한 문장으로 설명.

## 재현
1. 정확한 URL (예: /pipelines/5/triggers)
2. 어떤 요소를 클릭/입력
3. 관찰된 결과

## 원인
소스코드를 읽어서 근본 원인까지 파악 (파일경로:라인번호).

## 수정 방향
구체적인 코드 수준 수정 방향.

## 메타
- **컴포넌트**: `파일경로:라인번호`
- **발견**: YYYY-MM-DD (playwright 탐색 테스트)
EOF
)")

# 이슈 번호 추출 후 보드에 추가 (현재 iteration + Status=ready)
ISSUE_NUM=$(echo "$ISSUE_URL" | grep -oE '[0-9]+$')
bash .claude/skills/ai-driven-pilot/scripts/add-to-board.sh "$ISSUE_NUM"
```

> 보드 추가 스크립트는 ai-driven-pilot 스킬 소속이지만 발견 직후 가시성을 위해 explorer에서도 호출한다. 스크립트가 idempotent라 중복 호출돼도 안전.

심각도별 라벨: `severity:critical` / `severity:major` / `severity:minor` / `severity:ux`

**보안 이슈는 `security` 라벨 추가**: SQL/DML 우회, 크로스 스키마 접근, IDOR, 권한 우회, 민감 데이터 노출(비밀번호 해시·토큰 등), XSS, CSRF, 대량 할당 등 Section 3 보안 체크리스트로 발견된 버그는 `bug,severity:critical,security` 형태로 등록한다. `security` 라벨은 pilot의 자율 사이클에서 자동 close 차단 → 사람 결정으로 라우팅되는 트리거다.

커버리지 매트릭스의 🔴 항목에는 `gh issue view <번호> --json number,title`로 확인한 이슈 번호를 기록한다.

## 8. 최종 보고서

탐색 완료 후 `test-results/exploratory/<YYYY-MM-DDTHH-MM>/report.md`에 작성.

> 디렉토리는 **timestamp 중심**이다. 기능명·주제명을 디렉토리에 넣지 않는다 — 한 세션이 여러 기능을 넘나들어도 timestamp 하나로 묶이도록 한다. 콜론(`:`) 대신 하이픈(`-`)을 써서 파일시스템 호환성을 확보한다(예: `2026-04-25T14-30`).

```
test-results/
├── e2e/                  ← Playwright E2E 자동 생성 (outputDir)
├── tc/                   ← TC 실행 결과 (TC 명세서 기반)
│   └── <suite-name>/
└── exploratory/          ← 탐색적 테스트 (ai-driven-explorer)
    ├── .component-tree.md              ← 앱 전체 트리 (perspective 무관, 공유)
    ├── .coverage-matrix-bug.md         ← bug perspective 추적
    ├── .coverage-matrix-design.md      ← design perspective 추적 (사용자 요청 시)
    ├── .coverage-matrix-a11y.md        ← a11y perspective 추적
    ├── .coverage-matrix-perf.md        ← perf perspective 추적
    └── <YYYY-MM-DDTHH-MM>/
        ├── report.md
        └── screenshots/
```

보고서 작성 후 핵심 버그만 요약해서 사용자에게 보고한 뒤 **반드시 세션 close** (leak 방지):
```bash
playwright-cli -s=$SESSION close
```

## 크로스 체크 모드 — 이슈 수정 검증

> `ai-driven-solver`가 ✅ 수정 완료로 표시한 이슈를 독립적으로 재검증한다.
> solver의 자체 검증(Step 6)은 수정 직후 같은 컨텍스트에서 실행되어 편향될 수 있다.
> explorer는 fresh 세션에서 재현 단계를 독립 실행해 실제 fix가 살아있는지 확인한다.

### Step C1. 검증 대상 선택

```bash
# 크로스체크 대기 중인 이슈 조회 (label: resolved, state: open)
gh issue list --label "resolved" --state open --json number,title

# legacy 호환 (이전 모델로 처리된 이슈도 함께 잡으려면)
gh issue list --state all --search "(label:resolved state:open) OR (label:crosscheck-pending state:closed)" --json number,title

# 사용자가 번호를 지정한 경우
gh issue view <번호> --json number,title,body,labels,state
```

- 사용자가 번호를 지정하면 해당 이슈만
- 지정 없으면 `resolved` 라벨이 붙은 open 이슈 전체를 대상으로 한다

각 이슈에서 추출할 정보 (`gh issue view <번호>`):
- `## 재현` 섹션 — 브라우저에서 그대로 따라갈 단계
- `## 수정 방향` 섹션 — 무엇이 고쳐졌어야 하는지 (기대 동작 유추)
- `## 메타` 섹션의 컴포넌트 — 어느 페이지/기능에서 테스트해야 하는지

### Step C2. Fresh 세션 열기

기존 탐색 세션과 반드시 **다른 이름**을 사용한다 (상태 오염 방지):

> **환경 헬스체크 (pilot subagent로 호출된 경우 필수)**: 진입 직후 `curl -sf http://localhost:5173 > /dev/null && echo OK || echo NOPE`. NOPE이면 `RESULT: #N / blocked / dev_server_down`으로 즉시 종료.

**세션 이름 규약**:
- 사용자 직접 호출: `SESSION="crosscheck#$(openssl rand -hex 3)"` (random)
- Pilot subagent 호출: `pc<이슈번호>` (예: 이슈 #38 → `pc38`). 이슈 번호가 unique하므로 random 불필요. 이름을 짧게 유지하는 이유 — macOS Unix 소켓 경로 한도(104바이트)를 넘기면 socket bind 실패로 좀비 발생.
- 환경변수 `SESSION_NAME`이 있으면 그 값 그대로 사용:

```bash
SESSION="${SESSION_NAME:-crosscheck#$(openssl rand -hex 3)}"
playwright-cli -s=$SESSION --headed open http://localhost:5173
playwright-cli -s=$SESSION state-load .playwright-cli/state.json
# 로그인 확인 후 필요 시 수동 로그인 (Section 1 참조)
```

### Step C3. 재현 단계 실행 및 검증

이슈의 `**재현**:` 단계를 브라우저에서 그대로 따라가며 세 가지를 확인한다:

1. **버그가 사라졌는가** → 수정 성공 ✅
2. **버그가 여전히 발생하는가** → 회귀 🔴
3. **수정은 됐지만 새로운 부작용이 생겼는가** → 새 이슈 등록 (Step C3b)

```bash
# 결과 스크린샷
playwright-cli -s=$SESSION screenshot
cp .playwright-cli/page-TIMESTAMP.png test-results/issues/<번호>/crosscheck.png
```

### Step C3b. 크로스체크 중 새 이슈 발견 시

크로스체크 도중 **기존에 없던 버그**를 발견하면 섹션 7의 `gh issue create` 명령으로 즉시 등록한다.
**재현 방법이 매우 중요**하다 — 정확한 단계가 없으면 나중에 재현·수정이 불가능하다.
발견 출처는 `(크로스체크 중 발견)`으로 명시한다.

### Step C4. GitHub Issues 업데이트

**수정 확인 (패스)**:
```bash
# resolved 라벨 제거 + 이슈 닫기 (정상 최종)
gh issue edit <번호> --remove-label "resolved"
gh issue close <번호> --reason completed \
  --comment "✅ 크로스체크 완료 (YYYY-MM-DD) — 버그 재현 안 됨, 수정 확인됨"

# legacy 호환: closed+crosscheck-pending 이슈는 이렇게 정리
# gh issue edit <번호> --remove-label "crosscheck-pending" --add-label "crosscheck-passed"
```

**회귀 발견** (reopen 불필요 — 이슈는 이미 OPEN):
```bash
gh issue edit <번호> --remove-label "resolved" --add-label "regression"
gh issue comment <번호> --body "🔴 회귀 발견 (크로스체크 YYYY-MM-DD)

**관찰 결과**: 재현 단계 실행 시 관찰한 내용.
**solver 검증과의 차이**: 어떤 조건이 달랐는지 명시."

# legacy 이슈(closed+crosscheck-pending)에서 회귀가 발견된 경우만 reopen 필요:
# gh issue reopen <번호>
# gh issue edit <번호> --remove-label "crosscheck-pending" --add-label "regression"
```

### Step C5. 최종 요약 보고 + 세션 종료

모든 이슈 검증 완료 후 사용자에게 보고:

```
## 크로스 체크 결과 — YYYY-MM-DD

| 이슈  | 제목                         | 결과       | 비고                |
|-------|------------------------------|------------|---------------------|
| #48   | 활성 토글 피드백 없음        | ✅ 확인    | -                   |
| #49   | 설명 필드 에러 미표시        | 🔴 회귀    | 수정 코드 미반영     |
```

보고 후 **반드시 세션 close** (leak 방지):
```bash
playwright-cli -s=$SESSION close
```

### Pilot subagent 모드 — 정형 보고 (호출된 경우만)

`ai-driven-pilot`이 자율 사이클로 explorer를 subagent로 호출한 경우, **stdout 마지막 줄**에 정형 보고를 출력한다 (pilot이 파싱).

호출 모드 식별: subagent prompt에 "ai-driven-pilot이 자율 사이클로 호출함" 같은 문구가 있으면 이 모드로 진입.

#### 크로스체크 모드 — `RESULT:` 형식

크로스체크는 이슈당 한 건 처리이므로 RESULT 한 줄.

| 결과 | RESULT 라인 |
|------|-----------|
| 통과 (close 완료, regression 라벨 유지·resolved 제거) | `RESULT: #<N> / passed / closed` |
| 회귀 (regression 부착, 회귀 회차 K) | `RESULT: #<N> / regression / <K>` |
| 진행 불가 (재현 단계 자체가 모호 등) | `RESULT: #<N> / blocked / <사유>` |

`<N>`은 검증한 이슈 번호. 회귀 회차 `<K>`: 이슈 코멘트의 "🔴 회귀 발견" 카운트 + 1.

#### 탐색 모드 — `EXPLORER_DONE:` 형식

탐색은 한 세션에서 0~다수 건 발견하므로 발견 번호 목록을 한 줄로 보고. RESULT 형식이 아닌 점에 주의 — pilot이 다른 파싱 분기를 탐.

| 결과 | EXPLORER_DONE 라인 |
|------|-----------|
| 1건 이상 발견 (모두 보드에 추가됨) | `EXPLORER_DONE: <N>,<M>,...` |
| 신규 이슈 없음 (탐색은 했으나 깨끗함) | `EXPLORER_DONE: none` |
| 진행 불가 (브라우저 기동 실패 등) | `EXPLORER_DONE: blocked: <사유>` |

탐색 모드도 발견된 이슈는 Section 7의 `gh issue create` 직후 `add-to-board.sh`로 보드에 자동 추가되므로, pilot이 다음 사이클 분류 시 신규 솔버 큐에서 즉시 잡을 수 있다.

---

## 주의사항

- GitHub Issues(`gh issue create/comment/edit`)와 `test-results/` 폴더에만 변경한다 (소스코드 수정 금지)
- 스냅샷 파일은 `/tmp/`에 임시 저장 (프로젝트에 커밋하지 않음)
- 스크린샷은 `test-results/exploratory/<YYYY-MM-DDTHH-MM>/screenshots/` 또는 `test-results/issues/<번호>/`에 저장
- 미구현/숨김 처리된 기능은 CLAUDE.md나 소스코드 주석으로 먼저 확인 후 버그 제외 판단
- playwright-cli 명령 막히면 → `references/pitfalls.md` 참고
- 크로스 체크 모드에서 소스 코드를 읽어 "이건 고쳤을 것 같다"고 판단하지 말 것 — 반드시 브라우저에서 직접 재현 단계를 실행해야 한다
