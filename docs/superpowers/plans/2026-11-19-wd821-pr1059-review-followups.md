# WD-821 PR #1059 반려 반영 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 차트 빌더 미리보기 memo(`MemoChartRenderer`)가 저장 대화상자 전후로 미리보기를 유지하고, 설정 변경에는 갱신됨을 E2E 로 고정한다. 주석에 후속 이슈 WD-823 을 남긴다.

**Architecture:** 제품 코드는 이미 브랜치에 있다(`ChartRenderer.tsx` 의 `MemoChartRenderer`, `ChartBuilderPage.tsx` 미리보기). 이 계획은 E2E 한 벌과 주석 한 줄만 더한다.

**Tech Stack:** React 19, Playwright(API 모킹 — `page.route`), recharts.

**Spec:** `docs/superpowers/specs/2026-11-19-wd821-pr1059-review-followups-design.md`

## Global Constraints
- 주석·테스트 이름은 한국어(저장소 규칙 — 무엇을·왜).
- E2E 는 `PW_PORT=6174` 처럼 격리 포트로 돌린다(기본 5173 금지).
- 셸 명령은 단순하게: 한 줄 함수 정의, `${…:-{…}}`, 긴 `;`/`&&` 체인 금지.
- 구현자는 fix/wd-821 에 작업 커밋을 남긴다(메인 세션이 끝에 커밋 하나로 합친다 — 제목 끝 `(WD-821)`).

## Review Focus
1. 저장 대화상자를 **취소로 닫은 뒤**에도 미리보기가 남아 있어야 한다(Task 1 Step 1 의 닫기 단계).
2. 차트 종류를 바꾸면 이전 종류의 그림이 남지 않고 새 종류로 바뀐다(Task 1 Step 1 의 두 번째 테스트).
3. Y축 컬럼을 바꾸면 미리보기 축이 바뀐다 — memo 가 config 변경을 놓치면 낡은 축이 남는다(Task 1 두 번째 테스트).
4. 쿼리를 다시 실행해 데이터가 바뀌면 미리보기가 갱신된다 — 단위 테스트 `ChartRenderer.test.tsx` 「MemoChartRenderer — 같은 props…」가 이미 본다(추가 E2E 불필요).
5. 대시보드 위젯은 일반 `ChartRenderer` 를 계속 쓴다 — 기존 `dashboard-widget-keyboard-layout.spec.ts` 가 본다.

---

### Task 1: 차트 빌더 미리보기 — 저장 대화상자 전후 유지·설정 변경 시 갱신 E2E

**Files:**
- Create: `apps/firehub-web/e2e/pages/analytics/chart-builder-preview-memo.spec.ts`
- Modify: `apps/firehub-web/src/components/analytics/ChartRenderer.tsx` (`MemoChartRenderer` 위 JSDoc 에 「대시보드 키보드 배치 경합은 WD-823 — 고치면 전역 memo 로 되돌릴지 검토」 한 줄 추가)
- 참고(수정하지 않음): `e2e/pages/analytics/chart-builder.spec.ts`, `chart-builder-config-withheld.spec.ts` — 로그인 fixture(`authenticatedPage`)·쿼리 목록/실행 모킹 헬퍼를 그대로 재사용한다.

**Interfaces:**
- Consumes: 차트 빌더 화면 `/analytics/charts/new` — 쿼리 고르기 → 실행 → 미리보기(`ChartBuilderPage.tsx` 의 `previewContainerRef` div, 높이 460 안에 recharts `svg.recharts-surface`), 헤더 「저장」 버튼 → `getByRole('dialog')` 저장 대화상자(「취소」 버튼).
- Produces: 없음.

- [ ] **Step 1: 테스트 작성** — `test.describe('차트 빌더 미리보기 — 저장 대화상자·설정 변경 (WD-821)')` 안에 두 개:
  - `'저장 대화상자를 열고 닫아도 미리보기 차트가 그대로 남는다'`: 막대 차트 미리보기가 그려진 뒤 `svg.recharts-surface` 개수와 X축 눈금 글자(첫 행 값)를 기록 → 헤더 「저장」(exact) 클릭 → `getByRole('dialog')` 보임 확인 → 그 동안에도 미리보기 svg 가 보임 → 「취소」로 닫기 → dialog 사라짐 → 미리보기 svg 가 보이고 같은 눈금 글자를 보인다.
  - `'차트 종류·Y축을 바꾸면 미리보기가 새 설정으로 바뀐다'`: 막대 → 차트 종류를 선(LINE)으로 바꾸면 `.recharts-line` 이 보이고 `.recharts-bar-rectangle` 이 0개; 숫자 컬럼이 둘인 모킹 결과에서 Y축을 다른 컬럼으로 바꾸면 범례/툴팁 등 축 이름 표시가 새 컬럼 이름으로 바뀐다(기존 spec 이 쓰는 선택자를 따른다).
- [ ] **Step 2: 실행해 통과 확인**(제품 코드는 이미 있으므로 통과가 기대값) — `cd apps/firehub-web` 뒤 `PW_PORT=6174 pnpm exec playwright test e2e/pages/analytics/chart-builder-preview-memo.spec.ts --retries=0 --repeat-each=3` → 6 passed.
- [ ] **Step 3: 회귀 확인(가짜 통과 방지)** — `ChartBuilderPage.tsx` 의 미리보기를 잠시 `useMemo(() => <ChartRenderer …/>, [])`(의존성 비움)처럼 낡게 만들어 두 번째 테스트가 **실패**하는지 확인하고 즉시 되돌린다(`git diff src/pages` 가 비어야 한다).
- [ ] **Step 4: 주석 추가 후 정적 검사** — `pnpm exec tsc -b --noEmit` 와 `pnpm exec eslint src/components/analytics/ChartRenderer.tsx e2e/pages/analytics/chart-builder-preview-memo.spec.ts` 오류 없음. `pnpm exec vitest run src/components/analytics/ChartRenderer.test.tsx` 21 passed.
