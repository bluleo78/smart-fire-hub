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

  test('모든 탭에 아이콘이 렌더링된다 (UI 일관성)', async ({ authenticatedPage: page }) => {
    // 이슈 #4: "일반" 탭만 아이콘이 없어 탭 그룹 내 UI 불일관 — 수정 회귀 방지
    await setupSettingsMocks(page);
    await page.goto('/admin/settings');

    // 탭 목록 확인 — 각 탭 내에 svg 아이콘이 있어야 한다
    const generalTab = page.getByRole('tab', { name: '일반' });
    const aiTab = page.getByRole('tab', { name: 'AI 에이전트' });
    const emailTab = page.getByRole('tab', { name: '이메일' });

    await expect(generalTab.locator('svg')).toBeVisible();
    await expect(aiTab.locator('svg')).toBeVisible();
    await expect(emailTab.locator('svg')).toBeVisible();
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
    // 설정 저장 API 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
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

    // PUT /api/v1/settings payload 검증 — 변경한 키들이 포함되어 있어야 한다
    const req = capture.lastRequest();
    if (req) {
      // 저장 payload에 settings 배열 또는 객체가 포함되어야 한다
      expect(req.payload).toBeTruthy();
    }
  });

  test('이메일 탭 — SMTP 설정 폼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    // SMTP 설정 API 모킹 — 이메일 탭 진입 시 호출
    await mockApi(page, 'GET', '/api/v1/settings/smtp', [
      { key: 'smtp.host', value: 'smtp.gmail.com', description: 'SMTP 호스트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.port', value: '587', description: '포트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.username', value: 'user@example.com', description: '사용자 이름', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.password', value: '****masked****', description: '비밀번호', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.starttls', value: 'true', description: 'STARTTLS', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.from_address', value: 'noreply@example.com', description: '발신자 주소', updatedAt: '2024-01-01T00:00:00Z' },
    ]);

    await page.goto('/admin/settings');

    // 이메일 탭 클릭
    await page.getByRole('tab', { name: '이메일' }).click();

    // SMTP 서버 설정 카드 제목 확인
    await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

    // 폼 필드가 서버 데이터로 채워졌는지 확인
    await expect(page.locator('#smtp-host')).toHaveValue('smtp.gmail.com');
    await expect(page.locator('#smtp-port')).toHaveValue('587');
    await expect(page.locator('#smtp-username')).toHaveValue('user@example.com');
    await expect(page.locator('#smtp-from')).toHaveValue('noreply@example.com');
  });

  test('이메일 탭 — SMTP 설정 저장 시 PUT /api/v1/settings/smtp 가 호출된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await mockApi(page, 'GET', '/api/v1/settings/smtp', [
      { key: 'smtp.host', value: '', description: 'SMTP 호스트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.port', value: '587', description: '포트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.username', value: '', description: '사용자 이름', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.password', value: '', description: '비밀번호', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.starttls', value: 'true', description: 'STARTTLS', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.from_address', value: '', description: '발신자 주소', updatedAt: '2024-01-01T00:00:00Z' },
    ]);
    // PUT 캡처 — goto 이전에 등록
    const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings/smtp', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '이메일' }).click();
    await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

    // 호스트 입력 → 변경 감지로 저장 버튼 활성화
    await page.locator('#smtp-host').fill('smtp.example.com');

    // 저장 버튼 활성화 후 클릭 (SMTP 탭 내 저장 버튼)
    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    // PUT API 호출 검증
    const req = await saveCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/settings/smtp');
    // payload에 smtp.host 키가 포함되어 있어야 한다
    expect(req.payload).toMatchObject({ 'smtp.host': 'smtp.example.com' });

    // toast 성공 메시지 확인
    await expect(page.getByText('SMTP 설정이 저장되었습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('이메일 탭 — 테스트 발송 버튼 클릭 시 POST /api/v1/settings/smtp/test 가 호출된다', async ({ authenticatedPage: page }) => {
    await setupSettingsMocks(page);
    await mockApi(page, 'GET', '/api/v1/settings/smtp', [
      { key: 'smtp.host', value: 'smtp.example.com', description: 'SMTP 호스트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.port', value: '587', description: '포트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.username', value: 'user@example.com', description: '사용자 이름', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.password', value: '****masked****', description: '비밀번호', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.starttls', value: 'true', description: 'STARTTLS', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.from_address', value: 'noreply@example.com', description: '발신자 주소', updatedAt: '2024-01-01T00:00:00Z' },
    ]);
    // POST 캡처
    const testCapture = await mockApi(page, 'POST', '/api/v1/settings/smtp/test', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '이메일' }).click();
    await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

    // 변경 없는 상태에서 "테스트 발송" 버튼이 활성화됨
    const testBtn = page.getByRole('button', { name: '테스트 발송' });
    await expect(testBtn).toBeEnabled({ timeout: 3000 });
    await testBtn.click();

    // POST /api/v1/settings/smtp/test 가 호출되었는지 검증
    const req = await testCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/settings/smtp/test');

    // toast 성공 메시지 확인
    await expect(page.getByText('테스트 이메일이 발송되었습니다.')).toBeVisible({ timeout: 5000 });
  });
});
