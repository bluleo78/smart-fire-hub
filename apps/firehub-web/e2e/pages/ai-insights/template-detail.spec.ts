import { createTemplate } from '../../factories/ai-insight.factory';
import { setupTemplateDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 템플릿 상세 페이지 E2E 테스트
 * - 템플릿 로드, 빌트인/커스텀 구분, 새 템플릿 생성 폼을 검증한다.
 * - TemplateJsonEditor(CodeMirror) 비동기 렌더링으로 인해 데이터 로딩 대기가 필요하다.
 */
test.describe('리포트 템플릿 상세 페이지', () => {
  test('커스텀 템플릿 상세가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 커스텀 템플릿(builtin: false)으로 모킹
    // createTemplate 기본값 sections: [{key:'summary', label:'요약'}, {key:'details', label:'상세 내용'}]
    const template = createTemplate({ id: 1, name: '기본 리포트 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/1', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 헤더에 표시되는지 확인 — 네트워크 요청 완료 후 데이터가 렌더링된다
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // "커스텀" 뱃지 확인 (builtin: false)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '커스텀' })).toBeVisible();

    // 팩토리 sections의 레이블 '요약', '상세 내용'이 JSON 구조 뷰 또는 섹션 미리보기에 표시되는지 확인
    // TemplateJsonEditor(CodeMirror)가 JSON을 렌더링하면 section label이 텍스트로 포함된다
    // 여러 요소에 매칭될 수 있으므로 .first()를 사용해 strict mode 위반을 방지한다
    await expect(page.getByText('요약').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('상세 내용').first()).toBeVisible({ timeout: 10000 });
  });

  test('빌트인 템플릿에는 "기본" 뱃지가 표시되고 편집/삭제 버튼이 없다', async ({ authenticatedPage: page }) => {
    // setupTemplateDetailMocks는 createTemplate({ id: 1 }) 기본값(builtin: true)을 사용한다
    await setupTemplateDetailMocks(page, 1);

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 렌더링될 때까지 대기 (팩토리 기본값: "기본 리포트 템플릿")
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // "기본" 뱃지 확인 (builtin: true)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '기본' })).toBeVisible();

    // 빌트인 템플릿에는 편집/삭제 버튼이 없어야 한다 (isBuiltin 조건으로 숨겨짐)
    await expect(page.getByRole('button', { name: '편집' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: '삭제' })).not.toBeVisible();
  });

  test('커스텀 템플릿에는 편집 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹
    const template = createTemplate({ id: 2, name: '커스텀 리포트', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/2', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/2');

    // 템플릿 이름이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '커스텀 리포트' })).toBeVisible({ timeout: 10000 });

    // 커스텀 템플릿에는 편집 버튼이 표시된다
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible();

    // 삭제 버튼도 표시된다
    await expect(page.getByRole('button', { name: '삭제' })).toBeVisible();
  });

  test('새 템플릿 페이지에서 생성 폼이 표시된다', async ({ authenticatedPage: page }) => {
    // 새 템플릿 페이지 API 모킹 (템플릿 목록만 필요)
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/new');

    // "새 템플릿" 헤더 확인
    await expect(page.getByRole('heading', { name: '새 템플릿' })).toBeVisible();

    // 이름 입력 필드 확인
    await expect(page.locator('#tpl-name')).toBeVisible();

    // 설명 입력 필드 확인 (id="tpl-desc")
    await expect(page.locator('#tpl-desc')).toBeVisible();

    // 작성 스타일 입력 필드 확인 (id="tpl-style")
    await expect(page.locator('#tpl-style')).toBeVisible();

    // "생성" 버튼 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
  });

  test('삭제 버튼 클릭 시 삭제 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹
    const template = createTemplate({ id: 3, name: '삭제 대상 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/3', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // DELETE API 캡처 — 다이얼로그 확인 버튼 클릭 시 호출되는지 검증
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/templates/3', {}, { capture: true });

    await page.goto('/ai-insights/templates/3');

    // 삭제 버튼이 나타날 때까지 대기 (템플릿 데이터 로드 후 표시)
    await expect(page.getByRole('button', { name: '삭제' })).toBeVisible({ timeout: 10000 });

    // 삭제 버튼 클릭
    await page.getByRole('button', { name: '삭제' }).click();

    // 삭제 확인 다이얼로그 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('템플릿 삭제')).toBeVisible();

    // 다이얼로그 내 확인(삭제) 버튼 클릭 — 역할/텍스트 기반으로 탐색
    const confirmButton = page.getByRole('dialog').getByRole('button', { name: /삭제|확인/ }).last();
    await confirmButton.click();

    // DELETE /api/v1/proactive/templates/3 가 실제로 호출되었는지 확인
    const deletedReq = await deleteCapture.waitForRequest();
    expect(deletedReq).toBeDefined();
  });
});
