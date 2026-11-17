# WD-775 리포트 수치 출처 대조 — 오탐·누락·성능 다듬기

이슈: https://genia-works.iacloud.kr/projects/WD/issues/775
워크트리: `.claude/worktrees/WD-775` (브랜치 `fix/wd-775`, base origin/main 18426c040)
대상: `apps/firehub-ai-agent/src/agent/report-number-check.ts`, `src/routes/proactive.ts`(+ 각 `*.test.ts`)
배경: WD-767 이 리포트 수치를 그 실행의 데이터 조회 결과와 대조해 출처 없는 수치를 경고한다(실행 상세 배너·알림 요약 첫 줄). 검토 리뷰가 오탐 6·누락 2·성능 1 을 찾았다. 알림에 잘못된 경고가 나가면 기능을 믿지 않게 되므로 다듬는다.

## 공통 규칙 (하위 에이전트 브리프에 그대로)
- 셸 명령은 단순하게: 한 줄 셸 함수 정의(`f(){ …; }`), `${…:-{…}}` 같은 중괄호 확장, `;`·`&&` 로 길게 이은 한 줄 금지. 복잡하면 스크립트 파일로.
- 한국어 주석 필수(무엇을·왜), 이슈 키 WD-775.
- 커밋·push·`git add -A`·`git stash` 금지. 저장소 전체 포매터 금지.
- `pnpm dev`·`pnpm db:*`·`docker compose down` 금지. 공용 테스트 락 파일 지우지 않음.
- TDD: 각 항목마다 이슈 예시로 실패하는 단위 시험을 먼저 쓰고, 고친 뒤 통과를 본다.

## Task 1 — 오탐 (report-number-check.ts + proactive.ts)
1. `DATA_QUERY_TOOLS` 에 `run_saved_query`·`get_chart_data`·`get_row_count` 를 더한다(도구 이름은 `src/mcp/tools/analytics-tools.ts`·`data-tools.ts` 에서 확인). 시험: `isDataQueryTool('mcp__firehub__get_chart_data')` 등 true.
2. 허용 텍스트(양식·프롬프트·context)를 글자가 아니라 **값으로도** 비교한다. `checkReportNumbers` 입력은 그대로 두되, 안에서 허용 텍스트의 숫자를 `collectSourceNumbers` 로 별도 Set(allowedNumbers)에 모으고 `matchesSource` 에서 출처 Set 과 함께 본다(글자 비교 allowed 는 유지 — 「제3조」 같은 표기 그대로 허용). 시험: context 의 `1234` → 리포트 「1,234개」 경고 없음.
   - 주의: 허용 숫자는 「출처 숫자가 하나도 없으면 checked=false」 판정에 섞지 않는다(출처 판정은 sourceNumbers 만).
3. `proactive.ts` 의 `allowedTexts` 에서 이미 문자열인 값을 다시 `JSON.stringify` 로 감싸지 않게 한다 — template·context 는 객체라 직렬화가 필요하지만, 문자열 값 안 줄바꿈이 글자 `\n` 이 되어 줄 첫머리 숫자(`\n5,000건`)가 토큰 앞 조건에 걸려 `000` 만 잡힌다. 방법(구현자가 고름): 객체의 문자열 값들을 재귀로 모아(`collectStrings`) 그대로 넘기거나, 직렬화 뒤 `\\n`·`\\t`·`\\r` 를 실제 공백으로 되돌림. 시험: context 문자열 「…\n5,000건…」 이 있으면 리포트 「5,000건」 경고 없음(라우트 수준 또는 도우미 단위 시험).
4. `htmlToText` 가 **인라인 태그**(strong·b·em·i·span·a·u·mark·small·sub·sup·code 등)는 공백 없이 지우고, 블록 태그만 공백으로 바꾼다. 시험: `<strong>3.5</strong>억` → 「3.5억」 으로 읽혀 출처 3.5e8 과 맞음. 블록 태그(`</td><td>`) 사이는 여전히 떨어져야 한다(「12</td><td>34」 가 1234 가 되면 안 됨).
5. 「조」를 단위로 읽지 않는 경우: 앞에 「제」가 붙은 「제3조」, 그리고 「2조 편성」처럼 뒤에 공백+명사가 오는 경우는 어떻게 가를지 — 최소 범위: `제\d+조` 는 조문 번호라 마스크(가림)한다. 「2조 편성」은 조(兆)와 구분이 애매하므로 `조` 뒤에 「편성·조합·로·가」 등 조(組) 쓰임이 흔한 낱말이 오면 단위가 아님 — 구현자는 예시(「제3조」·「2조 편성」)가 경고되지 않고 「3조 원」「1.2조」는 여전히 조 단위로 읽히는 규칙을 고르고 근거를 주석에 남긴다.
6. 목차 번호: 줄 첫머리(앞에 `#`·공백만)의 `\d+.` + 공백 패턴(「11. 권고사항」·「## 12. 부록」)은 번호로 보고 건너뛴다. 「11.5%」·문장 안 「11. 」은 해당 없음(줄 첫머리만). 시험 둘 다.

## Task 2 — 누락
7. 짧은 날짜 가리기가 분수·범위를 가리지 않게: 뒤에 개수 단위(건·개·명·곳·회·종·가구·대·%)가 바로 오는 「10/20건」·「3-15건」은 날짜가 아니다. 시험: 출처에 없는 「10/20건」·「3-15건」 → 경고됨(20·15 등 토큰 중 출처 없는 것이 잡힘). 기존 「11/16 기준」 같은 날짜 가리기 시험은 그대로 통과.
8. `createToolNameResolver().resolve()`: SDK·CLI 는 tool_result 의 `toolName` 자리에 tool_use_id 를 싣는다. 그 id 가 맵에 없으면 직전 도구 이름이 아니라 '' (출처 아님)이어야 한다. 직전 도구 이름 폴백은 「id 도 이름 자리 값도 전혀 없는」 공급자에만. 각 공급자(`src/agent/providers/` 등 — grep `type: 'tool_result'`)의 tool_result 모양을 확인해 실제 이름이 실리는 공급자(opencode 등)를 깨지 않게 한다. 시험: 맵에 없는 id(`toolu_x`) → '', id 없는 이벤트 → 직전 이름.

## Task 3 — 성능
9. `matchesSource` 가 토큰마다 출처 전체를 반올림해 훑지 않게: (multiplier, decimals, percent) 조합마다 반올림한 출처 값 Set 을 한 번 만들어(지연 생성·캐시) 찾는다. 퍼센트는 s 와 s*100 둘 다 넣는다. 반올림 결과 비교는 정수 키(예: `Math.round(x*10^k)`)로 해 부동소수 비교를 피한다. 시험: 출처 5만 개 + 토큰 수백 개가 1초 안(넉넉히) — 기존 시험 결과는 그대로.

## 확인
- `pnpm -C apps/firehub-ai-agent test -- src/agent/report-number-check.test.ts src/routes/proactive` (관련 라우트 시험 파일), `pnpm -C apps/firehub-ai-agent typecheck`, eslint(바꾼 파일만).

## Review Focus
- 허용 숫자 Set 이 checked 판정(출처 없음 → false)에 섞이지 않는가.
- 인라인 태그 제거로 셀·블록 사이 숫자가 붙지 않는가.
- 「조」 규칙이 실제 조 단위 금액(「3조 원」「1.2조」「3조5천억」)을 깨지 않는가.
- 목차 번호 건너뛰기가 문장 중 숫자·소수·퍼센트를 건너뛰지 않는가.
- 분수·범위 예외가 「11/16 기준」·「3-15」(단위 없음) 날짜 가리기를 깨지 않는가.
- resolver 변경이 opencode 등 이름을 싣는 공급자의 출처 수집을 끊지 않는가.
- 성능 캐시가 percent·multiplier·decimals 조합을 섞지 않는가.

## 내가 정한 것
- 사람에게 묻지 않음: 화면 변화 없는 규칙 다듬기이고 이슈가 고칠 것·완료 기준을 정했다.
- 허용 텍스트는 글자 비교를 남기고 값 비교를 더한다(「제3조」 같은 표기가 양식에 있으면 그대로 허용).
- 「조」 판정은 오탐을 줄이는 쪽으로 좁게(조문 번호·흔한 組 쓰임만) — 놓쳐도 경고 하나 덜 나는 것이라 감수.
