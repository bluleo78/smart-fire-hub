import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 설정 페이지 E2E 테스트
 * - 설정 로드, 탭 전환, 저장 버튼 동작을 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
test.describe('설정 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('설정 페이지가 올바르게 로드된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '설정' })).toBeVisible();

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '일반' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '이메일' })).toBeVisible();
  });

  test('AI 에이전트 탭이 기본으로 선택되고 설정 항목이 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // AI 에이전트 탭이 기본 선택되어 있음 (defaultValue="ai")
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    // 모델 설정 카드 확인
    await expect(page.getByText('모델 설정')).toBeVisible();

    // 에이전트 유형 셀렉트 확인
    await expect(page.getByLabel('에이전트 유형')).toBeVisible();
  });

  test('일반 탭 클릭 시 탭 내용이 전환된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 일반 탭 클릭
    await page.getByRole('tab', { name: '일반' }).click();

    // 일반 탭 내용 확인 ("준비 중입니다" 메시지)
    await expect(page.getByText('준비 중입니다')).toBeVisible();
  });

  test('설정값 변경 시 저장 버튼이 활성화된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 초기 상태에서 저장 버튼 비활성화 확인 (변경 사항 없음)
    await expect(page.getByRole('button', { name: '저장' })).toBeDisabled();

    // 최대 턴 수 필드 값 변경
    await page.getByLabel('최대 턴 수').fill('15');

    // 변경 후 저장 버튼 활성화 확인
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
  });

  test('되돌리기 버튼 클릭 시 변경 사항이 초기화된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 최대 턴 수 필드 변경
    const maxTurnsInput = page.getByLabel('최대 턴 수');
    await maxTurnsInput.fill('20');

    // 되돌리기 버튼 클릭
    await page.getByRole('button', { name: '되돌리기' }).click();

    // 원래 값(10)으로 복원되는지 확인
    await expect(maxTurnsInput).toHaveValue('10');
  });

  test('저장 성공 시 toast 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    // 설정 저장 API 모킹
    await mockApi(page, 'PUT', '/api/v1/settings', {});
    // verifyAuth 호출 모킹
    await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

    await page.goto('/admin/settings');

    // AI 에이전트 탭이 기본으로 선택되어 있음 — 탭 로드 대기
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

    // API 키 입력 (validate: api_key가 비어있으면 저장 불가)
    // getByLabel은 "API 키 보기" 버튼도 매칭하므로 id로 명시적 선택
    const apiKeyInput = page.locator('#ai-api-key');
    await apiKeyInput.fill('sk-ant-test-key-12345');

    // 시스템 프롬프트 입력 (validate: 비어있으면 저장 불가)
    const systemPromptTextarea = page.getByPlaceholder('시스템 프롬프트를 입력하세요...');
    await systemPromptTextarea.fill('당신은 도움이 되는 AI 어시스턴트입니다.');

    // 저장 버튼이 활성화될 때까지 대기 후 클릭
    const saveButton = page.getByRole('button', { name: '저장' }).first();
    await expect(saveButton).toBeEnabled({ timeout: 3000 });
    await saveButton.click();

    // Sonner toast 메시지 확인
    await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });
  });
});
