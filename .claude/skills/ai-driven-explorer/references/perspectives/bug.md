# Bug 관점 — 기능적 결함 탐색 (default)

기본 탐색에서 사용하는 **기능적 결함** 발견 관점. 매트릭스 파일: `.coverage-matrix-bug.md`.

> 출처: OWASP Top 10 (2021) · OWASP ASVS 4.0 Level 1 · Heuristic Test Strategy Model (James Bach) · PortSwigger Web Security Academy. 권위 출처는 문서 끝 §5 참조.

이 perspective는 **사람이 5분 안에 발견할 수 있는 기능적 결함의 신호**를 잡는다. 정량/전수/페이로드 매트릭스가 필요하면 별도 자동화 트랙으로 분리한다 (§6 참조).

## 1. 우선순위 (높은 것부터)

OWASP/HTSM 매핑으로 8개 카테고리:

| # | 카테고리 | 표준 매핑 | 핵심 |
|---|---|---|---|
| 1 | **Critical Path** | HTSM Capability/Reliability | 앱이 켜지고 핵심 사용자 여정이 완수되는가 |
| 2 | **Business Logic** | HTSM Capability | 기능이 명세대로 동작하는가 |
| 3 | **Access Control** | OWASP A01 (1위) | 인가 우회/IDOR — 권한 없는 사용자가 접근 가능한가 |
| 4 | **Input Validation** | OWASP ASVS V5 | 경계/타입/주입 — UI에서 거르는가 |
| 5 | **Error Handling** | HTSM Reliability | 예외가 사용자에게 제대로 전달되는가 (스택트레이스 노출 X) |
| 6 | **Async / Race** | CWE-367 TOCTOU | 중복 클릭·동시성·stale 응답 |
| 7 | **State Management** | HTSM Reliability | 잔류 상태·stale data·SPA 라우트 전환 |
| 8 | **Security 기타** | OWASP ASVS L1 | CSRF·세션·저장소 노출 (보안 심층은 별도 펜테스트) |

> **A11y 간단 점검**은 별도 perspective(`a11y.md`)로 분리되어 있으므로, bug 패스에서는 **fail-loudly한 경우**(키보드 트랩, 포커스 상실로 동작 불가)만 부수적으로 본다.

## 2. 카테고리별 시나리오 체크리스트

### A. Critical Path (앱 부팅 / 핵심 여정)
- [ ] 첫 진입 시 401/403/500 없이 메인 페이지 렌더링되는가
- [ ] 새로고침(F5) 후에도 상태 유지되는가 (SPA 라우터 직접 진입 포함)
- [ ] 로그인 → 핵심 기능 1개 → 로그아웃이 끊김 없이 완수되는가
- [ ] 뒤로가기/앞으로가기로 핵심 화면 진입 시 깨지지 않는가
- [ ] 좁은 노트북 폭(1280px)에서도 핵심 버튼이 화면 밖으로 잘리지 않는가 (※ 본 프로젝트는 데스크탑 전용 — 모바일 폭 검증 안 함)

### B. Business Logic / Capability (HTSM)
- [ ] 빈 폼 submit 시 명확한 검증 메시지가 뜨는가 (조용한 실패 금지)
- [ ] 필수 필드 누락 시 어느 필드인지 시각적으로 표시되는가
- [ ] CRUD 4개 동작이 모두 가능한가 (Read만 되고 Update/Delete가 막혀있지 않은가)
- [ ] 검색/필터 빈 결과 상태가 의도된 메시지로 표시되는가 (Empty)
- [ ] 1건/0건/N건(페이징 경계)에서 모두 정상 동작하는가
- [ ] 정렬 토글이 양방향(asc/desc) 모두 동작하는가
- [ ] 동일 작업을 다른 진입점(메뉴/단축키/우클릭)으로 수행해도 결과가 같은가

### C. Access Control / IDOR (OWASP A01)
- [ ] 로그아웃 상태에서 보호 URL 직접 입력 시 로그인 화면으로 리다이렉트되는가
- [ ] URL의 리소스 ID(`/items/123`)를 다른 사용자 ID로 변경 시 403/404가 떨어지는가
- [ ] 일반 사용자가 admin 메뉴 URL을 직접 입력 시 차단되는가 (메뉴만 숨기고 라우트 노출 금지)
- [ ] 다른 사용자가 만든 리소스의 수정/삭제 버튼이 노출되지 않는가
- [ ] DevTools에서 disabled 버튼을 enable로 바꿔도 서버가 거부하는가
- [ ] 권한 다운그레이드 후(역할 변경) 기존 세션이 새 권한을 즉시 반영하는가
- [ ] 페이지네이션/검색 파라미터 조작으로 본인 외 데이터가 노출되지 않는가

### D. Input Validation (OWASP ASVS V5)
- [ ] 텍스트 필드에 매우 긴 입력(10,000+ chars) 시 UI가 멈추거나 잘려서 깨지지 않는가
- [ ] 숫자 필드에 음수/0/소수/지수표기(1e10)/-Infinity 입력 처리
- [ ] 날짜 필드에 과거(1900)/미래(2099)/잘못된 형식 입력 처리
- [ ] 이메일 필드에 `<script>`, `'; DROP--`, `${7*7}`, 한글 도메인 등 입력 시 적절히 escape되는가
- [ ] 파일 업로드: 빈 파일/큰 파일/잘못된 확장자(.exe) 거부되는가
- [ ] 드롭다운/라디오: 클라이언트에서 값 조작(DevTools)해도 서버가 거부하는가
- [ ] 복사-붙여넣기로 trailing whitespace, 줄바꿈, 보이지 않는 유니코드(zero-width) 입력 처리
- [ ] 검색창에 SQL/NoSQL 메타문자(`%`, `_`, `{$gt}`)가 그대로 쿼리되지 않는가

### E. Error Handling
- [ ] 서버 500/503 응답 시 사용자에게 의미 있는 메시지가 뜨는가 (스택트레이스 노출 금지)
- [ ] 네트워크 끊김(DevTools offline) 시 UI가 멈추지 않고 재시도 안내가 나오는가
- [ ] 토큰 만료 시 자동으로 재로그인 또는 토큰 갱신되는가
- [ ] 중복 제출/취소된 요청에 대한 응답이 와도 토스트가 중복으로 뜨지 않는가
- [ ] 콘솔 에러(빨간색)가 정상 사용 흐름 중 발생하지 않는가
- [ ] 404 페이지가 앱 내 일관된 디자인으로 표시되는가

### F. Async / Race Condition (PortSwigger 기준)
- [ ] **저장 버튼 더블 클릭** 시 1건만 생성되는가 (debounce/disable)
- [ ] 동일 폼을 빠르게 두 번 submit 시 중복 데이터/이중 결제 발생하지 않는가
- [ ] 검색어를 빠르게 타이핑/지우기 반복 시 마지막 입력의 결과만 표시되는가
- [ ] 로딩 중에 다른 탭/필터로 이동 후 돌아왔을 때 stale 응답이 덮어쓰지 않는가
- [ ] 쿠폰/할인 코드를 동시에 두 번 적용 시도 시 1회만 적용되는가
- [ ] 잔액/재고 등 한정 자원을 두 탭에서 동시 차감 시도 시 음수가 되지 않는가
- [ ] 파일 업로드 진행 중 페이지 이탈 시 경고가 뜨는가

### G. State Management / Stale Data
- [ ] 항목 추가/수정/삭제 후 목록이 즉시 갱신되는가 (수동 새로고침 불필요)
- [ ] 탭 전환 후 돌아왔을 때 이전 폼 입력값이 의도대로 유지/초기화되는가
- [ ] 필터를 적용한 채 다른 페이지로 이동 후 돌아오면 필터가 의도대로 처리되는가
- [ ] 로그아웃 후 재로그인 시 이전 사용자 데이터 잔재가 보이지 않는가
- [ ] 두 탭에서 같은 리소스를 수정하면 충돌 감지 또는 마지막 쓰기 우선이 명확한가
- [ ] 무한 스크롤/페이징 후 항목 삭제 시 인덱스가 깨지지 않는가
- [ ] 모달 닫기 → 재오픈 시 이전 입력값/에러 상태가 적절히 리셋되는가
- [ ] 페이지 장시간 방치(30분+) 후 동작 시 토큰/세션 처리가 정상인가

### H. Security 기타 (ASVS Level 1 UI 검증)
- [ ] 로그아웃 후 뒤로가기로 보호 페이지가 캐시에서 노출되지 않는가
- [ ] localStorage/sessionStorage에 민감정보(JWT, PII)가 저장되지 않는가
- [ ] 비밀번호 필드가 type=password이고 자동완성 정책이 의도대로인가
- [ ] HTTPS 강제, mixed content 경고 없는가
- [ ] CSP 헤더 위반 콘솔 메시지가 정상 사용 중 발생하지 않는가
- [ ] 외부 링크 `target="_blank"`에 `rel="noopener noreferrer"` 적용
- [ ] 비밀번호 재설정/이메일 변경 등 민감 작업이 현재 비밀번호 재확인을 요구하는가

## 3. 시나리오 매트릭스 예시

```
| 시나리오                            | 검증할 것                          | 상태 |
|------------------------------------|-----------------------------------|------|
| 저장 > 이름 빈값/공백만             | 저장 버튼 비활성 여부              | ⬜  |
| 저장 > 이름 특수문자(<>'"&)         | 저장·표시 깨짐 여부, XSS 여부      | ⬜  |
| 저장 > 200자 초과                  | 클라이언트/서버 검증 존재 여부     | ⬜  |
| 저장 > 버튼 중복 클릭               | 중복 요청 방지 (disabled/Loader)  | ⬜  |
| 권한 > 읽기전용 사용자 삭제          | 403 응답 + UI 차단                | ⬜  |
| URL 조작 > /items/{타인 ID}        | 403/404 — 본인 외 노출 안 됨      | ⬜  |
```

## 4. 보안 이슈 등록 시 추가 라벨

`bug,severity:critical,security` — pilot 자율 close 차단 → 사람 검토 라우팅.

## 5. 권위 있는 출처

- **OWASP Top 10 2021** (A01 Broken Access Control이 1위, 비중 94%): https://owasp.org/Top10/2021/
- **OWASP ASVS 4.0** (Level 1 = 펜테스트 가능 수준): https://owasp.org/www-project-application-security-verification-standard/
- **OWASP Authorization Testing Cheat Sheet**: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Testing_Automation_Cheat_Sheet.html
- **PortSwigger Web Security Academy — Race Conditions**: https://portswigger.net/web-security/race-conditions
- **CWE-367 TOCTOU Race Condition**: https://cwe.mitre.org/data/definitions/367.html
- **Heuristic Test Strategy Model (James Bach, v5.2)**: https://www.developsense.com/resource/htsm.pdf

## 6. 의도적으로 제외 (탐색 단계 부적합)

탐색은 **사람이 한두 번 시도해서 발견할 수 있는 것**에 집중. 다음은 자동화/전용 도구 영역:

| 제외 항목 | 이유 | 어디로 가야 하나 |
|---|---|---|
| SQL/NoSQL Injection 실제 페이로드 검증 | 탐색에선 메타문자 escape만 확인 | sqlmap, Burp Scanner |
| XSS payload 매트릭스 전수 | 탐색에선 1~2개만 | DAST, XSStrike |
| CSRF 토큰 절도/체이닝 | 별도 PoC 페이지 필요 | 보안 점검 전용 |
| Race condition µs 단위 동시성 | 사람 손은 ms까지 | Burp Turbo Intruder |
| 부하/p99 latency | 정확한 수치는 별도 | perf perspective, k6, Lighthouse |
| 전수 회귀 (모든 경로) | 탐색은 휴리스틱 위주 | Playwright TC 자동화 |
| WCAG 전체 매트릭스 | 본격 a11y는 별도 | a11y perspective, axe-core |
| 메모리 누수 정량 | heap snapshot 분석은 별도 | perf perspective |

**원칙**: 정량/전수/페이로드 매트릭스는 별도 패스로 분리하라.
