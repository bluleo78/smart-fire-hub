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
    // 전체 문서 모달과 그 "편집" 버튼은 Task 6에서 제거됐다 — 유일한 편집 진입점인 "수정 모드"
    // 토글의 비-ADMIN 숨김을 대신 확인한다.
    await expect(page.getByRole('button', { name: '수정 모드' })).toBeHidden();

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

  test('은퇴는 확인 다이얼로그를 거친 뒤 전용 PATCH로 status=archived만 보낸다 (#399)', async ({
    authenticatedPage: page,
  }) => {
    // 전이는 스키마 편집(PUT)이 아니라 PATCH /ontology/{id}/status 전용 경로다 — PUT으로 가면
    // schema_version이 올라가 적재 노드가 전부 "구버전"으로 뒤집힌다.
    const capture = await mockApi(page, 'PATCH', '/api/v1/ontology/2/status', null, { status: 204, capture: true });
    // PUT이 호출되면 실패해야 한다 — 낡은 경로로 되돌아가는 회귀를 잡는 가드.
    const putCapture = await mockApi(page, 'PUT', '/api/v1/ontology/2', createOntologySchema(), { capture: true });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    const row = page.getByTestId('ontology-manage-dialog').getByRole('row', { name: /건축물 대장/ });
    await row.getByRole('button', { name: '은퇴' }).click();

    // 삭제와 마찬가지로 확인 다이얼로그가 뜨고, 버튼 클릭만으로는 아직 요청이 나가지 않아야 한다(#399).
    const confirmDialog = page.getByRole('alertdialog');
    await expect(confirmDialog).toBeVisible();
    expect(capture.requests).toHaveLength(0);

    await confirmDialog.getByRole('button', { name: '은퇴', exact: true }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ status: 'archived' });
    expect(putCapture.requests).toHaveLength(0);
  });

  test('은퇴 확인 다이얼로그는 참조 중인 데이터셋 수를 고지한다 (#399)', async ({ authenticatedPage: page }) => {
    // 참조가 있어도 은퇴 자체는 항상 허용된다(백엔드는 기본 온톨로지 여부만 검사) — 프론트가
    // 확인 다이얼로그에서 영향 범위(참조 데이터셋 수)를 보여줘 실수 클릭을 막는지만 검증한다.
    await mockApi(page, 'GET', '/api/v1/ontologies', [
      {
        id: 7,
        domain: '설비 점검',
        schemaVersion: 2,
        status: 'active',
        entityCount: 5,
        datasetCount: 4,
        updatedAt: '2026-07-01T09:00:00Z',
        isDefault: false,
      },
    ]);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /온톨로지 관리/ }).click();

    await page
      .getByTestId('ontology-manage-dialog')
      .getByRole('row', { name: /설비 점검/ })
      .getByRole('button', { name: '은퇴' })
      .click();

    await expect(page.getByRole('alertdialog')).toContainText('4개 데이터셋이 이 온톨로지를 사용 중입니다');
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
    const capture = await mockApi(page, 'PATCH', '/api/v1/ontology/3/status', null, { status: 204, capture: true });
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /소방시설 점검/ }).click();

    const banner = page.getByTestId('ontology-status-banner');
    await expect(banner).toHaveAttribute('data-variant', 'warning');
    await expect(banner).toContainText('초안입니다');

    await banner.getByRole('button', { name: '활성화' }).click();
    const req = await capture.waitForRequest();
    // 본문은 status 하나뿐이어야 한다 — 스키마를 실어 보내면 전이 경로가 다시 본문 조회에 묶인다.
    expect(req.payload).toEqual({ status: 'active' });
  });

  test('빈 초안 활성화가 400이면 서버 사유를 토스트로 보여준다', async ({ authenticatedPage: page }) => {
    // 완전성 게이트는 서버에만 있다 — 프론트가 본문을 안 보내게 된 뒤로는 더더욱 서버 메시지가 유일한 안내다.
    await mockApi(page, 'GET', '/api/v1/ontology/3', createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }));
    await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/3/status',
      { message: '엔티티 타입은 최소 1개 이상이어야 합니다.' },
      { status: 400 },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /소방시설 점검/ }).click();
    await page.getByTestId('ontology-status-banner').getByRole('button', { name: '활성화' }).click();

    await expect(page.getByText('엔티티 타입은 최소 1개 이상이어야 합니다.')).toBeVisible();
  });

  test('은퇴 온톨로지는 info 배너를 보여주고 편집 버튼을 숨긴다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ontology/4', createOntologySchema({ domain: '구 화재조사(2024)' }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /구 화재조사/ }).click();

    const banner = page.getByTestId('ontology-status-banner');
    await expect(banner).toHaveAttribute('data-variant', 'info');
    await expect(banner).toContainText('은퇴한 온톨로지');
    // 은퇴 상태는 편집 불가 — 서버도 409로 거부하므로 버튼 자체를 숨긴다. 전체 문서 모달의 "편집"
    // 버튼은 Task 6에서 제거됐으므로 그 자리를 대체한 "수정 모드" 토글로 같은 게이팅을 확인한다.
    await expect(page.getByRole('button', { name: '수정 모드' })).toBeHidden();
    await expect(banner.getByRole('button', { name: '복귀' })).toBeVisible();
  });

  // Task 6 이전에는 이 CTA가 전체 문서 모달(OntologyEditDialog)을 열었다 — 모달이 사라지면서
  // "편집기를 연다"는 이제 수정 모드를 켜는 것을 뜻한다. S3 Task 4부터는 수정 모드와 함께 생성 폼도
  // 곧바로 열려("첫 타입 만들기") 아웃라인의 "타입 추가"를 다시 찾지 않고 바로 입력할 수 있는지까지
  // 입력→API→UI로 검증한다(빈 상태였던 캔버스가 SchemaGraph로 바뀌는 것까지 — CTA 클릭만으로는
  // 아무것도 증명하지 않는다). CTA 문구·예시 트리플 자체의 상세 검증은 ontology-canvas.spec.ts의
  // 빈 상태 테스트가 맡는다 — 여기는 이 도메인(생명주기 전환에 따른 온톨로지 선택)과 결합된 시나리오다.
  test('엔티티가 없으면 빈 상태 CTA가 수정 모드와 생성 폼을 함께 열고, 첫 타입을 만들면 캔버스가 나타난다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/ontology/3', createOntologySchema({ domain: '소방시설 점검', entities: [], relations: [] }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: /소방시설 점검/ }).click();

    await expect(page.getByText('아직 엔티티 타입이 없습니다')).toBeVisible();
    await page.getByRole('button', { name: '첫 타입 만들기' }).click();

    // 수정 모드가 켜지고 생성 폼이 곧바로 열린다 — 엔티티가 0개라 캔버스 자리는 빈 상태 그대로지만,
    // 인스펙터 pane에는 이미 "새 타입 만들기" 폼이 떠 있다(아웃라인의 "타입 추가"를 다시 누를 필요가 없다).
    await expect(page.getByRole('button', { name: '수정 모드' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('entity-inspector-create')).toBeVisible();

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/3/entity-types',
      { schemaVersion: 2, entityType: { id: 1, type: 'Building', description: '', naming: '', resolution: 'embedding', properties: [] } },
      { capture: true },
    );
    await page.getByLabel('타입 이름').fill('Building');
    // exact: true — 엔티티가 여전히 0개인 동안(POST 응답 전) 빈 상태 CTA("첫 타입 만들기")도 함께
    // 떠 있어 부분 일치로는 strict mode 충돌이 난다.
    await page.getByRole('button', { name: '타입 만들기', exact: true }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ type: 'Building', description: '', naming: '', resolution: 'embedding' });
    // 엔티티가 생겼으니 빈 상태 대신 스키마 캔버스가 그려진다.
    await expect(page.getByText('아직 엔티티 타입이 없습니다')).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toBeVisible();
  });

  // 이전에는 전역 PUT /ontology가 아니라 id 스코프 PUT인지를 검증했다(#id=1을 실수로 덮어쓰는 회귀
  // 가드). 요소 단위 편집(S2)으로 바뀐 뒤에도 같은 위험이 남는다 — useOntologyElementMutations가
  // 기본 온톨로지(defaultOntologyId)가 아니라 실제 선택된 온톨로지(effectiveOntologyId)로 바인딩돼야
  // 한다. 그래서 기본이 아닌 온톨로지(id=2)를 선택한 채로 편집해 PATCH가 /ontology/2/entity-types/…로
  // 나가는지 확인한다 — 실수로 defaultOntologyId(id=1)에 바인딩됐다면 이 요청은 /ontology/1/…로
  // 나가 이 테스트가 잡아낸다.
  test('요소 편집이 선택된 온톨로지 id로 전송된다(기본 온톨로지가 아니어도)', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema({ domain: '건축물 대장', schemaVersion: 3 });
    await mockApi(page, 'GET', '/api/v1/ontology/2', schema);
    const capture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/2/entity-types/1',
      { schemaVersion: 4, entityType: { ...schema.entities[0], description: '수정됨' } },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId('outline-entity-1').click();

    await page.getByLabel('설명', { exact: true }).fill('수정됨');
    await page.getByLabel('설명', { exact: true }).blur();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ description: '수정됨' });
  });
});
