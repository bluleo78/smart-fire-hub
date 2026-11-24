/**
 * `size="icon"`·`size="sm"` 버튼의 최소 클릭 영역 32×32 회귀 시험 (WD-1022)
 *
 * 결함: `size="icon"`(buttonVariants `size-9` = 36px)·`size="sm"`(`h-8` = 32px)을 쓰면서 `className` 으로
 * `h-5`·`h-6`·`h-7`을 덮어써, 디자인 시스템 최소 클릭 영역 32×32px(WD-914 → WD-926 → WD-951)에 못 미쳤다
 * (WCAG 2.5.8 의 24×24 보다 높은 우리 규칙). 세 화면의 대상 버튼을 화면에 실제로 그려진 폭·높이
 * (`getBoundingClientRect()`)로 재어 전부 32px 이상인지 본다.
 *
 * - AI 어시스턴트 패널 머리(사이드 패널·전체 화면 모드 전환·닫기)
 * - 데이터셋 상세 필드 탭(컬럼 행 접기·위로·아래로·삭제 + 머리글의 태그 추가)
 * - 파이프라인 스텝 설정 패널 머리(닫기)
 *
 * WD-960(size-sm-hit-target)과 같은 관형: 단언 전에 캡처를 남겨 단언이 실패해도 그 시점의 화면이 증거로 남고,
 * 한 개도 보이지 않아 0건으로 공허하게 통과하지 않도록 측정 대상 개수를 먼저 단언한다.
 * `h-7`·`h-6`·`h-5` 가 다시 들어오면 레드로 떨어진다 — 이 시험의 몫은 고친 그린을 지키는 것이다.
 */

import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Locator, Page } from '@playwright/test';

import { createCategories } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** 최소 클릭 영역 한 변(px) — 대상 버튼이 치야 하는 32×32 */
const MIN = 32;

/**
 * 캡처 폴더 — `apps/firehub-web/test-results/tc/<suite>/` 규약을 따른다(루트 CLAUDE.md).
 * `__dirname` 기준이라 실행 위치(cwd)와 무관하게 항상 그 폴더에 남고, 재실행하면 같은 자리에 덮어쓴다.
 */
const SHOT_DIR = path.resolve(__dirname, '..', '..', '..', 'test-results', 'tc', 'WD-1022');

/**
 * 단언 전 캡처 — 단언이 실패해도 이 시점까지 찍혔으므로 증거는 남는다. 폴더는 없으면 만든다.
 * @param page - 캡처할 페이지
 * @param fileName - 저장할 파일 이름(ai-panel-head.png 등)
 */
async function shot(page: Page, fileName: string) {
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: path.join(SHOT_DIR, fileName), fullPage: false });
}

/**
 * 대상 버튼들의 폭·높이를 재어 전부 `MIN` 이상인지 본다.
 *
 * - 보이는 대상만 센다: 보이지 않는 요소는 측정 대상이 아니고, `display:none` 이면 `getBoundingClientRect()`
 *   가 0 이라 32px 미달로 잡힌다. 한 개도 안 보이면 아래 `first()` 대기에서 실패해 0건으로 공허하게 통과하지 않는다.
 * - `first()` 대기와 이 재해석 사이에 DOM 이 바뀌어 빈 배열이 되면 그대로 지나가지 않게 한다(0건 공허 통과 방지).
 *
 * @param targets - 측정할 버튼 로케이터(이름으로 찾은 것·구조로 찾은 것 모두)
 * @param label - 실패 메시지에 붙는 이름 — 무엇이 깨졌는지 그대로 읽히게 한다
 */
async function expectMinBox(targets: Locator, label: string) {
  await expect(targets.first()).toBeVisible();
  const sizes = await targets.evaluateAll((els) =>
    els.map((el) => {
      const rect = el.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    }),
  );
  expect(sizes.length, `${label} 측정 결과 0건`).toBeGreaterThan(0);
  for (const { width, height } of sizes) {
    expect(height, `${label} 높이 ${height}px < ${MIN}px`).toBeGreaterThanOrEqual(MIN);
    expect(width, `${label} 너비 ${width}px < ${MIN}px`).toBeGreaterThanOrEqual(MIN);
  }
}

/**
 * 영역 안에서 접근 가능한 이름으로 찾은 버튼들을 재어 전부 `MIN` 이상인지 본다.
 *
 * - 영역(`scope`)을 받는 이유: 페이지 전체에서 이름으로만 찾으면 같은 이름의 다른 버튼(이 시험이 보지 않는 자리)까지
 *   재어 범위 밖 실패를 낼 수 있다 — 대상이 들어 있는 화면 줄·패널·테이블 안만 재도록 범위를 좁힌다.
 * - `exact` 를 쓰는 이유: 짧은 이름을 부분 매칭하면 엉뚱한 버튼까지 잡아 고쳐야 할 자리가 아니라 다른 자리로 실패한다.
 *
 * @param scope - 대상 버튼이 들어 있는 영역(패널·테이블) 또는 페이지(이름이 페이지에서 유일한 버튼)
 * @param names - 측정할 버튼의 접근 가능한 이름들(하나씩 순회)
 */
async function expectMinClickArea(scope: Page | Locator, names: string[]) {
  for (const name of names) {
    await expectMinBox(scope.getByRole('button', { name, exact: true }).filter({ visible: true }), name);
  }
}

test.describe('size="icon"·size="sm" 버튼 최소 클릭 영역 (WD-1022)', () => {
  test('AI 어시스턴트 패널 머리 — 사이드 패널·전체 화면·닫기 버튼', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    // AI 상태 칩을 누르면 데스크탑 사이드 패널이 열리고 입력창이 그려진다
    await page.getByRole('button', { name: /AI 상태/ }).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });

    // 사이드 패널(data-testid)만 영역으로 삼는다 — 같은 이름 버튼이 다른 모드 패널에 있어도 밖이다.
    // 모드 전환 버튼의 접근 가능한 이름은 title(「사이드 패널」·「전체 화면」), 닫기 버튼은 aria-label 이다.
    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).toBeVisible();

    await shot(page, 'ai-panel-head.png');
    await expectMinClickArea(panel, ['사이드 패널', '전체 화면', 'AI 어시스턴트 닫기']);
  });

  test('데이터셋 상세 필드 탭 — 컬럼 행 접기·위로·아래로·삭제와 태그 추가', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // 「작업」 열(위로·아래로·편집·삭제)과 태그 추가는 dataset:write 권한이 있을 때만 그려진다
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10_000 });

    // 대상이 들어 있는 표 한 개 — 다른 탭·화면의 같은 이름 버튼은 재지 않는다
    const table = page.getByRole('table');
    const firstRow = table.locator('tbody tr').first();
    // 호버로만 드러나는 행 액션이 생겨도 재게 하려고 행에 호버부터 올린다(지금은 항상 그려지는 구조다)
    await firstRow.hover();

    await shot(page, 'dataset-columns-tab.png');

    // 접기 토글은 아이콘만 있는 버튼이라 접근 가능한 이름이 없다 — 첫 칸(표의 맨 왼쪽 w-8 칸) 구조로 찾는다
    await expectMinBox(firstRow.locator('td').first().getByRole('button'), '컬럼 행 접기 버튼');
    // 위로·아래로·삭제는 aria-label 로 이름을 갖는다(행 전체에서 재어 각 행의 것을 모두 본다)
    await expectMinClickArea(table, ['컬럼 위로 이동', '컬럼 아래로 이동', '컬럼 삭제']);
    // 머리글의 태그 추가 버튼(title) — 표 바깥이라 페이지 범위에서 찾되 정확히 이름이 같은 것만
    await expectMinClickArea(page, ['태그 추가']);
  });

  test('파이프라인 스텝 설정 패널 머리 — 닫기 버튼', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await grantPipelinePermissions(page);
    await page.goto('/pipelines/new');
    // 툴바의 「스텝 추가」로 ADD_STEP — 노드 카드의 20px 「다음 스텝 추가」 를 못 잡게 툴바만 집는다
    await clickToolbarAddStep(page);
    // ReactFlow 노드 클릭 → StepConfigPanel 열림
    await page.locator('.react-flow__node').first().click();
    await page.locator('#step-name').waitFor({ state: 'visible', timeout: 10_000 });

    const panel = page.getByTestId('step-config-panel');
    // 닫기 버튼은 StepConfigPanel 이 aria-label(「스텝 설정 패널 닫기」)을 주므로 구조가 아니라 접근 가능한 이름으로 찾는다
    const closeButton = panel.getByRole('button', { name: '스텝 설정 패널 닫기', exact: true });

    await shot(page, 'step-config-panel-head.png');
    await expectMinBox(closeButton, '스텝 설정 패널 닫기 버튼');
  });
});
