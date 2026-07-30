import type { Page } from '@playwright/test';

import {
  createBinding,
  createMappingDataset,
  createMappingResponse,
  createOntologySummaries,
  MAPPING_DATASET_ID,
  MAPPING_ONTOLOGY_ID,
} from '../../factories/mapping.factory';
import { createOntologySchema } from '../../factories/ontology.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 매핑 탭(슬라이스 E) E2E.
 * - 백엔드 없이 page.route()로 매핑·바인딩·온톨로지 API를 모킹한다.
 * - mockApi의 경로는 url.pathname 매칭이므로 /api/v1 프리픽스를 포함한다.
 */

const MAPPING_URL = `/data/datasets/${MAPPING_DATASET_ID}?tab=mapping`;

/** 매핑 탭 렌더에 필요한 공통 목. bound=false면 미바인딩 상태를 만든다. */
async function setupMappingMocks(page: Page, opts: { bound?: boolean } = {}) {
  const { bound = true } = opts;
  await mockApi(page, 'GET', '/api/v1/categories', []);
  await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}`, createMappingDataset());
  await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/ontology`, createBinding(bound ? MAPPING_ONTOLOGY_ID : null));
  await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
  await mockApi(page, 'GET', `/api/v1/ontology/${MAPPING_ONTOLOGY_ID}`, createOntologySchema());
}

test.describe('데이터셋 매핑 탭', () => {
  test('미바인딩 데이터셋은 온톨로지 연결 카드를 보여주고, 선택한 온톨로지 ID를 PUT으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page, { bound: false });
    const capture = await mockApi(page, 'PUT', `/api/v1/datasets/${MAPPING_DATASET_ID}/ontology`, null, {
      status: 204,
      capture: true,
    });
    await page.goto(MAPPING_URL);

    const card = page.getByTestId('ontology-binding-card');
    await expect(card).toBeVisible();

    await page.getByTestId('ontology-select').click();
    await page.getByRole('option', { name: '화재조사 보고서 (v1)' }).click();
    await page.getByRole('button', { name: '연결' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ ontologyId: MAPPING_ONTOLOGY_ID });
  });

  test('매핑이 없으면(404) 에러 토스트 없이 빈 상태를 보여준다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-empty')).toBeVisible();
    await expect(page.getByTestId('mapping-status')).toHaveText('없음');
    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 0개 · 관계 0개');
    // 404는 정상 초기 상태이므로 토스트가 뜨면 안 된다.
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
  });

  test('저장된 매핑이 있으면 상태 배지와 요약을 보여준다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'active' }));
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-tab')).toBeVisible();
    await expect(page.getByTestId('mapping-status')).toHaveText('활성');
    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 1개');
    await expect(page.getByTestId('mapping-empty')).toHaveCount(0);
  });

  test('엔티티 매핑을 추가하고 저장하면 spec 구조가 그대로 PUT 페이로드에 담긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse({ status: 'draft' }),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await dialog.getByTestId('entity-name-column-select').click();
    await page.getByRole('option', { name: 'damage_name' }).click();

    // 속성 1건 추가 — 속성명 선택지는 Damage에 정의된 것만 나와야 한다.
    await dialog.getByRole('button', { name: '속성 추가' }).click();
    await dialog.getByTestId('property-column-select-0').click();
    await page.getByRole('option', { name: 'damage_amount' }).click();
    await dialog.getByTestId('property-name-select-0').click();
    await expect(page.getByRole('option')).toHaveCount(1);
    await page.getByRole('option', { name: '피해액' }).click();

    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('mapping-dirty')).toBeVisible();

    await page.getByRole('button', { name: '초안 저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({
      entities: [
        {
          entityType: 'Damage',
          nameColumn: 'damage_name',
          properties: [{ column: 'damage_amount', propertyName: '피해액' }],
        },
      ],
      relations: [],
    });
  });

  // 회귀(#298): 빈 상태 문구가 서버 저장본이 아닌 로컬 draft를 기준으로 사라져야 한다.
  test('저장 전이라도 엔티티를 추가하면 빈 상태 문구가 사라진다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    // 저장된 매핑이 없는(404) 상태 — 이때 draft에만 엔티티가 생긴다.
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-empty')).toBeVisible();

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await dialog.getByTestId('entity-name-column-select').click();
    await page.getByRole('option', { name: 'damage_name' }).click();
    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(dialog).toBeHidden();

    // 초안 저장을 하지 않은 상태 — 요약은 1개인데 빈 상태 문구가 남아 있으면 안 된다.
    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 1개 · 관계 0개');
    await expect(page.getByTestId('mapping-empty')).toHaveCount(0);
    // 서버 저장 여부는 상태 배지가 계속 '없음'으로 전달한다.
    await expect(page.getByTestId('mapping-status')).toHaveText('없음');
  });

  test('속성 행에 컬럼/속성을 선택하지 않고 확인을 누르면 에러 메시지를 보여주고 다이얼로그를 닫지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await dialog.getByTestId('entity-name-column-select').click();
    await page.getByRole('option', { name: 'damage_name' }).click();

    // 속성 행을 추가만 하고 컬럼/속성을 고르지 않은 채 제출하면 zod가 막는다.
    await dialog.getByRole('button', { name: '속성 추가' }).click();
    await dialog.getByRole('button', { name: '확인' }).click();

    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('모든 속성 행에서 컬럼과 속성을 선택하세요.')).toBeVisible();
  });

  test('저장이 400으로 실패하면 백엔드의 한국어 메시지를 그대로 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
    await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      { message: '온톨로지에 없는 엔티티 타입입니다: Ghost' },
      { status: 400 },
    );
    await page.goto(MAPPING_URL);

    // 초안 저장은 dirty일 때만 눌리므로, 관계 하나를 지워(확인 다이얼로그 포함) 변경 상태를 만든다.
    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
    await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
    await page.getByRole('button', { name: '초안 저장' }).click();
    await expect(page.getByText('온톨로지에 없는 엔티티 타입입니다: Ghost')).toBeVisible();
  });

  test('참조되는 엔티티를 삭제하면 관계 동반 삭제를 고지하고, 남은 관계는 재계산된 인덱스로 저장된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    // 엔티티 3개 + 관계 2개: Incident(0)→Building(1), Incident(0)→Damage(2)
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({
      spec: {
        entities: [
          { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
          { entityType: 'Building', nameColumn: 'building_name', properties: [] },
          { entityType: 'Damage', nameColumn: 'damage_name', properties: [] },
        ],
        relations: [
          { subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 1 },
          { subjectRef: 0, relation: 'RESULTED_IN', objectRef: 2 },
        ],
      },
    }));
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse(),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await page.getByTestId('entity-row-Building').getByRole('button', { name: '삭제' }).click();
    const confirm = page.getByTestId('entity-delete-confirm');
    await expect(confirm).toContainText('관계 1건');
    await confirm.getByRole('button', { name: '삭제' }).click();

    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 1개');

    await page.getByRole('button', { name: '초안 저장' }).click();
    const req = await capture.waitForRequest();
    // Damage는 인덱스 2 → 1로 재계산돼야 한다.
    expect(req.payload).toEqual({
      entities: [
        { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
        { entityType: 'Damage', nameColumn: 'damage_name', properties: [] },
      ],
      relations: [{ subjectRef: 0, relation: 'RESULTED_IN', objectRef: 1 }],
    });
  });

  test('관계 추가 시 관계 드롭다운은 선택한 끝점 조합의 허용 트리플만 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({
      spec: {
        entities: [
          { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
          { entityType: 'Building', nameColumn: 'building_name', properties: [] },
        ],
        relations: [],
      },
    }));
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse(),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '관계 매핑 추가' }).click();
    const dialog = page.getByTestId('relation-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByTestId('relation-subject-select').click();
    await page.getByRole('option', { name: 'Incident (incident_name)' }).click();
    await dialog.getByTestId('relation-object-select').click();
    await page.getByRole('option', { name: 'Building (building_name)' }).click();

    // Incident→Building 허용 트리플은 OCCURRED_AT 하나뿐이다.
    await dialog.getByTestId('relation-type-select').click();
    await expect(page.getByRole('option')).toHaveCount(1);
    await page.getByRole('option', { name: 'OCCURRED_AT' }).click();

    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(dialog).toBeHidden();

    await page.getByRole('button', { name: '초안 저장' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({
      entities: [
        { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
        { entityType: 'Building', nameColumn: 'building_name', properties: [] },
      ],
      relations: [{ subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 1 }],
    });
  });

  test('활성화하면 상태 배지가 활성으로 바뀐다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'draft' }));
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping/activate`,
      createMappingResponse({ status: 'active' }),
    );
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-status')).toHaveText('초안');
    await page.getByTestId('mapping-activate-button').click();
    await expect(page.getByTestId('mapping-status')).toHaveText('활성');
  });

  test('엔티티 타입을 바꾸면 이전 타입의 속성 행이 사라지고, 저장 페이로드에도 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    // Damage는 속성(피해액)이 있는 유일한 타입이므로, 다른 타입(속성 없음)으로 바꿨을 때
    // 이전 속성 행이 화면과 폼 상태에서 완전히 사라지고, 저장 시에도 담기지 않는지 검증한다.
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({
      spec: {
        entities: [
          {
            entityType: 'Damage',
            nameColumn: 'damage_name',
            properties: [{ column: 'damage_amount', propertyName: '피해액' }],
          },
        ],
        relations: [],
      },
    }));
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse(),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await page.getByTestId('entity-row-Damage').getByRole('button', { name: '수정' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByTestId('property-column-select-0')).toBeVisible();

    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Incident' }).click();
    await expect(dialog.getByTestId('property-column-select-0')).toHaveCount(0);

    await dialog.getByTestId('entity-name-column-select').click();
    await page.getByRole('option', { name: 'incident_name' }).click();
    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(dialog).toBeHidden();

    await page.getByRole('button', { name: '초안 저장' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({
      entities: [{ entityType: 'Incident', nameColumn: 'incident_name', properties: [] }],
      relations: [],
    });
  });

  test('저장되지 않은 변경이 있으면 활성화를 막는다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'draft' }));
    await page.goto(MAPPING_URL);

    // 관계 하나를 지워(확인 다이얼로그 포함) dirty 상태를 만든다.
    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
    await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByTestId('mapping-dirty')).toBeVisible();

    await page.getByTestId('mapping-activate-button').click();
    await expect(page.getByText('저장되지 않은 변경이 있습니다. 먼저 저장하세요.')).toBeVisible();
    // 활성화되지 않았으므로 배지는 그대로다.
    await expect(page.getByTestId('mapping-status')).toHaveText('초안');
  });

  // 회귀(#297): 저장은 status를 항상 draft로 되돌리므로, 미변경 재저장은 활성 매핑을 조용히 강등시켰다.
  test('변경이 없으면 초안 저장을 막아 활성 매핑이 강등되지 않는다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'active' }));
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse({ status: 'draft' }),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-status')).toHaveText('활성');
    await expect(page.getByTestId('mapping-save-button')).toBeDisabled();

    // 편집(관계 삭제 + 확인)을 하면 다시 눌릴 수 있어야 한다 — 정상 편집 흐름은 막지 않는다.
    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
    await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByTestId('mapping-save-button')).toBeEnabled();
    // 강등 확인 전에는 PUT이 나가지 않는다.
    expect(capture.requests.length).toBe(0);
  });

  // 회귀(#297): 활성 → 초안 강등은 그래프 투영을 멈추므로 사전 고지가 필요하다.
  test('활성 매핑을 편집 후 저장하면 강등 확인을 거친 뒤에만 PUT이 나간다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'active' }));
    const capture = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`,
      createMappingResponse({ status: 'draft' }),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
    await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
    await page.getByTestId('mapping-save-button').click();

    const confirm = page.getByTestId('mapping-demote-confirm');
    await expect(confirm).toBeVisible();

    // 취소하면 저장이 일어나지 않고 활성 상태가 유지된다.
    await confirm.getByRole('button', { name: '취소' }).click();
    await expect(confirm).toBeHidden();
    expect(capture.requests.length).toBe(0);
    await expect(page.getByTestId('mapping-status')).toHaveText('활성');

    // 확인하면 강등된 spec이 저장된다.
    await page.getByTestId('mapping-save-button').click();
    await confirm.getByRole('button', { name: '초안으로 저장' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({
      entities: [
        { entityType: 'Incident', nameColumn: 'incident_name', properties: [] },
        { entityType: 'Building', nameColumn: 'building_name', properties: [] },
      ],
      relations: [],
    });
    await expect(page.getByTestId('mapping-status')).toHaveText('초안');
  });

  // 회귀(#299): 같은 패널의 엔티티 삭제는 확인을 요구하는데 관계 삭제만 즉시 실행돼,
  // 잘못 누른 관계(주어/관계/목적어 3단 선택)를 되돌릴 수 없었다.
  test('관계 삭제는 확인 다이얼로그를 거치고, 취소하면 관계가 남는다', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
    await page.goto(MAPPING_URL);

    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();

    // 확인 전에는 행도 dirty 표시도 그대로다.
    const confirm = page.getByTestId('relation-delete-confirm');
    await expect(confirm).toBeVisible();
    // 어떤 관계가 지워지는지 끝점 라벨까지 정확히 고지해야 한다.
    await expect(confirm).toContainText(
      'Incident (incident_name) → OCCURRED_AT → Building (building_name) 관계 매핑을 삭제합니다.',
    );
    await expect(page.getByTestId('relation-row-OCCURRED_AT')).toBeVisible();
    await expect(page.getByTestId('mapping-dirty')).toBeHidden();

    // 취소하면 삭제가 일어나지 않는다.
    await confirm.getByRole('button', { name: '취소' }).click();
    await expect(confirm).toBeHidden();
    await expect(page.getByTestId('relation-row-OCCURRED_AT')).toBeVisible();
    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 1개');
    await expect(page.getByTestId('mapping-dirty')).toBeHidden();

    // 확인하면 행이 사라지고 미저장 변경으로 표시된다.
    await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
    await confirm.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByTestId('relation-row-OCCURRED_AT')).toHaveCount(0);
    await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 0개');
    await expect(page.getByTestId('mapping-dirty')).toBeVisible();
  });
});
