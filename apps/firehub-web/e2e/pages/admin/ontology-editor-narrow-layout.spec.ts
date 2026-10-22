import type { Page } from '@playwright/test';

import { createDraftOntologySummary } from '../../factories/mapping.factory';
import { createMappingUsage, createOntologySchema } from '../../factories/ontology.factory';
import { setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 지식 모델 편집기 — 좁은 본문에서의 목록·편집 패널 배치 (WD-130).
 *
 * 결함: 목록(ModelOutline)과 편집 패널(인스펙터)이 창 폭 xl(1280px) 미만에서 통째로 숨어(#403),
 * 1024px 노트북·태블릿에서는 「첫 타입 만들기」가 수정 모드만 켜고 아무것도 열지 않았고 타입을 편집할
 * 수단도 없었다. 반대로 넓은 창에서 AI 챗 패널을 열어 본문만 좁아지면 두 패널이 그대로 나와 캔버스를
 * 짓눌렀다(기준이 창 폭이라).
 * 수정: 기준을 지식 모델 본문 컨테이너 폭(@container/kg-body)으로 바꾸고, 856px(목록 256 + 캔버스 최소
 * 280 + 편집 320) 미만이면 목록은 접기 막대로, 편집 패널은 선택·생성 폼이 있을 때만 붙게 했다.
 *
 * 폭 단언은 bounding box로 한다 — toBeVisible()은 overflow-hidden 조상에 잘려 화면 밖으로 밀린
 * 요소도 통과시키므로, "편집 패널이 본문 안에 있고 캔버스가 280px 이상"을 동시에 숫자로 확인해야
 * 짓눌림·잘림 회귀를 잡는다.
 */

/** 요소의 화면상 상자 — 없으면 테스트를 실패시킨다(null 단언을 매번 반복하지 않게). */
async function box(page: Page, testId: string) {
  const b = await page.getByTestId(testId).boundingBox();
  expect(b, `${testId} 상자`).not.toBeNull();
  return b!;
}

/** 본문(<main>)의 오른쪽 끝 x — 편집 패널이 이 밖으로 밀려 잘리지 않았는지 보는 기준. */
async function mainRight(page: Page) {
  const b = await page.locator('main').boundingBox();
  return b!.x + b!.width;
}

/**
 * 캔버스 영역의 "보이는" 폭 — 캔버스 래퍼(min-w-[280px]가 걸린 요소)를 재되, 오른쪽 끝을 캔버스+편집
 * 패널 영역(overflow-hidden)과 <main>의 오른쪽 끝으로 자른다. 래퍼 자체의 폭은 min-w 때문에 폭이 모자라도
 * 280 그대로 남고 넘친 부분은 잘려 숨을 뿐이라, 래퍼 폭만 재면 "≥ 280"이 구조적으로 항상 참이 된다.
 * schema-graph는 래퍼의 p-3 안쪽이라 24px 작다. 빈 초안처럼 schema-graph가 없는 경우도 같은 기준으로 잰다.
 */
async function canvasWidth(page: Page) {
  return page
    .getByTestId('instance-graph-panel')
    .locator(':scope > div')
    .first()
    .evaluate((el) => {
      const r = el.getBoundingClientRect();
      const panel = el.parentElement!.getBoundingClientRect();
      const main = document.querySelector('main')!.getBoundingClientRect();
      return Math.round(Math.min(r.right, panel.right, main.right) - Math.max(r.left, panel.left, main.left));
    });
}

test.describe('지식 모델 편집기 — 좁은 본문 배치 (WD-130)', () => {
  test('1024px 창의 빈 초안에서 「첫 타입 만들기」가 생성 폼을 실제로 열고, 저장하면 올바른 요청이 나간다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    // 타입이 하나도 없는 초안 하나만 있는 테넌트 — 선택기 폴백으로 곧바로 이 초안이 열린다.
    await mockApi(page, 'GET', '/api/v1/ontologies', [createDraftOntologySummary()]);
    await mockApi(
      page,
      'GET',
      '/api/v1/ontology/3',
      createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }),
    );
    await mockApi(page, 'GET', '/api/v1/ontology/3/mapping-usage', createMappingUsage());
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '첫 타입 만들기' }).click();

    // 핵심 완료 기준 — 수정 모드만 켜지는 게 아니라 생성 폼이 화면 안에 실제로 보인다.
    await expect(page.getByRole('button', { name: '수정 모드' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('entity-inspector-create')).toBeVisible();
    const inspector = await box(page, 'model-inspector');
    expect(inspector.width).toBeGreaterThanOrEqual(300);
    expect(inspector.x + inspector.width).toBeLessThanOrEqual((await mainRight(page)) + 1);
    // 목록은 접기 막대로 접혀 있다(편집 패널과 한 번에 하나만).
    await expect(page.getByTestId('model-outline-rail')).toBeVisible();
    await expect(page.getByTestId('model-outline')).toBeHidden();
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/3/entity-types',
      {
        schemaVersion: 2,
        entityType: { id: 1, type: 'Building', description: '', naming: '', resolution: 'embedding', properties: [] },
      },
      { capture: true },
    );
    await page.getByLabel('타입 이름').fill('Building');
    // exact — 빈 상태 CTA(「첫 타입 만들기」)가 아직 떠 있어 부분 일치면 strict mode 충돌이 난다.
    await page.getByRole('button', { name: '타입 만들기', exact: true }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ type: 'Building', description: '', naming: '', resolution: 'embedding' });
  });

  test('1024px 창에서 목록은 접기 막대로 접히고, 펼치기·타입 선택·편집 패널 닫기가 동작한다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 선택 전: 목록은 얇은 막대, 편집 패널은 붙지 않는다 — 캔버스가 넓게 남는다.
    const rail = await box(page, 'model-outline-rail');
    expect(rail.width).toBeLessThanOrEqual(48);
    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);

    // ▸ 목록 펼치기 — 목록이 겹치지 않고 붙어 캔버스를 밀어낸다(캔버스는 여전히 280px 이상).
    await page.getByRole('button', { name: '목록 펼치기' }).click();
    await expect(page.getByTestId('model-outline')).toBeVisible();
    await expect(page.getByTestId('model-outline-rail')).toBeHidden();
    const outline = await box(page, 'model-outline');
    const canvasPanel = await box(page, 'instance-graph-panel');
    // 겹침이 아니라 밀어냄 — 캔버스 영역은 목록 오른쪽 끝에서 시작한다.
    expect(canvasPanel.x).toBeGreaterThanOrEqual(outline.x + outline.width - 1);
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);
    // 펼친 목록을 접으면 다시 막대로 — 포커스는 막대의 펼치기 버튼으로 돌아온다(숨은 버튼에 갇히지 않는다).
    await page.getByRole('button', { name: '목록 접기' }).click();
    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByRole('button', { name: '목록 펼치기' })).toBeFocused();

    // 다시 펼쳐 타입(Building, id=2)을 고르면 목록이 접히고 편집 패널이 붙는다.
    await page.getByRole('button', { name: '목록 펼치기' }).click();
    await page.getByTestId('outline-entity-2').click();
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');
    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-outline-rail')).toBeVisible();
    const inspector = await box(page, 'model-inspector');
    expect(inspector.width).toBeGreaterThanOrEqual(300);
    expect(inspector.x + inspector.width).toBeLessThanOrEqual((await mainRight(page)) + 1);
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);

    // ✕ 편집 패널 닫기 — 패널이 사라지고 선택도 풀린다(캔버스에 선택 표시가 남지 않는다).
    await page.getByRole('button', { name: '편집 패널 닫기' }).click();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
    const selectedCount = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologySchemaCy?: { $(sel: string): { length: number } } })
        .__ontologySchemaCy;
      return cy ? cy.$(':selected').length : -1;
    });
    expect(selectedCount).toBe(0);
  });

  test('1440px 창(넓은 본문)에서는 지금처럼 목록·캔버스·편집 패널 3칸이 함께 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 선택 전에도 편집 패널이 붙어 안내 문구를 보인다 — 넓을 때의 규칙은 바뀌지 않았다.
    await expect(page.getByTestId('model-outline')).toBeVisible();
    await expect(page.getByTestId('model-inspector')).toBeVisible();
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
    // 좁을 때만 쓰는 조작(접기 막대·접기·닫기)은 넓을 때 나타나지 않는다.
    await expect(page.getByTestId('model-outline-rail')).toBeHidden();
    await expect(page.getByRole('button', { name: '목록 접기' })).toBeHidden();
    await expect(page.getByRole('button', { name: '편집 패널 닫기' })).toBeHidden();
    expect((await box(page, 'model-outline')).width).toBe(256);
    expect((await box(page, 'model-inspector')).width).toBe(320);
    expect(await canvasWidth(page)).toBeGreaterThan(280);

    // 타입을 골라도 목록은 그대로 펼쳐져 있다(좁을 때의 "하나만" 규칙이 넓을 때 새지 않는다).
    await page.getByTestId('outline-entity-2').click();
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');
    await expect(page.getByTestId('model-outline')).toBeVisible();
  });

  // 창 폭이 아니라 본문 폭이 기준임을 증명한다 — 같은 1440px 창에서 AI 챗 패널(380px)만 열어 본문을
  // 좁히면 접힌 배치로 바뀌어야 한다. 창 폭(xl) 기준이었다면 여기서도 3칸이 나와 캔버스가 짓눌렸다.
  test('1440px 창이라도 AI 챗 패널을 열어 본문이 좁아지면 접힌 배치가 된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 열기 전: 넓은 배치(목록 패널 보임) — 대조군. 본문 폭도 기록해 둔다.
    await expect(page.getByTestId('model-outline')).toBeVisible();
    const mainBefore = (await page.locator('main').boundingBox())!.width;

    await page.getByText('AI 어시스턴트').first().click();
    await expect(page.getByTestId('ai-side-panel')).toBeVisible();
    // 창 폭은 그대로이고 본문만 좁아졌다 — 이 조건에서 배치가 바뀌는 것이 곧 컨테이너 기준의 증거다.
    expect(page.viewportSize()!.width).toBe(1440);
    // 패널은 폭 전환 애니메이션으로 열리므로 한 번 재지 않고 다 열릴 때까지 기다린다.
    await expect
      .poll(async () => (await page.locator('main').boundingBox())!.width)
      .toBeLessThan(mainBefore - 300);

    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-outline-rail')).toBeVisible();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);

    // 타입을 고르면 편집 패널이 본문 안에 붙고, 캔버스는 280px 이상을 지킨다(짓눌리거나 잘리지 않는다).
    await page.getByRole('button', { name: '목록 펼치기' }).click();
    await page.getByTestId('outline-entity-2').click();
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');
    const inspector = await box(page, 'model-inspector');
    expect(inspector.x + inspector.width).toBeLessThanOrEqual((await mainRight(page)) + 1);
    expect(await canvasWidth(page)).toBeGreaterThanOrEqual(280);
  });

  // 본문이 640px(접기 막대 40 + 캔버스 280 + 편집 320)보다도 좁으면 편집 패널을 캔버스 옆에 붙일 폭이 없다 —
  // 그때는 편집 패널이 캔버스 자리를 대신 차지하고(겹치는 서랍 아님), ✕로 닫으면 캔버스가 돌아온다.
  // 1024px 창 + AI 챗 패널이면 본문이 356px 안팎이 된다.
  test('본문이 640px보다 좁으면(1024px 창 + AI 챗) 편집 패널이 캔버스 자리를 대신하고 닫으면 캔버스가 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByText('AI 어시스턴트').first().click();
    await expect(page.getByTestId('ai-side-panel')).toBeVisible();
    await expect.poll(async () => (await page.locator('main').boundingBox())!.width).toBeLessThan(500);

    // 선택 전: 접기 막대 + 캔버스만.
    await expect(page.getByTestId('model-outline-rail')).toBeVisible();
    await expect(page.getByTestId('schema-graph')).toBeVisible();

    // 캔버스에서 타입을 고르면 편집 패널이 캔버스 자리를 차지한다 — 본문 밖으로 잘리지 않는다.
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Building"]')
        .emit('tap');
    });
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');
    await expect(page.getByTestId('schema-graph')).toBeHidden();
    const inspector = await box(page, 'model-inspector');
    expect(inspector.x + inspector.width).toBeLessThanOrEqual((await mainRight(page)) + 1);
    expect(inspector.width).toBeGreaterThanOrEqual(250);

    await page.getByRole('button', { name: '편집 패널 닫기' }).click();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
    await expect(page.getByTestId('schema-graph')).toBeVisible();
  });
});
