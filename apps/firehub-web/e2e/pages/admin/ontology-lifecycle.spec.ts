import type { CreateOntologyRequest } from '@/types/ontology';

import {
  createArchivedOntologySummary,
  createDraftOntologySummary,
  createOntologySummaries,
} from '../../factories/mapping.factory';
import { createOntologyGraph, createOntologySchema } from '../../factories/ontology.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

// 지식 모델 탭의 온톨로지 생명주기 — 선택·생성·활성화·은퇴·삭제.
test.describe('온톨로지 생명주기', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', [
      ...createOntologySummaries(),
      createDraftOntologySummary(),
      createArchivedOntologySummary(),
    ]);
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
  });

  test(
    '선택기에서 온톨로지를 고르면 해당 스키마를 조회한다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      const otherSchema = createOntologySchema({ domain: '건축물 대장', schemaVersion: 3 });
      const capture = await mockApi(page, 'GET', '/api/v1/ontology/2', otherSchema, { capture: true });
      await page.goto('/knowledge-graph/model');

      await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
      await page.getByRole('option', { name: '건축물 대장' }).click();

      // 선택 → by-id 조회가 실제로 발생해야 한다(선택기가 표시만 바꾸는 것이 아님을 검증).
      await capture.waitForRequest();
      await expect(page.getByRole('combobox', { name: '온톨로지 선택' })).toContainText('건축물 대장');
    },
  );

  test('새 온톨로지는 도메인명만 받아 draft로 생성된다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/ontologies', 5, { status: 201, capture: true });
    await mockApi(page, 'GET', '/api/v1/ontology/5', createOntologySchema({ domain: '신규 도메인', entities: [], relations: [] }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '새 온톨로지' }).click();
    await page.getByLabel('도메인').fill('신규 도메인');
    await page.getByRole('button', { name: '만들기' }).click();

    // 입력 → API payload 검증. status가 draft가 아니면 사람 검토 없이 운영에 들어간다.
    const req = await capture.waitForRequest();
    const payload = req.payload as CreateOntologyRequest;
    expect(payload).toMatchObject({ domain: '신규 도메인', status: 'draft', entities: [], relations: [] });
  });

  test('도메인명 중복(409)은 필드 에러로 표시된다', async ({ authenticatedPage: page }) => {
    // 실제 백엔드 페이로드(GlobalExceptionHandler.handleIllegalState → ErrorResponse.message = ex.getMessage())를
    // 그대로 목킹한다 — 코드리뷰 결함 #1: 친절한 한국어를 임의로 목킹하면 실제 도메인 중복 사전 검사가
    // 없어도(또는 DataIntegrityViolationException의 영문 문구로 새도) 이 테스트가 눈치채지 못한다.
    await mockApi(
      page,
      'POST',
      '/api/v1/ontologies',
      { message: '이미 같은 도메인의 온톨로지가 있습니다: 화재조사 보고서' },
      { status: 409 },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '새 온톨로지' }).click();
    await page.getByLabel('도메인').fill('화재조사 보고서');
    await page.getByRole('button', { name: '만들기' }).click();

    await expect(page.getByText('이미 같은 도메인의 온톨로지가 있습니다: 화재조사 보고서')).toBeVisible();
    // 다이얼로그가 닫히지 않아야 사용자가 이름을 고칠 수 있다.
    await expect(page.getByLabel('도메인')).toBeVisible();
  });

  test('비-ADMIN에게는 생성·편집 컨트롤이 보이지 않는다', async ({ authenticatedPage: page }) => {
    // setupAdminAuth를 덮어써 일반 사용자로 만든다.
    await mockApi(page, 'GET', '/api/v1/users/me', {
      id: 2, username: 'user', email: 'user@test.com', roles: [{ id: 2, name: 'USER' }], enabled: true,
    });
    await mockApi(
      page,
      'GET',
      '/api/v1/ontology/3',
      createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }),
    );
    await page.goto('/knowledge-graph/model');

    await expect(page.getByRole('button', { name: '새 온톨로지' })).toBeHidden();
    await expect(page.getByRole('button', { name: '편집' })).toBeHidden();

    // draft 온톨로지를 선택해도 배너의 안내 문구는 보이되(비-ADMIN에게도 유용한 정보) 활성화 액션은 숨겨야 한다(스펙 271행).
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '소방시설 점검' }).click();
    await expect(page.getByTestId('ontology-status-banner')).toContainText('이 온톨로지는 초안입니다.');
    await expect(page.getByRole('button', { name: '활성화' })).toBeHidden();
  });

  test('관리 다이얼로그가 카운트와 수정일을 셀 단위로 보여준다', async ({ authenticatedPage: page }) => {
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    const dialog = page.getByTestId('ontology-manage-dialog');
    await expect(dialog).toBeVisible();

    const fireRow = dialog.getByRole('row', { name: /화재조사 보고서/ });
    // exact: true — 수정일 셀(예: "오후 6:00:00")이 시각 표기에 우연히 "6"을 포함해 서브스트링 매칭 시 충돌한다.
    await expect(fireRow.getByRole('cell', { name: '6', exact: true })).toBeVisible();
    await expect(fireRow.getByRole('cell', { name: '3개 데이터셋' })).toBeVisible();
    // 기본 온톨로지(id=1)는 삭제 불가 사유가 버튼 대신 표시된다.
    await expect(fireRow.getByText('기본 온톨로지')).toBeVisible();
    await expect(fireRow.getByRole('button', { name: '삭제' })).toBeHidden();
  });

  test('참조가 없는 온톨로지는 삭제할 수 있다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'DELETE', '/api/v1/ontology/2', null, { status: 204, capture: true });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    const dialog = page.getByTestId('ontology-manage-dialog');
    await dialog.getByRole('row', { name: /건축물 대장/ }).getByRole('button', { name: '삭제' }).click();
    await page.getByRole('button', { name: '삭제', exact: true }).last().click();

    await capture.waitForRequest();
  });

  test('은퇴 요청은 status=archived를 담아 보낸다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'PUT', '/api/v1/ontology/2', createOntologySchema(), { capture: true });
    await mockApi(page, 'GET', '/api/v1/ontology/2', createOntologySchema({ domain: '건축물 대장' }));
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    await page
      .getByTestId('ontology-manage-dialog')
      .getByRole('row', { name: /건축물 대장/ })
      .getByRole('button', { name: '은퇴' })
      .click();

    const req = await capture.waitForRequest();
    expect((req.payload as { status?: string }).status).toBe('archived');
  });

  test('참조 중 삭제 시도(409)는 사유를 토스트로 보여준다', async ({ authenticatedPage: page }) => {
    // 관리 다이얼로그는 사유를 미리 보여주지만, 동시 바인딩 상황에서는 409가 올 수 있다.
    await mockApi(page, 'DELETE', '/api/v1/ontology/2', { message: '2개 데이터셋이 사용 중입니다. 은퇴(archived)를 사용하세요.' }, { status: 409 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    await page
      .getByTestId('ontology-manage-dialog')
      .getByRole('row', { name: /건축물 대장/ })
      .getByRole('button', { name: '삭제' })
      .click();
    await page.getByRole('button', { name: '삭제', exact: true }).last().click();

    await expect(page.getByText(/2개 데이터셋이 사용 중입니다/)).toBeVisible();
  });

  test('초안 온톨로지는 warning 배너와 활성화 버튼을 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ontology/3', createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }));
    const capture = await mockApi(page, 'PUT', '/api/v1/ontology/3', createOntologySchema(), { capture: true });
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /소방시설 점검/ }).click();

    const banner = page.getByTestId('ontology-status-banner');
    await expect(banner).toHaveAttribute('data-variant', 'warning');
    await expect(banner).toContainText('초안입니다');

    await banner.getByRole('button', { name: '활성화' }).click();
    const req = await capture.waitForRequest();
    expect((req.payload as { status?: string }).status).toBe('active');
  });

  test('은퇴 온톨로지는 info 배너를 보여주고 편집 버튼을 숨긴다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ontology/4', createOntologySchema({ domain: '구 화재조사(2024)' }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /구 화재조사/ }).click();

    const banner = page.getByTestId('ontology-status-banner');
    await expect(banner).toHaveAttribute('data-variant', 'info');
    await expect(banner).toContainText('은퇴한 온톨로지');
    // 은퇴 상태는 편집 불가 — 서버도 409로 거부하므로 버튼 자체를 숨긴다.
    await expect(page.getByRole('button', { name: '편집' })).toBeHidden();
    await expect(banner.getByRole('button', { name: '복귀' })).toBeVisible();
  });

  test('엔티티가 없으면 빈 상태 CTA가 편집기를 연다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ontology/3', createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /소방시설 점검/ }).click();

    await expect(page.getByText('아직 엔티티 타입이 없습니다')).toBeVisible();
    await page.getByRole('button', { name: '엔티티 타입 정의하기' }).click();
    await expect(page.getByTestId('ontology-edit-dialog')).toBeVisible();
  });

  test('편집기 저장이 선택된 온톨로지 id로 전송된다', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema({ domain: '건축물 대장', schemaVersion: 3 });
    await mockApi(page, 'GET', '/api/v1/ontology/2', schema);
    // 전역 PUT /ontology가 아니라 id 스코프 PUT이어야 한다 — 아니면 id=1을 덮어쓴다.
    const capture = await mockApi(page, 'PUT', '/api/v1/ontology/2', schema, { capture: true });
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();
    await page.getByRole('button', { name: '편집' }).click();

    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-edit-Incident').getByLabel('설명').fill('수정됨');
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    expect((req.payload as typeof schema).entities.find((e) => e.type === 'Incident')?.description).toBe('수정됨');
  });
});
