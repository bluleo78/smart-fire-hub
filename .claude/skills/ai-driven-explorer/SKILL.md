---
name: ai-driven-explorer
description: >
  AI 주도 탐색적 테스트 스킬. playwright-cli로 실제 브라우저를 headed 모드로 열어
  기능/페이지를 직접 탐색하며 버그를 발견한다.
  사용자가 "playwright로 테스트해줘", "UI 탐색해줘", "버그 찾아줘", "기능 검증해줘",
  "headed 모드로 테스트", "탐색적 테스트", "exploratory test" 등을 요청할 때 반드시 이 스킬을 사용한다.
  TC 기반 자동화 테스트(ai-driven-tc-runner)가 아닌 탐색적/인간적 관점의 자유로운 테스트에 특화되어 있다.
---

# Playwright 탐색적 UI 테스트

사람이 브라우저를 직접 쓰듯이 테스트한다. 시나리오를 따르는 것이 아니라
"이 기능을 실제로 써보면 어떻게 되나?"를 탐색하는 것이 목표다.
버그는 예상과 다른 동작, 시각적 이상, UX 혼란, 데이터 불일치 등을 모두 포함한다.

> **함정 목록**: playwright-cli 사용 중 막히면 `references/pitfalls.md`를 읽는다.

## 1. 브라우저 실행

> **도구 선택**: 브라우저 자동화는 반드시 `playwright-cli` CLI를 사용한다.
> Playwright MCP(`mcp__plugin_playwright_playwright__browser_*`) 도구는 사용하지 않는다.

세션 이름은 매 실행마다 랜덤으로 생성해 기존 세션과 충돌을 방지한다:
```bash
SESSION="explorer#$(openssl rand -hex 3)"
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

### 컨텍스트 컴팩션 후 세션 재개

이전 대화가 압축되어 재시작된 경우, 다음 순서로 상태를 복구한다:

```bash
# 1) playwright-cli 세션 생존 확인
playwright-cli -s=<SESSION명> snapshot --depth=2 2>&1 | head -5
# → TimeoutError 또는 오류 시 세션 닫고 재시작:
# playwright-cli -s=<SESSION명> close 2>/dev/null; SESSION="explorer#$(openssl rand -hex 3)"

# 2) 커버리지 매트릭스에서 마지막 상태 파악
grep "🔴\|✅\|⬜" test-results/exploratory/.coverage-matrix.md | wc -l
grep "⬜" test-results/exploratory/.coverage-matrix.md | head -10

# 3) 마지막 버그 번호 확인
grep "^### \[#" docs/ISSUES.md | tail -3
```

재개 후에는 ⬜(미시작) 항목부터 이어서 진행한다.

### 세션 시작 결정 흐름

```
세션 시작
├── test-results/.component-tree.md 있음?
│   ├── YES → 로드. 테스트 대상 섹션이 트리에 있나?
│   │         ├── YES (상세 내용 있음) → 재사용
│   │         └── NO (⬜ 미탐색 표시 또는 섹션 자체 없음)
│   │               → 소스 읽어서 해당 섹션 상세 작성 후 진행
│   └── NO  → 앱 전체 뼈대 신규 작성 (메뉴 단위 섹션 + ⬜ 미탐색 표시)
│             그 후 테스트 대상 섹션만 상세 작성
│
├── test-results/.coverage-matrix.md 있음?
│   ├── YES → 로드. ⬜(미시작) 항목만 이번 세션 대상
│   └── NO  → 트리의 해당 섹션에서 파생해서 신규 생성
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

트리에서 파생. **`test-results/exploratory/.coverage-matrix.md`에 저장**하고 세션을 이어받는다.

**매트릭스는 반드시 시나리오 레벨로 작성한다.** "버튼이 존재하는가"가 아니라 "이 입력을 주면 무슨 일이 벌어지는가"를 열거해야 한다.

❌ 잘못된 예 (컴포넌트 레벨):
```
| DataTab > 버튼    | 행추가/내보내기/삭제 | ⬜ |
```

✅ 올바른 예 (시나리오 레벨):
```
| 저장 > 이름 빈값/공백만      | 저장 버튼 비활성 여부              | ⬜ |
| 저장 > 이름 특수문자(<>'"&)  | 저장·표시 깨짐 여부, XSS 여부      | ⬜ |
| 저장 > 200자 초과            | 클라이언트/서버 검증 존재 여부     | ⬜ |
| 저장 > 버튼 중복 클릭        | 중복 요청 방지 (disabled/Loader)  | ⬜ |
```

시나리오 뽑는 질문:
- 빈 값 / 최대 길이 / 특수문자를 넣으면?
- 오류 시 사용자에게 명확한 피드백이 오는가?
- 빠르게 두 번 클릭하면 중복 요청이 발생하는가?
- 비동기 작업 중 버튼 상태는?
- 필터/탭 전환 시 이전 상태가 남아 있지는 않은가?

매트릭스에 새 항목을 추가할 때는 **반드시 해당 섹션 테이블 안에 삽입**한다.
파일 하단에 append하면 섹션 구조가 깨져 추적이 어려워진다.

상태: ⬜ 미시작 → 🔄 진행 중 → ✅ 완료 → 🔴 버그 발견

### Step 4: 사전 제외 확인

```bash
grep -ri "TODO\|hidden\|disabled\|coming soon\|미구현" src/ | head -10
cat CLAUDE.md
cat docs/ISSUES.md  # 이미 알려진 이슈 제외
```

### 탐색 범위 우선순위

우선순위 기준 (높은 것부터):
1. **Security** — SQL/DML 실행 허용 여부, 크로스 스키마 접근, IDOR, 권한 우회, 민감 데이터 노출
2. **Error / Edge path** — 잘못된 입력, 경계값, 특수문자, 빈 상태
3. **Async / Race condition** — 중복 클릭, 실행 중 상태, 로딩 피드백
4. **Critical path** — 핵심 CRUD, 저장/삭제 플로우
5. **State spillover** — 탭/필터 전환 시 이전 상태 잔류 여부
6. **Accessibility** — 키보드 탐색, aria 속성, 콘솔 경고

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

## 5. 탐색 명령어

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
cp .playwright-cli/page-TIMESTAMP.png test-results/exploratory/<기능명>/<timestamp>/screenshots/<설명>.png
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

## 6. 탐색 패턴

각 기능을 테스트할 때 이 관점으로 확인한다:

1. **정상 동작** — 기본 기능이 의도대로 작동하는가?
2. **엣지 케이스** — 빈 값, 긴 입력, 특수문자는?
3. **상태 전환** — 열기/닫기, 활성/비활성 전환이 깔끔한가?
4. **오류 처리** — 잘못된 입력, 서버 오류 시 사용자에게 명확한 피드백이 오는가?
5. **시각적 일관성** — 로딩 상태, 완료 상태 표시가 정확한가?
6. **UX 명확성** — 동일한 레이블의 버튼이 여러 개 있지는 않은가?

스냅샷을 자주 찍어서 진행 상황을 기록한다. 버그 발견 시 바로 스크린샷.

## 7. 버그 판정 기준

- **Critical**: 데이터 유실, 기능 완전 불가, 보안 이슈
- **Major**: 핵심 기능 일부 오동작, 데이터 오류
- **Minor**: 비핵심 기능 오동작, 처리되지 않는 예외
- **UX**: 혼란스러운 UI, 잘못된 레이블, 시각적 이상

## 8. 버그 문서화

발견된 버그는 `docs/ISSUES.md`에 즉시 추가:

```markdown
### [#번호] 제목
- **심각도**: Critical / Major / Minor / UX
- **컴포넌트**: `파일경로:라인번호`
- **발견**: YYYY-MM-DD (playwright 탐색 테스트)
- **상태**: 🔴 미처리

**현상**: 한 문장으로 설명.

**재현**:
1. 단계별 재현 절차

**원인**: 소스코드를 읽어서 근본 원인까지 파악.

**수정 방향**: 구체적인 코드 수준 수정 방향.
```

## 9. 최종 보고서

탐색 완료 후 `test-results/exploratory/<기능명>/<YYYY-MM-DDTHH:MM>/report.md`에 작성.

```
test-results/
├── e2e/                  ← Playwright E2E 자동 생성 (outputDir)
├── tc/                   ← TC 실행 결과 (ai-driven-tc-runner)
│   └── <suite-name>/
└── exploratory/          ← 탐색적 테스트 (ai-driven-explorer)
    ├── .component-tree.md    ← 앱 전체 트리 (세션 간 재사용)
    ├── .coverage-matrix.md   ← 시나리오 추적표 (세션 간 이어받기)
    └── <기능명>/<timestamp>/
        ├── report.md
        └── screenshots/
```

보고서 작성 후 핵심 버그만 요약해서 사용자에게 보고한다.

## 주의사항

- `docs/ISSUES.md`와 `test-results/` 폴더에만 파일을 생성한다 (소스코드 수정 금지)
- 스냅샷 파일은 `/tmp/`에 임시 저장 (프로젝트에 커밋하지 않음)
- 스크린샷은 `snapshots/` 폴더에 저장
- 미구현/숨김 처리된 기능은 CLAUDE.md나 소스코드 주석으로 먼저 확인 후 버그 제외 판단
- playwright-cli 명령 막히면 → `references/pitfalls.md` 참고
