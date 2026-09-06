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

  // 회귀(#399): 은퇴한 온톨로지에 바인딩된 매핑을 편집 중이라는 사실이 이 탭 밖에서는 보이지 않았다
  // (관리 다이얼로그는 ADMIN 전용). OntologyStatusBanner를 재사용해 status=archived면 안내가 떠야 한다.
  test('온톨로지가 은퇴 상태면 매핑 탭에 경고 배너를 보여준다 (#399)', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', '/api/v1/ontologies', [
      {
        id: MAPPING_ONTOLOGY_ID,
        domain: '화재조사 보고서',
        schemaVersion: 1,
        status: 'archived',
        entityCount: 6,
        datasetCount: 3,
        updatedAt: '2026-04-12T09:00:00Z',
        isDefault: true,
      },
    ]);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'active' }));
    await page.goto(MAPPING_URL);

    const banner = page.getByTestId('ontology-status-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('은퇴한 온톨로지');
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

  // 회귀(#408): 이미 매핑된 엔티티 타입을 다시 매핑하면 그래프 투영 시 노드가 조각나므로
  // 드롭다운 단계에서부터 재선택할 수 없어야 한다(서버 conformance와 별개의 UX 방어선).
  test('이미 매핑된 엔티티 타입은 "엔티티 매핑 추가" 다이얼로그 드롭다운에서 제외된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({
      spec: {
        entities: [{ entityType: 'Incident', nameColumn: 'incident_name', properties: [] }],
        relations: [],
      },
    }));
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByTestId('entity-type-select').click();
    await expect(page.getByRole('option', { name: 'Incident' })).toHaveCount(0);
    await expect(page.getByRole('option', { name: 'Building' })).toBeVisible();
  });

  // 회귀(#408): 수정 다이얼로그에서는 자기 자신의 타입은 계속 선택 가능해야 한다(다른 항목과의
  // 중복만 막아야지, "값을 바꾸지 않고 확인"까지 막히면 안 된다).
  test('엔티티 수정 다이얼로그에서는 자기 자신의 엔티티 타입이 드롭다운에 남아있다', async ({
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
    await page.goto(MAPPING_URL);

    await page.getByTestId('entity-row-Incident').getByRole('button', { name: '수정' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByTestId('entity-type-select').click();
    // 자기 자신(Incident)은 보이고, 다른 항목이 이미 쓴 타입(Building)은 보이지 않는다.
    await expect(page.getByRole('option', { name: 'Incident' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Building' })).toHaveCount(0);
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
    await expect(dialog.getByText('모든 속성 행에서 데이터셋 컬럼과 온톨로지 속성을 선택해야 합니다.')).toBeVisible();
  });

  // 회귀(#324) — 숫자 속성에 텍스트 컬럼을 붙이면 서버 conformance가 400으로 막는다.
  // 활성화 버튼을 누른 뒤에야 알게 되지 않도록, 선택 즉시 행 아래에 사유가 뜨고 확인이 막혀야 한다.
  test('숫자 속성에 텍스트 컬럼을 고르면 행별 사유를 보여주고 확인을 막는다', async ({
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

    // damage_name(TEXT) → 피해액(number): 타입 축이 달라 서버가 거부하는 조합.
    await dialog.getByRole('button', { name: '속성 추가' }).click();
    await dialog.getByTestId('property-column-select-0').click();
    await page.getByRole('option', { name: 'damage_name' }).click();
    await dialog.getByTestId('property-name-select-0').click();
    await page.getByRole('option', { name: '피해액' }).click();

    const rowError = dialog.getByTestId('property-type-error-0');
    await expect(rowError).toHaveText('텍스트 컬럼은 숫자 속성(number)에 연결할 수 없습니다');
    await expect(dialog.getByTestId('property-name-select-0')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByRole('button', { name: '확인' })).toBeDisabled();

    // 숫자 컬럼으로 바꾸면 사유가 사라지고 정상 저장까지 이어진다.
    await dialog.getByTestId('property-column-select-0').click();
    await page.getByRole('option', { name: 'damage_amount' }).click();
    await expect(rowError).toBeHidden();
    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(dialog).toBeHidden();

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

  // #300 회귀 — placeholder(지시문)와 오류(요건문)가 같은 문장이면 오류가 "새 정보"로 읽히지 않는다.
  // 두 문구가 동시에, 서로 다른 문장으로 보이는 것까지 확인해야 규칙이 지켜졌다고 할 수 있다.
  test('필수 셀렉트를 비운 채 확인하면 오류는 요건문으로 뜨고 placeholder 지시문은 그대로 남는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: '확인' }).click();

    // 오류가 떠도 다이얼로그는 열려 있어야 한다(제출 차단).
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('엔티티 타입은 필수입니다')).toBeVisible();
    await expect(dialog.getByText('이름 컬럼은 필수입니다')).toBeVisible();
    // placeholder는 앱 전역 관행대로 지시문 그대로 — 오류와 문장이 달라야 한다.
    await expect(dialog.getByTestId('entity-type-select')).toContainText('엔티티 타입을 선택하세요');
    await expect(dialog.getByTestId('entity-name-column-select')).toContainText('이름으로 쓸 컬럼을 선택하세요');
    // 오류 필드는 무효 상태와 설명 노드를 스크린리더에 연결한다.
    await expect(dialog.getByTestId('entity-type-select')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByTestId('entity-type-select')).toHaveAttribute('aria-describedby', 'entity-type-error');
  });

  // #300 회귀 — 속성 행의 라벨과 placeholder가 서로 다른 정보를 주는지 검증한다.
  test('속성 행은 라벨과 placeholder가 서로 다른 문구로 컬럼/속성을 구분한다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await dialog.getByRole('button', { name: '속성 추가' }).click();

    await expect(dialog.getByText('속성 매핑')).toBeVisible();
    // 라벨은 htmlFor로 셀렉트와 연결되어 있어야 접근 이름이 잡힌다.
    await expect(dialog.getByLabel('데이터셋 컬럼')).toHaveAttribute('id', 'property-column-0');
    await expect(dialog.getByLabel('온톨로지 속성')).toHaveAttribute('id', 'property-name-0');
    // placeholder는 라벨과 다른 문자열(명사구 축약)이어야 중복이 아니다.
    await expect(dialog.getByTestId('property-column-select-0')).toContainText('컬럼 선택');
    await expect(dialog.getByTestId('property-name-select-0')).toContainText('속성 선택');
  });

  // #300 회귀 — 비활성 버튼은 이유가 없으면 "고장난 버튼"으로 읽힌다.
  test('엔티티가 2개 미만이면 관계 추가 비활성 사유를 안내하고, 2개 이상이면 안내가 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    // 저장된 매핑이 없는 상태 = 엔티티 0개 → 관계 추가 불가 + 사유 노출.
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    const table = page.getByTestId('relation-mapping-table');
    const addButton = table.getByRole('button', { name: '관계 매핑 추가' });
    const hint = page.locator('#relation-add-hint');

    await expect(addButton).toBeDisabled();
    await expect(hint).toHaveText('엔티티 매핑을 2개 이상 추가하면 관계를 연결할 수 있습니다.');
    await expect(addButton).toHaveAttribute('aria-describedby', 'relation-add-hint');

    // 엔티티를 2개 채우면 안내는 사라지고 버튼이 열린다.
    for (const [type, nameColumn] of [
      ['Damage', 'damage_name'],
      ['Incident', 'incident_name'],
    ]) {
      await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
      const dialog = page.getByTestId('entity-mapping-dialog');
      await dialog.getByTestId('entity-type-select').click();
      await page.getByRole('option', { name: type, exact: true }).click();
      await dialog.getByTestId('entity-name-column-select').click();
      await page.getByRole('option', { name: nameColumn, exact: true }).click();
      await dialog.getByRole('button', { name: '확인' }).click();
      await expect(dialog).toBeHidden();
    }

    await expect(hint).toHaveCount(0);
    await expect(addButton).toBeEnabled();
    await expect(addButton).not.toHaveAttribute('aria-describedby', /.+/);
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

  // 회귀(#400): 이미 활성 상태에서는 재클릭해도 재검증·재활성화 요청이 발생하지 않아야 한다.
  test('이미 활성 상태면 활성화 버튼이 비활성화돼 재클릭으로 재요청이 발생하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'active' }));
    const capture = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping/activate`,
      createMappingResponse({ status: 'active' }),
      { capture: true },
    );
    await page.goto(MAPPING_URL);

    await expect(page.getByTestId('mapping-status')).toHaveText('활성');
    await expect(page.getByTestId('mapping-activate-button')).toBeDisabled();
    expect(capture.requests.length).toBe(0);
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
  // 회귀(#502): DatasetMappingTab은 다른 탭으로 전환되면 완전히 unmount되어 로컬 dirty
  // 상태를 잃는다. 부모(DatasetDetailPage)가 onDirtyChange로 dirty를 전달받아 탭 전환을
  // 가로채지 않으면, 미저장 편집(특히 삭제)이 경고 없이 서버 상태로 조용히 되돌아간다.
  test.describe('#502 매핑 탭 미저장 변경 — 탭 전환 가드', () => {
    test('미저장 변경이 있으면 다른 탭 전환 시 확인을 요구하고, 취소하면 편집이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      // 관계 매핑을 삭제해 dirty 상태를 만든다(이슈 재현 시나리오와 동일).
      await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
      await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
      await expect(page.getByTestId('mapping-dirty')).toBeVisible();
      await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 0개');

      // 확인 다이얼로그에서 취소 → 매핑 탭에 남고 삭제한 관계는 여전히 지워진 채로 보존된다.
      page.once('dialog', (dialog) => {
        expect(dialog.message()).toContain('저장하지 않은 매핑 변경사항이 있습니다');
        void dialog.dismiss();
      });
      await page.getByRole('tab', { name: '필드' }).click();
      await expect(page.getByTestId('mapping-tab')).toBeVisible();
      await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 0개');
      await expect(page.getByTestId('mapping-dirty')).toBeVisible();
    });

    test('미저장 변경이 있을 때 탭 전환을 확인하면 편집 내용이 소실된 채 다른 탭으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      await page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' }).click();
      await page.getByTestId('relation-delete-confirm').getByRole('button', { name: '삭제' }).click();
      await expect(page.getByTestId('mapping-dirty')).toBeVisible();

      page.once('dialog', (dialog) => void dialog.accept());
      await page.getByRole('tab', { name: '필드' }).click();

      // 매핑 탭은 unmount되고 필드 탭이 보인다 — 확인은 "이탈 허용"이지 "저장"이 아니므로
      // 편집 내용은 여전히 소실되지만, 최소한 사용자가 인지한 상태로 이탈해야 한다.
      await expect(page.getByTestId('mapping-tab')).toHaveCount(0);
      await expect(page.getByText('필드 목록')).toBeVisible();

      // 다시 매핑 탭으로 돌아오면 서버 저장본(활성 관계 1건)으로 재시드된다.
      await page.getByRole('tab', { name: '매핑' }).click();
      await expect(page.getByTestId('mapping-summary')).toHaveText('엔티티 2개 · 관계 1개');
      await expect(page.getByTestId('mapping-dirty')).toBeHidden();
    });

    test('미저장 변경이 없으면 탭 전환 시 확인 없이 즉시 이동한다', async ({ authenticatedPage: page }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      let dialogShown = false;
      page.on('dialog', (dialog) => {
        dialogShown = true;
        void dialog.dismiss();
      });

      await page.getByRole('tab', { name: '필드' }).click();
      await expect(page.getByTestId('mapping-tab')).toHaveCount(0);
      await expect(page.getByText('필드 목록')).toBeVisible();
      expect(dialogShown).toBe(false);
    });
  });

  /**
   * #328 회귀: 다이얼로그를 닫으면 포커스가 트리거로 복귀해야 한다(WCAG SC 2.4.3).
   *
   * 원인: Radix modal Content 는 onCloseAutoFocus 에서 preventDefault() 후
   * `<Dialog.Trigger>` 가 채운 triggerRef 로만 복귀한다. 이 앱의 다이얼로그는 모두
   * 외부 `open` prop 으로 제어되어 Trigger 가 없으므로 복귀가 no-op 이 되고,
   * preventDefault 때문에 FocusScope 의 기본 복귀까지 막혀 포커스가 <body> 로 유실됐다.
   */
  test.describe('#328 다이얼로그 닫힘 포커스 복귀', () => {
    test('엔티티 매핑 다이얼로그를 Escape로 닫으면 포커스가 추가 버튼으로 복귀한다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      // 키보드 사용자와 동일하게 트리거에 포커스를 준 뒤 Enter 로 연다.
      const trigger = page.getByRole('button', { name: '엔티티 매핑 추가' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByTestId('entity-mapping-dialog')).toBeVisible();

      await page.keyboard.press('Escape');
      await expect(page.getByTestId('entity-mapping-dialog')).toBeHidden();
      await expect(trigger).toBeFocused();
    });

    test('엔티티 수정 다이얼로그를 취소로 닫으면 포커스가 해당 행의 수정 버튼으로 복귀한다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      const trigger = page.getByTestId('entity-row-Incident').getByRole('button', { name: '수정' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      const dialog = page.getByTestId('entity-mapping-dialog');
      await expect(dialog).toBeVisible();

      await dialog.getByRole('button', { name: '취소' }).click();
      await expect(dialog).toBeHidden();
      // 트리거가 살아 있으므로 대체 지점이 아니라 트리거 자신으로 돌아가야 한다.
      await expect(trigger).toBeFocused();
    });

    test('삭제 확인을 취소하면 살아남은 행의 삭제 버튼으로 복귀한다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      const trigger = page.getByTestId('entity-row-Incident').getByRole('button', { name: '삭제' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      const confirm = page.getByTestId('entity-delete-confirm');
      await expect(confirm).toBeVisible();

      await confirm.getByRole('button', { name: '취소' }).click();
      await expect(confirm).toBeHidden();
      await expect(trigger).toBeFocused();
    });

    test('삭제를 확인해 트리거 행이 사라지면 포커스가 표의 추가 버튼으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupMappingMocks(page);
      await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse());
      await page.goto(MAPPING_URL);

      const trigger = page.getByTestId('relation-row-OCCURRED_AT').getByRole('button', { name: '삭제' });
      await trigger.focus();
      await page.keyboard.press('Enter');
      const confirm = page.getByTestId('relation-delete-confirm');
      await expect(confirm).toBeVisible();

      await confirm.getByRole('button', { name: '삭제' }).click();
      await expect(page.getByTestId('relation-row-OCCURRED_AT')).toHaveCount(0);
      // 트리거가 언마운트됐으므로 restoreFocusRef 로 지정한 '관계 매핑 추가' 로 복귀한다.
      await expect(page.getByRole('button', { name: '관계 매핑 추가' })).toBeFocused();
    });
  });
});

/**
 * #330 회귀: 제출 실패가 스크린리더에 전달되어야 한다.
 * 오류 배선(aria-invalid/aria-describedby)은 #300에서 붙었지만 포커스가 `확인` 버튼에 남아
 * 그 설명이 읽히는 시점이 오지 않았다 — 실패 시 첫 오류 필드로 포커스를 옮긴다.
 */
test.describe('#330 매핑 다이얼로그 제출 실패 포커스', () => {
  test('엔티티 매핑을 빈 값으로 제출하면 첫 오류 필드(엔티티 타입)로 포커스가 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await dialog.getByRole('button', { name: '확인' }).click();

    // 포커스가 첫 오류 필드로 이동해야 연결된 오류 문구가 낭독된다.
    await expect(dialog.getByTestId('entity-type-select')).toBeFocused();
  });

  test('엔티티 타입만 고르고 제출하면 다음 오류 필드(이름 컬럼)로 포커스가 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupMappingMocks(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, { message: '매핑이 없습니다' }, { status: 404 });
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await dialog.getByTestId('entity-type-select').click();
    await page.getByRole('option', { name: 'Damage' }).click();

    await dialog.getByRole('button', { name: '확인' }).click();

    // 앞 필드가 채워졌으므로 화면 순서상 다음 오류 필드로 간다(errors 키 순서가 아니라 화면 순서).
    await expect(dialog.getByTestId('entity-name-column-select')).toBeFocused();
  });

  test('관계 매핑을 빈 값으로 제출하면 첫 오류 필드(주어 엔티티)로 포커스가 이동한다', async ({
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
    await page.goto(MAPPING_URL);

    await page.getByRole('button', { name: '관계 매핑 추가' }).click();
    const dialog = page.getByTestId('relation-mapping-dialog');
    await dialog.getByRole('button', { name: '확인' }).click();

    await expect(dialog.getByTestId('relation-subject-select')).toBeFocused();
  });
});
