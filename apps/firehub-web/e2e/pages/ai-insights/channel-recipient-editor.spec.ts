import type { Page } from '@playwright/test';

import { createJob, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
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

  test('KAKAO 체크박스 활성화 시 수신자 UI와 미연동 안내가 표시된다', async ({ authenticatedPage: page }) => {
    // CHAT만 활성화된 상태에서 시작
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
      },
    });

    // KAKAO 체크박스 체크 — 비활성 상태에서 클릭
    const kakaoCheckbox = page.getByRole('checkbox', { name: /카카오 알림톡/i });
    await expect(kakaoCheckbox).toBeVisible({ timeout: 5000 });
    await expect(kakaoCheckbox).not.toBeChecked();
    await kakaoCheckbox.click();

    // 체크 후 UserCombobox 플레이스홀더(버튼 내 span) 텍스트가 표시되어야 한다
    await expect(page.getByText('사용자 검색 (이름 또는 이메일)').first()).toBeVisible({ timeout: 3000 });

    // 미연동 안내 문구 확인
    await expect(page.getByText(/수신자의 카카오 알림톡 연동 상태는/)).toBeVisible();
    await expect(page.getByRole('link', { name: /설정.*채널 연동/i })).toBeVisible();
  });

  test('SLACK 체크박스 활성화 시 수신자 UI와 미연동 안내가 표시된다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
      },
    });

    const slackCheckbox = page.getByRole('checkbox', { name: /slack/i });
    await expect(slackCheckbox).not.toBeChecked();
    await slackCheckbox.click();

    // 미연동 안내 문구 확인 (Slack 레이블)
    await expect(page.getByText(/수신자의 Slack 연동 상태는/)).toBeVisible({ timeout: 3000 });
    await expect(page.getByRole('link', { name: /설정.*채널 연동/i })).toBeVisible();
  });

  test('KAKAO 활성화 후 비활성화 시 수신자 UI가 사라진다', async ({ authenticatedPage: page }) => {
    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'KAKAO', recipientUserIds: [], recipientEmails: [] },
        ],
      },
    });

    // KAKAO 이미 활성화 상태
    const kakaoCheckbox = page.getByRole('checkbox', { name: /카카오 알림톡/i });
    await expect(kakaoCheckbox).toBeChecked({ timeout: 5000 });

    // 미연동 안내가 표시 중
    await expect(page.getByText(/수신자의 카카오 알림톡 연동 상태는/)).toBeVisible();

    // 체크 해제
    await kakaoCheckbox.click();

    // 수신자 UI와 안내 문구가 사라져야 한다
    await expect(page.getByText(/수신자의 카카오 알림톡 연동 상태는/)).not.toBeVisible();
  });

  test('이메일/카카오/슬랙 동시 활성화 시 각 채널 UI가 모두 표시된다', async ({ authenticatedPage: page }) => {
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

    // KAKAO/SLACK 미연동 안내 모두 표시
    await expect(page.getByText(/수신자의 카카오 알림톡 연동 상태는/)).toBeVisible();
    await expect(page.getByText(/수신자의 Slack 연동 상태는/)).toBeVisible();
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

  // 회귀 테스트 (#555): 저장된 SLACK 채널 수신자가 검색을 거치지 않고 마운트된 selectedUserIds라서
  // UserCombobox 내부 캐시(userCache)에 없어, 배지 카운트는 맞는데 이름 칩이 빈 채로 보이던 결함.
  test('SLACK 채널 수신자를 저장 후 재오픈하면 이름 칩이 하이드레이션된다 (#555)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/recipients', [
      { userId: 42, name: 'DongHee', email: 'donghee@example.com' },
    ]);

    await setupAndNavigateToNotifyTab(page, {
      config: {
        channels: [
          { type: 'CHAT', recipientUserIds: [], recipientEmails: [] },
          { type: 'SLACK', recipientUserIds: [42], recipientEmails: [] },
        ],
      },
    });

    const slackSection = page
      .locator('div.rounded-lg.border')
      .filter({ has: page.getByRole('checkbox', { name: /slack/i }) });

    // 배지 카운트는 기존에도 정상 — 회귀 전에도 통과하던 부분
    await expect(slackSection.getByText('1명에게 전달됩니다')).toBeVisible({ timeout: 5000 });

    // 수정 전: 검색을 하지 않았으므로 콤보박스는 placeholder만 보이고 이름 칩이 없었다.
    // 수정 후: 마운트 시 하이드레이션되어 "DongHee" 칩이 곧바로 렌더링되어야 한다.
    await expect(slackSection.getByText('DongHee')).toBeVisible({ timeout: 5000 });
    await expect(slackSection.getByText('사용자 검색 (이름 또는 이메일)')).not.toBeVisible();
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
