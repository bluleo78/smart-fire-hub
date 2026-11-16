# WD-774 리포트 양식 섹션 지시 — 데이터에 없는 값 요구 안내

이슈: https://genia-works.iacloud.kr/projects/WD/issues/774
워크트리: `.claude/worktrees/WD-774` (브랜치 `feat/wd-774`, base origin/main 18426c040)
배경: WD-767 이 리포트 수치를 조회 결과와 대조해 출처 없는 수치를 경고한다. 양식 섹션 지시가 데이터에 없는 값(목표·전망·달성률)을 요구하면 모델이 빈칸을 숫자로 채운다 — 입력하는 곳에서 미리 알린다.

## 공통 규칙 (하위 에이전트 브리프에 그대로)
- 셸 명령은 단순하게: 한 줄 셸 함수 정의(`f(){ …; }`), `${…:-{…}}` 같은 중괄호 확장, `;`·`&&` 로 길게 이은 한 줄 금지. 복잡하면 스크립트 파일로.
- 한국어 주석 필수(무엇을·왜), 이슈 키 WD-774.
- 커밋·push·`git add -A`·`git stash` 금지. 저장소 전체 포매터 금지(바꾼 파일만 eslint --fix — 저장소 루트에서 `npx eslint --config apps/firehub-web/eslint.config.js --fix <파일>`).
- `pnpm dev`·`pnpm db:*`·`docker compose down` 금지, dev 포트 5xxx 건드리지 않음, 공용 테스트 락 파일 지우지 않음. E2E 는 `PW_PORT=6774`.

## Task 1 — 판별 도우미 + 단위 시험
- 새 파일 `apps/firehub-web/src/lib/report-instruction-hints.ts`:
  - `REPORT_NUMBER_SOURCE_GUIDE` 상수 = 「리포트의 수치는 조회(SQL) 결과에 있는 값만 쓰입니다. 목표·전망·비율처럼 데이터에 없는 값을 요구하면 「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다. 합계·증감률은 SQL 에서 계산해 주세요.」(이슈 문구. 이슈의 『』 는 화면 관례대로 「」).
  - `findUnsupportedValueWords(instruction: string): string[]` — 지시에 들어 있는 주의 낱말을 처음 나온 순서로, 중복 없이 돌려준다. 낱말 목록 `목표`, `전망`, `달성률`, `예측`(전망과 같은 갈래). 빈 값·공백이면 [].
- 새 시험 `report-instruction-hints.test.ts`(vitest): 낱말 없음 → [], 하나·여럿(순서·중복 제거), 「목표 대비 달성률」→ [목표, 달성률], 빈 문자열, 상수 문구가 이슈 핵심어(조회(SQL) 결과, 데이터 없음, 출처 미확인, SQL 에서 계산)를 담는지.

## Task 2 — 섹션 속성 편집기 안내 + E2E
- `apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx` 「AI 지시 (Instruction)」 블록(정적·구분선 섹션 아님):
  - 기존 도움말 아래에 `REPORT_NUMBER_SOURCE_GUIDE` 를 늘 보인다(id `${baseId}-instruction-guide`, text-xs text-muted-foreground). Textarea `aria-describedby` 에 기존 help 와 guide 둘 다.
  - `findUnsupportedValueWords(section.instruction ?? '')` 가 비지 않으면 그 아래에 주의 문구(경고 색 토큰 — 디자인 시스템 docs/design-system 의 warning 계열, 아이콘 `AlertTriangle` aria-hidden): 「지시에 「목표」·「전망」 같은 값이 있습니다 — 조회 결과에 그 값이 없으면 리포트에 쓰이지 않습니다.」 (낱말은 찾은 것을 「」로 감싸 `·` 로 잇는다). `data-testid="instruction-unsupported-hint"`, `role="status"`(읽기 프로그램이 입력 중 알림 — aria-live polite). 저장은 막지 않는다(아무 폼 검증과도 엮지 않음). id 를 aria-describedby 에 더한다(보일 때만).
- E2E: `apps/firehub-web/e2e/pages/ai-insights/template-detail.spec.ts`(또는 같은 폴더의 새 spec `template-instruction-hint.spec.ts` — 기존 fixture 재사용)에서
  1. 새 양식(`/ai-insights/templates/new`)에서 섹션을 추가·선택하면 「AI 지시」 아래에 안내 문구가 보인다.
  2. 지시에 「목표 대비 달성률」을 입력하면 주의 문구에 「목표」·「달성률」이 보이고, 「요약」처럼 바꾸면 사라진다.
  3. 주의 문구가 떠 있어도 저장(POST/PUT) 요청이 나가고 본문 섹션 instruction 에 입력값이 그대로 실린다(route 캡처).
  4. 정적(고정 텍스트) 섹션에는 안내가 없다.
  - 기존 템플릿 spec 의 셀렉터·fixture(`e2e/fixtures/ai-insight.fixture.ts`)를 먼저 읽고 재사용.
- 확인: `pnpm -C apps/firehub-web typecheck`, `npx tsc -p apps/firehub-web/tsconfig.e2e.json --noEmit`, vitest 대상, E2E 대상 spec(템플릿 4개).

## Review Focus
- 지시가 undefined·빈 값일 때 주의 문구가 없고 오류가 없는지.
- 낱말이 다른 낱말 안에 들어간 경우(「목표치」, 「전망대」) — 포함 검사라 「목표치」도 잡힘(의도), 「전망대」 오탐은 감수(저장을 막지 않는 가벼운 주의).
- 좁은 화면(390px)에서 문구가 넘치지 않는지(template-section-mobile.spec 영향).
- 접근성: aria-describedby 연결, role=status 가 키 입력마다 과하게 읽히지 않는지(문구가 바뀔 때만).

## 내가 정한 것
- 사람에게 묻지 않음: 이슈가 문구·위치·「저장은 막지 않음」을 정했고 사람 결정(「추천대로」)이 있다.
- 주의 낱말은 이슈의 목표·전망·달성률 + 같은 갈래의 예측. 「비율」은 SQL 로 계산 가능한 값도 많아 주의 낱말에서 뺌(안내 문구에는 남김).
- 안내는 섹션 속성 편집기(새 양식·편집 공용) 한 곳. JSON 탭 직접 편집에는 두지 않음(구조 편집 전문가 경로 — 빌더로 돌아오면 같은 안내가 보임).
