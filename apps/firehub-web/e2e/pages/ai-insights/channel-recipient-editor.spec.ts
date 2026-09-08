import type { Page } from '@playwright/test';

import { createJob, createTemplates } from '../../factories/ai-insight.factory';
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
});
