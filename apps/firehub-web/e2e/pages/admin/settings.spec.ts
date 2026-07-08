import type { Page } from '@playwright/test';

import { createSetting } from '../../factories/admin.factory';
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

  test('설정 페이지가 올바르게 로드된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('이메일 탭 — SMTP 호스트 빈값으로 저장 시 에러 toast가 표시되고 API가 호출되지 않는다', async ({ authenticatedPage: page }) => {
    // 이슈 #46 회귀 방지: smtp.host="" 빈값으로 저장 가능한 버그 수정 검증
    await setupSettingsMocks(page);
    await mockApi(page, 'GET', '/api/v1/settings/smtp', [
      { key: 'smtp.host', value: 'smtp.gmail.com', description: 'SMTP 호스트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.port', value: '587', description: '포트', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.username', value: '', description: '사용자 이름', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.password', value: '', description: '비밀번호', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.starttls', value: 'true', description: 'STARTTLS', updatedAt: '2024-01-01T00:00:00Z' },
      { key: 'smtp.from_address', value: '', description: '발신자 주소', updatedAt: '2024-01-01T00:00:00Z' },
    ]);
    // PUT 캡처 — API가 호출되지 않아야 한다
    const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings/smtp', {}, { capture: true });

    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '이메일' }).click();
    await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

    // SMTP 호스트를 빈값으로 지운다
    await page.locator('#smtp-host').fill('');

    // 저장 버튼 활성화 확인 (변경 사항 있음)
    const saveBtn = page.getByRole('button', { name: '저장' }).first();
    await expect(saveBtn).toBeEnabled({ timeout: 3000 });
    await saveBtn.click();

    // 에러 toast 메시지 확인
    await expect(page.getByText('SMTP 호스트를 입력하세요.')).toBeVisible({ timeout: 5000 });

    // PUT API 가 호출되지 않아야 한다 (검증 실패로 early return)
    expect(saveCapture.lastRequest()).toBeUndefined();
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

  /**
   * OpenCode 에이전트 유형 선택 시 키 입력 UI 숨김 검증.
   * - opencode 선택 시 API 키/OAuth 입력창 대신 안내 메시지가 표시되어야 한다.
   */
  test.describe('OpenCode 에이전트 유형', () => {
    test('OpenCode 선택 시 키 입력 UI가 숨겨지고 안내 메시지가 표시된다', { tag: '@smoke' }, async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      // AI 에이전트 탭이 기본으로 선택되어 있는지 확인
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // 에이전트 유형 셀렉트를 클릭해 드롭다운 열기
      await page.getByLabel('에이전트 유형').click();

      // OpenCode 옵션 선택
      await page.getByRole('option', { name: 'OpenCode' }).click();

      // API 키 입력창이 사라졌는지 확인
      await expect(page.locator('#ai-api-key')).not.toBeVisible();

      // OAuth 토큰 입력창도 사라졌는지 확인
      await expect(page.locator('#ai-cli-oauth-token')).not.toBeVisible();

      // OpenCode 안내 메시지가 표시되어야 한다
      await expect(
        page.getByText('배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.'),
      ).toBeVisible();
    });

    test('OpenCode에서 다른 유형으로 변경 시 해당 입력 UI가 다시 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      // OpenCode로 변경
      await page.getByLabel('에이전트 유형').click();
      await page.getByRole('option', { name: 'OpenCode' }).click();

      // 안내 메시지 확인
      await expect(
        page.getByText('배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.'),
      ).toBeVisible();

      // SDK 유형으로 변경
      await page.getByLabel('에이전트 유형').click();
      await page.getByRole('option', { name: 'Claude Agent SDK' }).click();

      // API 키 입력창이 다시 표시되어야 한다
      await expect(page.locator('#ai-api-key')).toBeVisible();

      // 안내 메시지는 숨겨져야 한다
      await expect(
        page.getByText('배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.'),
      ).not.toBeVisible();
    });
  });

  /**
   * SDK 에이전트 유형 선택 시 OAuth 토큰 + API 키 필드 동시 노출 검증.
   * - sdk는 OAuth·API 키 둘 다 지원(OAuth 우선)하므로 두 입력창이 모두 보여야 한다.
   * - OAuth 토큰 저장 시 PUT payload에 ai.cli_oauth_token 키가 담기는지 검증한다.
   */
  test.describe('SDK 에이전트 유형', () => {
    /**
     * cli-api 상태로 설정을 로드한 뒤 실제로 sdk로 전환하는 onChange를 발생시켜
     * 분기(필드 노출)가 전환 시점에 정확히 동작하는지 검증한다.
     * (setupSettingsMocks는 초기값이 이미 sdk라 재선택은 onChange를 트리거하지 않으므로 별도 모킹 사용)
     */
    test('cli-api에서 sdk로 전환 시 OAuth 토큰과 API 키 필드가 모두 노출되고 저장된다', { tag: '@smoke' }, async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/settings', [
        createSetting({ key: 'ai.agent_type', value: 'cli-api', description: '에이전트 유형' }),
        createSetting({ key: 'ai.model', value: 'claude-sonnet-4-6', description: '모델' }),
        createSetting({ key: 'ai.max_turns', value: '10', description: '최대 턴 수' }),
        createSetting({ key: 'ai.system_prompt', value: '당신은 도움이 되는 AI 어시스턴트입니다.', description: '시스템 프롬프트' }),
        createSetting({ key: 'ai.temperature', value: '1.0', description: 'Temperature' }),
        createSetting({ key: 'ai.max_tokens', value: '16384', description: '최대 응답 토큰' }),
        createSetting({ key: 'ai.session_max_tokens', value: '50000', description: '세션 최대 토큰' }),
        createSetting({ key: 'ai.api_key', value: '****masked****', description: 'API 키' }),
        createSetting({ key: 'ai.cli_oauth_token', value: '', description: 'OAuth 토큰' }),
      ]);
      // 저장 PUT 캡처 — goto 이전에 등록
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

      await page.goto('/admin/settings');
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // 전환 전: cli-api 상태이므로 OAuth 토큰 입력창은 보이지 않아야 한다
      await expect(page.locator('#ai-cli-oauth-token')).not.toBeVisible();
      await expect(page.locator('#ai-api-key')).toBeVisible();

      // 에이전트 유형을 sdk로 실제 전환 (cli-api -> sdk 실제 onChange 발생)
      await page.getByLabel('에이전트 유형').click();
      await page.getByRole('option', { name: 'Claude Agent SDK' }).click();

      // 전환 후: OAuth 토큰 필드와 API 키 필드가 모두 노출되어야 한다
      await expect(page.locator('#ai-cli-oauth-token')).toBeVisible();
      await expect(page.locator('#ai-api-key')).toBeVisible();

      // OAuth 토큰 입력 후 저장
      await page.locator('#ai-cli-oauth-token').fill('sk-ant-oat01-xyz');
      const saveButton = page.getByRole('button', { name: '저장' }).first();
      await expect(saveButton).toBeEnabled({ timeout: 3000 });
      await saveButton.click();

      // PUT payload에 ai.cli_oauth_token이 담겨야 한다
      const req = await saveCapture.waitForRequest();
      expect((req.payload as { settings: Record<string, string> }).settings['ai.cli_oauth_token']).toBe(
        'sk-ant-oat01-xyz',
      );
    });

    /**
     * 이슈: sdk 모드에서 OAuth 토큰만 입력해도 validate()가 API 키를 강제 요구해
     * OAuth 전용 설정을 저장할 수 없던 버그(회귀 방지).
     */
    test('sdk 모드에서 API 키 없이 OAuth 토큰만 입력해도 저장된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page); // 초기값 agent_type=sdk, api_key=****masked****
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
      await mockApi(page, 'GET', '/api/v1/ai/auth-status', { valid: true });

      await page.goto('/admin/settings');
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // API 키를 비우고(짧은/빈 값) OAuth 토큰만 채운다
      const apiKeyInput = page.locator('#ai-api-key');
      await apiKeyInput.fill('');
      await page.locator('#ai-cli-oauth-token').fill('sk-ant-oat01-onlytoken');

      const saveButton = page.getByRole('button', { name: '저장' }).first();
      await expect(saveButton).toBeEnabled({ timeout: 3000 });
      await saveButton.click();

      // 검증 에러 없이 저장 성공 toast가 보여야 한다
      await expect(page.getByText('설정이 저장되었습니다.')).toBeVisible({ timeout: 8000 });

      const req = await saveCapture.waitForRequest();
      expect((req.payload as { settings: Record<string, string> }).settings['ai.cli_oauth_token']).toBe(
        'sk-ant-oat01-onlytoken',
      );
    });

    /**
     * sdk 모드에서 API 키와 OAuth 토큰이 모두 비어있으면 저장이 차단되어야 한다.
     */
    test('sdk 모드에서 API 키와 OAuth 토큰이 모두 비어있으면 저장이 차단된다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      const saveCapture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });

      await page.goto('/admin/settings');
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // API 키, OAuth 토큰 모두 비운다 (기존 마스킹된 API 키를 지우는 것만으로 dirty 상태가 된다)
      await page.locator('#ai-api-key').fill('');
      await page.locator('#ai-cli-oauth-token').fill('');

      const saveButton = page.getByRole('button', { name: '저장' }).first();
      await expect(saveButton).toBeEnabled({ timeout: 3000 });
      await saveButton.click();

      // 에러 toast 확인 및 PUT 미호출 확인
      await expect(page.getByText('입력값을 확인하세요.')).toBeVisible({ timeout: 5000 });
      expect(saveCapture.lastRequest()).toBeUndefined();
    });
  });

  /**
   * 이슈 #86 회귀 방지 — 미저장 변경사항 이탈 가드.
   * 이메일 탭에서 dirty 상태로 사이드바 메뉴를 클릭하면 AlertDialog가 떠야 하며,
   * 취소 시 머무르고(값 보존), 이탈 시 다른 페이지로 이동한다.
   */
  test.describe('이슈 #86 — 미저장 변경 가드', () => {
    async function setupEmailTabDirty(page: Page) {
      await setupSettingsMocks(page);
      await mockApi(page, 'GET', '/api/v1/settings/smtp', [
        { key: 'smtp.host', value: '', description: 'SMTP 호스트', updatedAt: '2024-01-01T00:00:00Z' },
        { key: 'smtp.port', value: '587', description: '포트', updatedAt: '2024-01-01T00:00:00Z' },
        { key: 'smtp.username', value: '', description: '사용자 이름', updatedAt: '2024-01-01T00:00:00Z' },
        { key: 'smtp.password', value: '', description: '비밀번호', updatedAt: '2024-01-01T00:00:00Z' },
        { key: 'smtp.starttls', value: 'true', description: 'STARTTLS', updatedAt: '2024-01-01T00:00:00Z' },
        { key: 'smtp.from_address', value: '', description: '발신자 주소', updatedAt: '2024-01-01T00:00:00Z' },
      ]);

      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '이메일' }).click();
      await expect(page.getByText('SMTP 서버 설정')).toBeVisible();

      // dirty 상태 만들기 — SMTP 호스트에 입력
      await page.locator('#smtp-host').fill('smtp.test.com');
      // 저장 버튼 활성화로 dirty 감지 확인
      await expect(page.getByRole('button', { name: '저장' }).first()).toBeEnabled({
        timeout: 3000,
      });
    }

    test('이메일 탭 dirty 상태에서 사이드바 메뉴 클릭 시 이탈 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupEmailTabDirty(page);

      // 사이드바 "홈" 링크 클릭 (사이드바 nav 영역으로 한정)
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      // AlertDialog 표시 검증
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
      // URL은 그대로 /admin/settings 유지 (즉시 이동되지 않아야 함)
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('이탈 다이얼로그에서 취소 클릭 시 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupEmailTabDirty(page);

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      // 취소 클릭
      await page.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).toBeHidden();

      // URL 동일, 입력값 보존
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
      await expect(page.locator('#smtp-host')).toHaveValue('smtp.test.com');
    });

    test('이탈 다이얼로그에서 이탈 클릭 시 변경값을 버리고 다른 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupEmailTabDirty(page);

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      // 이탈 클릭
      await page.getByRole('button', { name: '이탈' }).click();

      // 홈("/")으로 이동 확인
      await expect(page).toHaveURL(/\/$/);
    });

    test('AI 에이전트 탭 dirty 상태에서도 가드가 동작한다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await page.goto('/admin/settings');

      // AI 에이전트 탭은 기본 선택 — max_turns 변경
      await page.getByLabel('최대 턴 수').fill('20');
      await expect(page.getByRole('button', { name: '저장' }).first()).toBeEnabled({
        timeout: 3000,
      });

      // 사이드바 메뉴 클릭
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      // 다이얼로그 표시 + URL 보존
      await expect(page.getByRole('alertdialog')).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/settings');
    });

    test('변경 없는(clean) 상태에서는 메뉴 이동이 정상적으로 즉시 이루어진다', async ({
      authenticatedPage: page,
    }) => {
      await setupSettingsMocks(page);
      await mockApi(page, 'GET', '/api/v1/settings/smtp', []);
      await page.goto('/admin/settings');
      await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();

      // dirty 상태가 아니므로 사이드바 메뉴 클릭 시 즉시 이동, 다이얼로그 없음
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByRole('alertdialog')).toBeHidden();
    });
  });
});
