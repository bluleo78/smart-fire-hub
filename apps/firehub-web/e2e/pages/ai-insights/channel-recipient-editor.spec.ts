import type { Page } from '@playwright/test';

import { createJob, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi,type MockApiCapture } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * ChannelRecipientEditor 4채널 확장 E2E 테스트
 * - CHAT/EMAIL/KAKAO/SLACK 4개 채널 체크박스 렌더링 검증
 * - CHAT 채널은 항상 활성화 (disabled) 검증
 * - KAKAO/SLACK 채널 토글 및 수신자 편집 UI 검증
 */

/** 작업 상세 페이지에서 알림 탭을 열고 편집 모드로 진입하는 헬퍼 */
async function setupAndNavigateToNotifyTab(
  page: Page,
  jobConfig: Parameters<typeof createJob>[0] = {},
) {
  const job = createJob({ id: 1, ...jobConfig });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
  await mockApi(page, 'GET', '/api/v1/users', {
    content: [],
    totalElements: 0,
    totalPages: 0,
    page: 0,
    size: 20,
  });

  await page.goto('/ai-insights/jobs/1');
  await page.getByRole('button', { name: '편집' }).click();

  // 알림 탭 클릭
  const notifyTab = page.getByRole('tab', { name: /알림|채널|전달/ });
  if (await notifyTab.isVisible()) {
    await notifyTab.click();
  }
}

// 생성·편집·삭제·복제·실행 버튼은 proactive:write 가 있어야 보인다(WD-133) — 이 spec 은 쓰기 권한 사용자를 전제로 한다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('ChannelRecipientEditor — 4채널 확장', () => {
  test('CHAT/EMAIL/KAKAO/SLACK 4개 체크박스가 모두 렌더링된다', async ({ authenticatedPage: page }) => {
    // CHAT 채널만 활성화된 작업으로 시작
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
      },
    });

    // 4개 체크박스 레이블 확인
    await expect(page.getByLabel('채팅 채널 (항상 활성화)')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('checkbox', { name: /이메일/i })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /카카오 알림톡/i })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /slack/i })).toBeVisible();
  });

  test('CHAT 체크박스는 항상 체크된 상태로 disabled이다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
      },
    });

    // CHAT 체크박스: checked + disabled
    const chatCheckbox = page.getByLabel('채팅 채널 (항상 활성화)');
    await expect(chatCheckbox).toBeVisible({ timeout: 5000 });
    await expect(chatCheckbox).toBeChecked();
    await expect(chatCheckbox).toBeDisabled();
  });

  /** 미지원 채널 카드 — 바깥 래퍼와 겹치지 않게 카드별 data-testid 로 찾는다 (WD-805) */
  const cardOf = (page: Page, type: 'kakao' | 'slack') => page.getByTestId(`channel-card-${type}`);
  const KAKAO_NAME = /카카오 알림톡/;
  const SLACK_NAME = /slack/i;
  // 두 채널 공통 문구라 각 카드로 범위를 좁혀 단언한다
  const NOT_SELECTED = '스마트 작업 결과 전달은 아직 지원하지 않습니다 — 채팅·이메일로 받을 수 있습니다.';
  const WARN = '이 채널은 아직 지원하지 않아 저장할 수 없습니다 — 체크를 해제하세요.';

  // WD-805: 서버 화이트리스트(CHAT/EMAIL/WEBHOOK, #594)가 Slack·카카오를 400 으로 거절하므로 켤 수 없어야 한다.
  test('미지원 채널(카카오·Slack)은 비활성이고 준비 중 사유가 보인다 (WD-805)', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
      },
    });

    const kakaoCheckbox = page.getByRole('checkbox', { name: KAKAO_NAME });
    const slackCheckbox = page.getByRole('checkbox', { name: SLACK_NAME });
    await expect(kakaoCheckbox).toBeDisabled({ timeout: 5000 });
    await expect(kakaoCheckbox).not.toBeChecked();
    await expect(slackCheckbox).toBeDisabled();
    await expect(slackCheckbox).not.toBeChecked();

    const kakaoCard = cardOf(page, 'kakao');
    await expect(kakaoCard.getByText('준비 중', { exact: true })).toBeVisible();
    await expect(kakaoCard.getByText(NOT_SELECTED)).toBeVisible();
    await expect(kakaoCard.getByText('사용자 검색 (이름 또는 이메일)')).toHaveCount(0);

    const slackCard = cardOf(page, 'slack');
    await expect(slackCard.getByText('준비 중', { exact: true })).toBeVisible();
    await expect(slackCard.getByText(NOT_SELECTED)).toBeVisible();
    await expect(slackCard.getByText('사용자 검색 (이름 또는 이메일)')).toHaveCount(0);
  });

  test('예전 설정의 카카오는 해제만 할 수 있다 (WD-805)', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'KAKAO', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    const kakaoCheckbox = page.getByRole('checkbox', { name: KAKAO_NAME });
    const kakaoCard = cardOf(page, 'kakao');
    await expect(kakaoCheckbox).toBeChecked({ timeout: 5000 });
    await expect(kakaoCheckbox).toBeEnabled();
    await expect(kakaoCard.getByText(WARN)).toBeVisible();
    // 체크돼 있어도 원래 설명(「카카오 알림톡으로 …」)은 보이지 않고 미지원 설명 아래에 경고가 붙는다
    await expect(kakaoCard.getByText(NOT_SELECTED)).toBeVisible();
    await expect(kakaoCard.getByText('카카오 알림톡으로 분석 결과를 전달합니다.')).toHaveCount(0);

    await kakaoCheckbox.click();

    // 해제하면 다시 켤 수 없고, 경고는 사라지며 미선택 설명이 보인다
    await expect(kakaoCheckbox).not.toBeChecked();
    await expect(kakaoCheckbox).toBeDisabled();
    await expect(kakaoCard.getByText(WARN)).toHaveCount(0);
    await expect(kakaoCard.getByText(NOT_SELECTED)).toBeVisible();
  });

  // 보기 모드 단언은 두지 않는다 — 보기 화면(JobOverviewTab 의 !isEditing 분기)은 이 편집기를 그리지 않아
  // 편집 버튼을 누르기 전에는 Slack 체크박스 자체가 없으므로 「보기 모드에서 해제 불가」를 확인할 대상이 없다 (WD-805).
  test('예전 설정의 Slack 을 해제하고 저장하면 요청에서 빠진다 (WD-805)', async ({ authenticatedPage: page }) => {
    const save = await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', createJob({ id: 1 }), {
      capture: true,
    });
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'SLACK', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    const slackCheckbox = page.getByRole('checkbox', { name: SLACK_NAME });
    await expect(slackCheckbox).toBeChecked({ timeout: 5000 });
    await slackCheckbox.click();
    await expect(slackCheckbox).not.toBeChecked();

    await page.getByRole('button', { name: '저장' }).click();
    const req = await save.waitForRequest();
    const types = (req.payload as { config: { channels: { type: string }[] } }).config.channels.map(
      (c) => c.type,
    );
    expect(types).toContain('CHAT');
    expect(types).not.toContain('SLACK');
  });

  // WD-805: 예전에 Slack 이 저장된 작업은 체크가 남아 있는 동안 저장 전에 화면에서 막혀 서버 400 까지 가지 않는다.
  test('예전 설정의 Slack 이 체크된 채로는 저장되지 않고 사유가 보인다 (WD-805)', async ({ authenticatedPage: page }) => {
    const save = await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', createJob({ id: 1 }), {
      capture: true,
    });
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'SLACK', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    const slackCheckbox = page.getByRole('checkbox', { name: SLACK_NAME });
    await expect(slackCheckbox).toBeChecked({ timeout: 5000 });
    // 그 채널 카드가 저장할 수 없는 이유를 보이고 저장 버튼은 눌리지 않는다
    await expect(cardOf(page, 'slack').getByText(WARN)).toBeVisible();
    const saveButton = page.getByRole('button', { name: '저장' });
    await expect(saveButton).toBeDisabled();
    // 저장 버튼이 막혀 있으므로 요청은 나가지 않는다
    expect(save.requests.length).toBe(0);

    // 해제하면 오류가 사라지고 저장할 수 있다
    await slackCheckbox.click();
    await expect(saveButton).toBeEnabled();
  });

  test('이메일/카카오/슬랙 동시 선택 작업은 이메일 입력과 미지원 경고가 보인다 (WD-805)', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'EMAIL', recipientUserIds: [], recipientEmails: [] },
          { type: 'KAKAO', recipientUserIds: [], recipientEmails: [] },
          { type: 'SLACK', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    // EMAIL 섹션: 외부 이메일 입력 플레이스홀더 확인
    await expect(page.getByPlaceholder('이메일 입력 후 Enter')).toBeVisible({ timeout: 5000 });

    // KAKAO/SLACK 카드는 각각 경고 문구만 보이고 연동 안내는 없다
    await expect(cardOf(page, 'kakao').getByText(WARN)).toBeVisible();
    await expect(cardOf(page, 'slack').getByText(WARN)).toBeVisible();
    await expect(page.getByText(/수신자의 .* 연동 상태는/)).toHaveCount(0);
  });

  // 회귀 테스트 (#554): 이메일 채널은 CHAT/KAKAO/SLACK과 달리 안내 문구가 조건부로
  // 바뀌지 않고 항상 "본인에게만 전달됩니다"로 고정 노출되던 결함
  test('이메일 채널은 외부 이메일이 이미 지정된 경우 "N명에게 전달됩니다" 문구를 보여준다 (#554)', async ({
    authenticatedPage: page,
  }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'EMAIL', recipientUserIds: [], recipientEmails: ['test@example.com'] },
        ],
      },
    });

    // 배지는 이미 정확히 1건을 보여주지만, 안내 문구는 기존 결함으로 고정 문구였다
    await expect(page.getByPlaceholder('이메일 입력 후 Enter')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('group', { name: '외부 이메일' }).getByText('test@example.com')).toBeVisible();

    // CHAT 채널도 동일 문구를 쓰므로 이메일 채널 카드로 범위를 좁혀 검증한다
    const emailSection = page
      .locator('div.rounded-lg.border')
      .filter({ has: page.getByRole('checkbox', { name: '이메일' }) });

    // 수정 후: 외부 이메일 1건이 반영되어 "1명에게 전달됩니다"로 표시되어야 한다
    await expect(emailSection.getByText('1명에게 전달됩니다')).toBeVisible();
    await expect(emailSection.getByText('수신자를 지정하지 않으면 본인에게만 전달됩니다')).not.toBeVisible();
  });

  test('이메일 채널은 수신자가 없으면 기존과 동일하게 "본인에게만 전달됩니다" 문구를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'EMAIL', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    await expect(page.getByPlaceholder('이메일 입력 후 Enter')).toBeVisible({ timeout: 5000 });

    const emailSection = page
      .locator('div.rounded-lg.border')
      .filter({ has: page.getByRole('checkbox', { name: '이메일' }) });
    await expect(emailSection.getByText('수신자를 지정하지 않으면 본인에게만 전달됩니다')).toBeVisible();
  });

  // WD-512: UserCombobox 트리거는 role=combobox 라 placeholder 텍스트로 이름이 계산되지 않아 「이름 없는 버튼」이었다.
  // 화면 라벨과 같은 이름(이메일 「등록 사용자」, 그 밖의 채널 「<채널> 수신자」)으로 찾아 열 수 있어야 한다.
  test('수신자 사용자 콤보박스는 화면 라벨과 같은 접근 이름을 갖고 눌러서 검색을 연다 (WD-512)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/recipients', []);
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'EMAIL', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    await expect(page.getByRole('combobox', { name: '채팅 수신자' })).toBeVisible({ timeout: 5000 });
    const emailUsers = page.getByRole('combobox', { name: '등록 사용자' });
    await expect(emailUsers).toBeVisible();

    // 누르면 팝오버가 열리고 검색 입력이 보인다
    await emailUsers.click();
    await expect(emailUsers).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByPlaceholder('이름 또는 이메일로 검색...')).toBeVisible();
  });

  // 회귀 테스트 (#555): 저장된 채널 수신자(Slack 이 켜지지 않게 된 WD-805 이후로는 채팅 채널로 검증)가 검색을 거치지 않고 마운트된 selectedUserIds라서
  // UserCombobox 내부 캐시(userCache)에 없어, 배지 카운트는 맞는데 이름 칩이 빈 채로 보이던 결함.
  test('채팅 채널 수신자를 저장 후 재오픈하면 이름 칩이 하이드레이션된다 (#555, WD-805 에서 채팅으로 옮김)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/recipients', [
      { userId: 42, name: 'DongHee', email: 'donghee@example.com' },
    ]);

    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [42], recipientEmails: [] },
        ],
      },
    });

    const chatSection = page
      .locator('div.rounded-lg.border')
      .filter({ has: page.getByRole('checkbox', { name: '채팅 채널 (항상 활성화)' }) });

    // 배지 카운트는 기존에도 정상 — 회귀 전에도 통과하던 부분
    await expect(chatSection.getByText('1명에게 전달됩니다')).toBeVisible({ timeout: 5000 });

    // 수정 전: 검색을 하지 않았으므로 콤보박스는 placeholder만 보이고 이름 칩이 없었다.
    // 수정 후: 마운트 시 하이드레이션되어 "DongHee" 칩이 곧바로 렌더링되어야 한다.
    await expect(chatSection.getByText('DongHee')).toBeVisible({ timeout: 5000 });
    await expect(chatSection.getByText('사용자 검색 (이름 또는 이메일)')).not.toBeVisible();
    // WD-512: 채널 콤보박스도 「<채널> 수신자」 이름을 갖고, 칩의 × 는 누구를 빼는지 이름으로 알린다
    await expect(chatSection.getByRole('combobox', { name: '채팅 수신자' })).toBeVisible();
    await expect(chatSection.getByRole('button', { name: 'DongHee 빼기' })).toBeVisible();
  });
  // WD-172: 정지·제거된 수신자는 수신자 조회(현재 워크스페이스 ACTIVE 멤버만)에서 빠져 칩이 사라졌고, 그래서 관리자가 뺄 수 없었다.
  // 이름을 찾지 못한 id 는 「받을 수 없는 사용자」 칩으로 보이고, 빼고 저장하면 그 id 가 요청에서 빠져야 한다.
  test('정지·제거된 수신자는 「받을 수 없는 사용자」 칩으로 보이고 빼서 저장할 수 있다 (WD-172)', async ({
    authenticatedPage: page,
  }) => {
    // 42 만 이 워크스페이스 ACTIVE 멤버 — 77 은 정지·제거되어 조회 결과에 없다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/recipients', [
      { userId: 42, name: 'DongHee', email: 'donghee@example.com' },
    ]);
    const save = await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', createJob({ id: 1 }), {
      capture: true,
    });

    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [42, 77], recipientEmails: [] }],
      },
    });

    const chatSection = page
      .locator('div.rounded-lg.border')
      .filter({ has: page.getByRole('checkbox', { name: /채팅/ }) });
    await expect(chatSection.getByText('DongHee')).toBeVisible({ timeout: 5000 });
    await expect(chatSection.getByText('받을 수 없는 사용자')).toBeVisible({ timeout: 5000 });

    await chatSection.getByRole('button', { name: '받을 수 없는 사용자 빼기' }).click();
    await expect(chatSection.getByText('받을 수 없는 사용자')).not.toBeVisible();
    await expect(chatSection.getByText('1명에게 전달됩니다')).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    const req = await save.waitForRequest();
    const channels = (req.payload as { config: { channels: { type: string; recipientUserIds: number[] }[] } })
      .config.channels;
    expect(channels.find((c) => c.type === 'CHAT')?.recipientUserIds).toEqual([42]);
  });
  // WD-299: 외부 이메일 중복 검사가 대소문자를 구분했고, 「등록 사용자」로 고른 사람의 주소를 외부 이메일로 또 넣어도 막지 않아
  // 같은 사람이 리포트를 두 번 받게 저장되고 「N명에게 전달됩니다」가 실제보다 많았다.
  test.describe('이메일 수신자 중복 (WD-299)', () => {
    const emailSection = (page: Page) =>
      page.locator('div.rounded-lg.border').filter({ has: page.getByRole('checkbox', { name: '이메일' }) });

    test.beforeEach(async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/recipients', [
        { userId: 28, name: 'QA 저등급', email: 'qa267low@example.com' },
      ]);
    });

    test('대소문자만 다른 주소와 등록 사용자의 주소는 외부 이메일로 더 넣을 수 없다', async ({
      authenticatedPage: page,
    }) => {
      await setupAndNavigateToNotifyTab(page, {
        config: {
          channels: [
            { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
            { type: 'EMAIL', recipientUserIds: [28], recipientEmails: ['QA@Example.COM'] },
          ],
        },
      });
      const section = emailSection(page);
      await expect(section.getByText('QA 저등급')).toBeVisible({ timeout: 5000 });
      await expect(section.getByText('2명에게 전달됩니다')).toBeVisible();

      const input = page.getByPlaceholder('이메일 입력 후 Enter');
      // 대소문자만 다른 같은 주소
      await input.fill('qa@example.com');
      await input.press('Enter');
      await expect(section.getByText('이미 추가된 이메일입니다')).toBeVisible();
      await expect(input).toHaveValue('qa@example.com');

      // 등록 사용자로 이미 고른 사람의 주소(대소문자도 다르게)
      await input.fill('QA267LOW@example.com');
      await input.press('Enter');
      await expect(section.getByText('이미 수신자에 있습니다 — 등록 사용자로 선택된 주소입니다')).toBeVisible();

      // 외부 이메일 태그는 처음 하나 그대로이고 받는 사람 수도 그대로다
      const external = page.getByRole('group', { name: '외부 이메일' });
      await expect(external.getByRole('button', { name: /제거$/ })).toHaveCount(1);
      await expect(section.getByText('2명에게 전달됩니다')).toBeVisible();

      // 다른 주소는 그대로 들어가고 받는 사람 수가 늘어난다
      await input.fill('new@example.com');
      await input.press('Enter');
      await expect(external.getByText('new@example.com')).toBeVisible();
      await expect(section.getByText('3명에게 전달됩니다')).toBeVisible();
    });

    test('이미 중복으로 저장된 작업은 실제 받는 사람 수로 센다', async ({ authenticatedPage: page }) => {
      // 고치기 전에 저장된 config — 이슈 재현과 같은 모양
      await setupAndNavigateToNotifyTab(page, {
        config: {
          channels: [
            { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
            {
              type: 'EMAIL',
              recipientUserIds: [28],
              recipientEmails: [
                'QA@Example.COM',
                'qa@example.com',
                'workloop@example.com',
                'qa267low@example.com',
              ],
            },
          ],
        },
      });
      const section = emailSection(page);
      await expect(section.getByText('QA 저등급')).toBeVisible({ timeout: 5000 });
      // qa267low·qa@example.com·workloop — 3명(고치기 전에는 5명)
      await expect(section.getByText('3명에게 전달됩니다')).toBeVisible();
      await expect(section.locator('span').filter({ hasText: /^3$/ }).first()).toBeVisible();
    });
  });
});

// WD-994: API 로 설정된 웹훅 채널이 편집·저장 중 사라지거나 저장을 막지 않는다.
test.describe('웹훅 채널 (WD-994)', () => {
  const HOOK = 'https://example.com/hook';
  const CHAT = { type: 'CHAT', recipientUserIds: [], recipientEmails: [] };
  const PUT_PATH = '/api/v1/proactive/jobs/1';
  const GUIDE =
    'API 로 설정된 채널입니다 — 아직 스마트 작업 결과를 웹훅으로 보내지 않습니다. 해제하면 설정에서 빠집니다.';

  type SentChannel = { type: string; url?: string; secret?: string };

  /** 「저장」을 눌러 나간 PUT 요청의 config.channels 와 name 을 돌려준다 */
  async function saveAndGetPayload(page: Page, save: MockApiCapture) {
    await page.getByRole('button', { name: '저장' }).click();
    const req = await save.waitForRequest();
    const payload = req.payload as { name: string; config: { channels: SentChannel[] } };
    return { name: payload.name, channels: payload.config.channels };
  }

  /** 편집 없이 읽기 화면 상세를 모킹해 연다 */
  async function openReadView(page: Page, channels: unknown[]) {
    const job = createJob({ id: 1, config: { channels } });
    await mockApi(page, 'GET', PUT_PATH, job);
    await mockApi(page, 'GET', `${PUT_PATH}/executions`, []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', `${PUT_PATH}/anomaly-events`, []);
    await page.goto('/ai-insights/jobs/1');
  }

  test('웹훅 작업은 이름만 고쳐도 저장되고 url·secret 등 키가 보존된다', async ({ authenticatedPage: page }) => {
    const save = await mockApi(page, 'PUT', PUT_PATH, createJob({ id: 1 }), { capture: true });
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: [CHAT, { type: 'WEBHOOK', url: HOOK, secret: 'abc' }] },
    });

    await expect(page.getByRole('checkbox', { name: /웹훅/ })).toBeChecked({ timeout: 5000 });
    const card = page.getByTestId('channel-card-webhook');
    await expect(card.getByText(`웹훅 주소: ${HOOK}`)).toBeVisible();
    await expect(card.getByText('전달 안 됨')).toBeVisible();
    await expect(card.getByText(GUIDE)).toBeVisible();

    await page.getByLabel(/작업 이름/).fill('새 이름');
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

    const { name, channels } = await saveAndGetPayload(page, save);
    expect(name).toBe('새 이름');
    // WD-994: url 뿐 아니라 화면이 모르는 키(secret)도 그대로 실린다
    expect(channels.find((c) => c.type === 'WEBHOOK')).toMatchObject({ url: HOOK, secret: 'abc' });
  });

  test('웹훅을 해제하고 저장하면 요청에서 빠진다', async ({ authenticatedPage: page }) => {
    const save = await mockApi(page, 'PUT', PUT_PATH, createJob({ id: 1 }), { capture: true });
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: [CHAT, { type: 'WEBHOOK', url: HOOK }] },
    });

    const checkbox = page.getByRole('checkbox', { name: /웹훅/ });
    await expect(checkbox).toBeChecked({ timeout: 5000 });
    await checkbox.click();
    await expect(page.getByTestId('channel-card-webhook')).toHaveCount(0);

    const { channels } = await saveAndGetPayload(page, save);
    const types = channels.map((c) => c.type);
    expect(types).toContain('CHAT');
    expect(types).not.toContain('WEBHOOK');
  });

  // WCAG 2.4.3: 카드가 사라져도 키보드 초점이 body 로 떨어지지 않고 이메일 체크박스로 간다
  test('웹훅을 키보드로 해제하면 초점이 이메일 체크박스로 옮겨진다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: [CHAT, { type: 'WEBHOOK', url: HOOK }] },
    });

    const webhook = page.getByRole('checkbox', { name: /웹훅/ });
    await expect(webhook).toBeChecked({ timeout: 5000 });
    await webhook.focus();
    await page.keyboard.press('Space');

    await expect(page.getByRole('checkbox', { name: /웹훅/ })).toHaveCount(0);
    await expect(page.getByRole('checkbox', { name: /이메일/ })).toBeFocused();
  });

  test('url 없는 웹훅도 저장이 켜진다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: [CHAT, { type: 'WEBHOOK' }] },
    });

    await expect(page.getByText('웹훅 주소: (없음)')).toBeVisible({ timeout: 5000 });
    await page.getByLabel(/작업 이름/).fill('새 이름');
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
  });

  test('구 형식 문자열 채널의 웹훅도 저장이 켜진다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: ['CHAT', 'WEBHOOK'] },
    });

    await expect(page.getByRole('checkbox', { name: /웹훅/ })).toBeChecked({ timeout: 5000 });
    await page.getByLabel(/작업 이름/).fill('새 이름');
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
  });

  test('웹훅 해제 뒤 취소하면 다시 편집할 때 되살아난다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: { channels: [CHAT, { type: 'WEBHOOK', url: HOOK }] },
    });

    await page.getByRole('checkbox', { name: /웹훅/ }).click();
    await expect(page.getByTestId('channel-card-webhook')).toHaveCount(0);

    // 해제로 폼이 dirty 해져 취소 시 이탈 확인 창이 뜬다 — 「이탈」로 변경을 버린다
    await page.getByRole('button', { name: '취소' }).click();
    await page.getByRole('button', { name: '이탈' }).click();
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.getByRole('checkbox', { name: /웹훅/ })).toBeChecked();
  });

  // 읽기 화면: 라벨은 「웹훅」(WEBHOOK 글자 없음), 웹훅 행은 「전달 안 됨」이고 「본인에게만 전달」이 아니다
  test('읽기 화면 웹훅 행은 「웹훅」·「전달 안 됨」으로 보인다', async ({ authenticatedPage: page }) => {
    await openReadView(page, [{ type: 'WEBHOOK', url: HOOK }]);

    const section = page.getByRole('heading', { name: '전달 채널' }).locator('..');
    await expect(section.getByText('웹훅', { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(section.getByText('WEBHOOK')).toHaveCount(0);
    await expect(section.getByText('전달 안 됨')).toBeVisible();
    await expect(section.getByText('본인에게만 전달')).toHaveCount(0);
  });

  // 웹훅이 둘이면 카드도 둘, 해제는 그 항목만 뺀다
  test('웹훅이 둘이면 카드가 둘이고 하나만 해제하면 나머지만 저장된다', async ({ authenticatedPage: page }) => {
    const save = await mockApi(page, 'PUT', PUT_PATH, createJob({ id: 1 }), { capture: true });
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [CHAT, { type: 'WEBHOOK', url: 'https://a' }, { type: 'WEBHOOK', url: 'https://b' }],
      },
    });

    const cards = page.getByTestId('channel-card-webhook');
    await expect(cards).toHaveCount(2, { timeout: 5000 });
    await expect(cards.nth(0).getByText('웹훅 주소: https://a')).toBeVisible();
    await expect(cards.nth(1).getByText('웹훅 주소: https://b')).toBeVisible();

    await cards.nth(0).getByRole('checkbox').click();
    await expect(cards).toHaveCount(1);
    await expect(cards.nth(0).getByText('웹훅 주소: https://b')).toBeVisible();

    const { channels } = await saveAndGetPayload(page, save);
    expect(channels.filter((c) => c.type === 'WEBHOOK').map((c) => c.url)).toEqual(['https://b']);
  });
});
