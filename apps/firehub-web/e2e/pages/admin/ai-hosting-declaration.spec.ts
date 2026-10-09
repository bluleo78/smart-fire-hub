import type { Page } from '@playwright/test';

import {
  createAiClassifyCredential,
  createAiCredential,
  createAiSettings,
  createEmbeddingConfig,
} from '../../factories/admin.factory';
import {
  mockAiClassifyCredential,
  mockAiCredential,
  setupAdminAuth,
  setupEmbeddingMocks,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * S3 §5-5 — AI 공급자 호스팅 위치 선언(채팅·분류 자격증명, 임베딩).
 *
 * 서버 계약(Task 1 4171b61d + 231008d7): 자격증명 `payload.hosting`/임베딩 `hosting` 은 'EXTERNAL'|'SELF_HOSTED',
 * Claude 계열의 SELF_HOSTED 는 400, 자체 호스팅으로 "올리는" 선언은 security:settings 가 없으면 403
 * HOSTING_DECLARATION_FORBIDDEN, 전송 대상(공급자·주소)이 바뀌면 선언은 유지되지 않는다. 화면은 같은 규칙으로 먼저
 * 잠그고(권한) 되돌리고(대상 변경) 알린다 — 그래서 저장 바디의 hosting 을 셀 단위로 단언한다.
 */

/** 자체 호스팅 선언 권한(security:settings)이 없는 사용자 — setupAdminAuth 의 권한 목록을 덮어쓴다(나중 route 가 이긴다). */
async function withoutSecuritySettings(page: Page) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read', 'role:read', 'dataset:read', 'ai:settings']);
}

/** 권한 없음 안내(라디오 aria-describedby 본문). */
const NO_PERMISSION_HINT = '보안 설정 권한이 있어야 자체 호스팅으로 선언할 수 있습니다.';
/** 권한 없는 사용자에게 보이는 강등 안내 — 다시 선언하라는 대신 요청 경로를 알린다. */
const DEMOTED_NO_PERMISSION =
  '공급자나 주소가 바뀌어 호스팅 위치를 외부 서비스로 되돌렸습니다. 새 주소가 조직 내부 서버라면 보안 설정 권한이 있는 관리자에게 자체 호스팅 선언을 요청하세요.';

const credentialGroup = (page: Page) => page.getByRole('group', { name: '자격증명' });
const hostingGroup = (page: Page) => credentialGroup(page).getByRole('radiogroup', { name: '호스팅 위치' });

/** 사설 주소 opencode 자격증명(저장된 상태). */
const corpOpencode = (hosting?: 'EXTERNAL' | 'SELF_HOSTED') =>
  createAiCredential({
    agentType: 'opencode',
    configured: true,
    payload: { providerId: 'openai', baseURL: 'http://10.0.0.5/v1', ...(hosting ? { hosting } : {}) },
    secretFieldNames: ['apiKey'],
  });

test.describe('AI 공급자 호스팅 위치 선언 (S3 §5-5)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page, { ai: createAiSettings() });
  });

  test('Claude 계열은 선택지 없이 읽기 전용 "외부 서비스"', async ({ authenticatedPage: page }) => {
    await mockAiCredential(page, createAiCredential({ agentType: 'sdk', configured: true }));
    await page.goto('/admin/settings');

    await expect(credentialGroup(page).getByText('호스팅 위치', { exact: true })).toBeVisible();
    await expect(credentialGroup(page).getByRole('radiogroup')).toHaveCount(0);
    await expect(credentialGroup(page).getByRole('radio')).toHaveCount(0);
    await expect(credentialGroup(page).getByText('외부 서비스', { exact: true })).toBeVisible();
    await expect(
      credentialGroup(page).getByText(
        "Claude 는 Anthropic 서버에서 실행됩니다. AI 분석 정책이 '자체 호스팅 모델만'인 등급의 데이터에는 사용되지 않습니다.",
      ),
    ).toBeVisible();
  });

  test(
    'opencode 를 자체 호스팅으로 바꾸면 확인 후 payload.hosting=SELF_HOSTED 로 저장한다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      const calls = await mockAiCredential(page, corpOpencode());
      await page.goto('/admin/settings');

      // 저장 문서에 키가 없으면 기본 외부.
      await expect(hostingGroup(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
      await hostingGroup(page).getByRole('radio', { name: '자체 호스팅' }).click();

      const dialog = page.getByRole('alertdialog');
      // 보안 탭 선택지와 같은 정책 이름으로 부르고, 등급 이름은 기본 설정의 예시로만 든다(테넌트가 정책을 바꿀 수 있다).
      await expect(dialog).toContainText(
        "자체 호스팅으로 선언하면 AI 분석 정책이 '자체 호스팅 모델만'인 등급(예: 기본 설정의 민감·기밀)의 데이터가 이 공급자로 전송됩니다.",
      );
      // 사설 주소라 공용 주소 경고는 없다.
      await expect(dialog).not.toContainText('공용 인터넷 주소');
      await dialog.getByRole('button', { name: '자체 호스팅으로 선언' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(hostingGroup(page).getByRole('radio', { name: '자체 호스팅' })).toBeChecked();

      await page.getByRole('button', { name: '저장', exact: true }).click();
      await expect.poll(() => calls.puts.length).toBe(1);
      expect(calls.puts[0]).toEqual({
        agentType: 'opencode',
        payload: { providerId: 'openai', baseURL: 'http://10.0.0.5/v1', reasoningEffort: '', hosting: 'SELF_HOSTED' },
        secret: {},
      });
    },
  );

  test('공용 주소면 확인 창에 경고를 보이고, 취소하면 외부 그대로다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(
      page,
      createAiCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
      }),
    );
    await page.goto('/admin/settings');
    await hostingGroup(page).getByRole('radio', { name: '자체 호스팅' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('이 주소는 공용 인터넷 주소로 보입니다');
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(hostingGroup(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
    // 취소는 아무것도 바꾸지 않는다 — 저장 버튼이 열리지 않는다(미저장 변경 없음).
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
  });

  test('security:settings 가 없으면 자체 호스팅 라디오가 비활성이고 이유를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await withoutSecuritySettings(page);
    await mockAiCredential(page, corpOpencode());
    await page.goto('/admin/settings');

    const self = hostingGroup(page).getByRole('radio', { name: '자체 호스팅' });
    await expect(self).toBeDisabled();
    await expect(self).toHaveAccessibleDescription(NO_PERMISSION_HINT);
    await expect(hostingGroup(page).getByRole('radio', { name: '외부 서비스' })).toBeEnabled();
    // 디자인 시스템 09 Disabled — 비활성 항목의 라벨도 50% 로 흐려진다(Label 의 peer-disabled 는 여기서 듣지 않는다).
    await expect(page.locator('label[for="ai-cred-hosting-self"]')).toHaveCSS('opacity', '0.5');
    await expect(page.locator('label[for="ai-cred-hosting-external"]')).toHaveCSS('opacity', '1');
    // 이유는 본문 한 곳에만 — 호버해도 툴팁이 라벨·선택값을 가리지 않는다.
    await page.locator('label[for="ai-cred-hosting-self"]').hover();
    await expect(page.getByRole('tooltip')).toHaveCount(0);
    await expect(credentialGroup(page).getByText(NO_PERMISSION_HINT, { exact: true })).toHaveCount(1);
  });

  test('권한 없이도 저장된 자체 호스팅 선언은 같은 주소 그대로 유지·저장할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await withoutSecuritySettings(page);
    const calls = await mockAiCredential(page, corpOpencode('SELF_HOSTED'));
    await page.goto('/admin/settings');

    await expect(hostingGroup(page).getByRole('radio', { name: '자체 호스팅' })).toBeChecked();
    // 비밀만 바꾸는 저장 — 서버는 같은 목적지의 기존 선언 유지를 권한 없이 허용한다.
    await page.locator('#ai-cred-opencode-api-key').fill('sk-rotated');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0].payload.hosting).toBe('SELF_HOSTED');
    expect(calls.puts[0].secret).toEqual({ apiKey: 'sk-rotated' });
  });

  test('저장된 자체 호스팅에서 기본 URL 을 바꾸면 외부로 되돌리고 알린 뒤 EXTERNAL 로 저장한다', async ({
    authenticatedPage: page,
  }) => {
    await withoutSecuritySettings(page);
    const calls = await mockAiCredential(page, corpOpencode('SELF_HOSTED'));
    await page.goto('/admin/settings');
    await expect(hostingGroup(page).getByRole('radio', { name: '자체 호스팅' })).toBeChecked();

    await page.locator('#ai-cred-base-url').fill('https://api.openai.com/v1');

    await expect(hostingGroup(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
    // 권한이 없으면 "다시 선언하세요" 대신 요청 경로를 알리고, 이 배너가 비활성 이유를 겸한다(권한 안내 줄은 따로 없다).
    const notice = credentialGroup(page).getByText(DEMOTED_NO_PERMISSION);
    await expect(notice).toBeVisible();
    await expect(credentialGroup(page).getByText(/자체 호스팅으로 다시 선언하세요/)).toHaveCount(0);
    await expect(credentialGroup(page).getByText(NO_PERMISSION_HINT, { exact: true })).toHaveCount(0);
    // 새 목적지에 대한 선언은 "올리는" 변경이라 권한 없는 사용자는 다시 고를 수 없다(서버 403 과 같은 판정).
    const self = hostingGroup(page).getByRole('radio', { name: '자체 호스팅' });
    await expect(self).toBeDisabled();
    await expect(self).toHaveAccessibleDescription(DEMOTED_NO_PERMISSION);

    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0].payload).toMatchObject({ baseURL: 'https://api.openai.com/v1', hosting: 'EXTERNAL' });
  });

  test('권한이 있으면 강등 안내가 자체 호스팅으로 다시 선언하라고 한다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(page, corpOpencode('SELF_HOSTED'));
    await page.goto('/admin/settings');
    await page.locator('#ai-cred-base-url').fill('http://10.0.0.9/v1');

    await expect(hostingGroup(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
    await expect(
      credentialGroup(page).getByText(
        '공급자나 주소가 바뀌어 호스팅 위치를 외부 서비스로 되돌렸습니다. 새 주소가 조직 내부 서버라면 자체 호스팅으로 다시 선언하세요.',
      ),
    ).toBeVisible();
    await expect(hostingGroup(page).getByRole('radio', { name: '자체 호스팅' })).toBeEnabled();
  });

  test('AI 분류 미설정 요약은 이어받은 채팅 설정의 호스팅 위치를 보인다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(page, corpOpencode('SELF_HOSTED'));
    await mockAiClassifyCredential(page);
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: 'AI 분류' }).click();
    const panel = page.getByRole('tabpanel', { name: 'AI 분류' });

    await expect(panel.getByText('AI 에이전트 설정을 사용 중입니다.')).toBeVisible();
    const row = panel.locator('dt', { hasText: '호스팅 위치' });
    await expect(row).toBeVisible();
    await expect(row.locator('xpath=following-sibling::dd[1]')).toHaveText('자체 호스팅');
  });

  test('서버가 403 HOSTING_DECLARATION_FORBIDDEN 으로 거부하면 서버 문구를 그대로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiCredential(page, corpOpencode());
    // 화면 판정과 서버 판정이 어긋난 경우(권한이 그 사이 회수됨 등) — 저장 실패를 조용히 삼키지 않는다.
    const message = '자체 호스팅 선언에는 보안 설정 권한(security:settings)이 필요합니다';
    await page.route(
      (url) => url.pathname === '/api/v1/settings/ai-credential',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 403,
              contentType: 'application/json',
              body: JSON.stringify({ status: 403, code: 'HOSTING_DECLARATION_FORBIDDEN', message }),
            })
          : route.fallback(),
    );
    await page.goto('/admin/settings');
    await hostingGroup(page).getByRole('radio', { name: '자체 호스팅' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '자체 호스팅으로 선언' }).click();
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText(message)).toBeVisible();
    await expect(page.getByText('저장했습니다.')).toHaveCount(0);
  });

  test('AI 분류 탭도 같은 필드로 호스팅 위치를 저장한다', async ({ authenticatedPage: page }) => {
    await mockAiCredential(page, createAiCredential({ agentType: 'cli-api', configured: true, secretFieldNames: ['apiKey'] }));
    const calls = await mockAiClassifyCredential(
      page,
      createAiClassifyCredential({
        agentType: 'opencode',
        configured: true,
        payload: { providerId: 'openai', baseURL: 'http://ollama:11434/v1' },
        secretFieldNames: ['apiKey'],
        model: 'openai/gpt-4o-mini',
      }),
    );
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: 'AI 분류' }).click();
    const panel = page.getByRole('tabpanel', { name: 'AI 분류' });
    const group = panel.getByRole('radiogroup', { name: '호스팅 위치' });

    await group.getByRole('radio', { name: '자체 호스팅' }).click();
    const dialog = page.getByRole('alertdialog');
    // 컨테이너 이름(점 없는 호스트)은 사설로 본다.
    await expect(dialog).not.toContainText('공용 인터넷 주소');
    await dialog.getByRole('button', { name: '자체 호스팅으로 선언' }).click();
    await panel.getByRole('button', { name: '저장' }).click();

    await expect.poll(() => calls.puts.length).toBe(1);
    expect(calls.puts[0]).toMatchObject({ agentType: 'opencode', payload: { hosting: 'SELF_HOSTED' } });
  });

  test.describe('임베딩 탭', () => {
    async function openEmbeddingTab(page: Page) {
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '임베딩' }).click();
      await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    }
    const embeddingHosting = (page: Page) =>
      page.getByRole('tabpanel', { name: '임베딩' }).getByRole('radiogroup', { name: '호스팅 위치' });

    test('자체 호스팅을 선언하면 저장 요청에 hosting 을 싣는다', async ({ authenticatedPage: page }) => {
      const { save } = await setupEmbeddingMocks(page, { config: createEmbeddingConfig() });
      await openEmbeddingTab(page);

      await expect(embeddingHosting(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
      await embeddingHosting(page).getByRole('radio', { name: '자체 호스팅' }).click();
      const dialog = page.getByRole('alertdialog');
      await expect(dialog).not.toContainText('공용 인터넷 주소');
      await dialog.getByRole('button', { name: '자체 호스팅으로 선언' }).click();
      await page.getByRole('button', { name: '저장', exact: true }).click();

      const req = await save.waitForRequest();
      expect(req.payload).toEqual({
        provider: 'OLLAMA',
        model: 'bge-m3',
        baseUrl: 'http://host.docker.internal:11434',
        hosting: 'SELF_HOSTED',
      });
    });

    test('권한이 없으면 비활성, 저장된 자체 호스팅도 Base URL 을 바꾸면 외부로 되돌린다', async ({
      authenticatedPage: page,
    }) => {
      await withoutSecuritySettings(page);
      const { save } = await setupEmbeddingMocks(page, { config: createEmbeddingConfig({ hosting: 'SELF_HOSTED' }) });
      await openEmbeddingTab(page);

      await expect(embeddingHosting(page).getByRole('radio', { name: '자체 호스팅' })).toBeChecked();
      await page.getByLabel('Base URL').fill('https://api.openai.com');
      await expect(embeddingHosting(page).getByRole('radio', { name: '외부 서비스' })).toBeChecked();
      await expect(embeddingHosting(page).getByRole('radio', { name: '자체 호스팅' })).toBeDisabled();
      await expect(page.getByText(DEMOTED_NO_PERMISSION)).toBeVisible();

      await page.getByRole('button', { name: '저장', exact: true }).click();
      const req = await save.waitForRequest();
      expect(req.payload).toMatchObject({ baseUrl: 'https://api.openai.com', hosting: 'EXTERNAL' });
    });
  });
});
