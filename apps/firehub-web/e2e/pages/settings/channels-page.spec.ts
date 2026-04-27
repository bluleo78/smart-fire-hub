import type { ChannelSetting } from '../../../src/api/channels';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 채널 설정 페이지 E2E 테스트
 * - /api/v1/channels/settings를 모킹하여 백엔드 없이 테스트한다.
 */

/** 기본 채널 설정 목업 데이터 — ChannelSetting 타입으로 API 스펙 정합성 보장 */
const MOCK_CHANNEL_SETTINGS: ChannelSetting[] = [
  {
    channel: 'CHAT',
    enabled: true,
    connected: true,
    needsReauth: false,
    displayAddress: null,
    oauthStartUrl: null,
  },
  {
    channel: 'EMAIL',
    enabled: true,
    connected: true,
    needsReauth: false,
    displayAddress: 'test@example.com',
    oauthStartUrl: null,
  },
  {
    channel: 'KAKAO',
    enabled: false,
    connected: false,
    needsReauth: false,
    displayAddress: null,
    oauthStartUrl: 'https://kauth.kakao.com/oauth/authorize?client_id=test',
  },
  {
    channel: 'SLACK',
    enabled: false,
    connected: true,
    needsReauth: true,
    displayAddress: 'workspace.slack.com',
    oauthStartUrl: 'https://slack.com/oauth/v2/authorize?client_id=test',
  },
];

/**
 * 채널 설정 API 모킹 헬퍼
 */
async function setupChannelMocks(page: Parameters<typeof mockApi>[0]) {
  await mockApi(page, 'GET', '/api/v1/channels/settings', MOCK_CHANNEL_SETTINGS);
}

test.describe('채널 설정 페이지', () => {
  test('페이지 진입 시 4개 채널 카드가 표시된다', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '알림 채널 설정' })).toBeVisible();

    // 4개 채널 이름이 모두 렌더링되는지 확인 (exact로 label만 매칭, description 제외)
    await expect(page.getByText('앱 알림', { exact: true })).toBeVisible();
    await expect(page.getByText('이메일', { exact: true })).toBeVisible();
    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();
    await expect(page.getByText('Slack', { exact: true })).toBeVisible();
  });

  test('KAKAO 미연결 상태 — "연동하기" 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    // KAKAO 카드에 미연결 배지 확인
    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();

    // "연동하기" 버튼 표시 확인
    await expect(page.getByRole('button', { name: '연동하기' })).toBeVisible();
  });

  test('EMAIL 토글 OFF → PATCH /api/v1/channels/settings/EMAIL/preference 호출', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    // PATCH 캡처 — goto 이전에 등록해야 한다
    const patchCapture = await mockApi(
      page,
      'PATCH',
      '/api/v1/channels/settings/EMAIL/preference',
      {},
      { capture: true, status: 204 },
    );

    await page.goto('/settings/channels');
    await expect(page.getByText('이메일', { exact: true })).toBeVisible();

    // EMAIL 활성화 토글 찾기 (aria-label로 선택)
    const emailToggle = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(emailToggle).toBeVisible();
    await expect(emailToggle).toBeChecked(); // 초기값: enabled=true

    // 토글 OFF
    await emailToggle.click();

    // PATCH 요청 검증
    const req = await patchCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/channels/settings/EMAIL/preference');
    expect(req.payload).toMatchObject({ enabled: false });
  });

  /**
   * 회귀 테스트: 채널 토글 성공 시 한국어 성공 토스트가 표시되어야 한다 (#36)
   * - EMAIL 토글 OFF → "이메일 채널이 비활성화되었습니다." 토스트 표시
   * - EMAIL 토글 ON → "이메일 채널이 활성화되었습니다." 토스트 표시
   */
  test('EMAIL 토글 OFF 성공 → "이메일 채널이 비활성화되었습니다." 토스트 표시 (refs #36)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    // PATCH 성공 응답 모킹 (204 No Content)
    await mockApi(page, 'PATCH', '/api/v1/channels/settings/EMAIL/preference', {}, { status: 204 });

    await page.goto('/settings/channels');
    await expect(page.getByText('이메일', { exact: true })).toBeVisible();

    const emailToggle = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(emailToggle).toBeChecked(); // 초기값: enabled=true

    // 토글 OFF → 성공 토스트 검증
    await emailToggle.click();
    await expect(page.getByText('이메일 채널이 비활성화되었습니다.')).toBeVisible();
  });

  test('EMAIL 토글 ON 성공 → "이메일 채널이 활성화되었습니다." 토스트 표시 (refs #36)', async ({
    authenticatedPage: page,
  }) => {
    // EMAIL을 enabled: false 상태로 오버라이드
    const disabledSettings: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'EMAIL' ? { ...s, enabled: false } : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', disabledSettings);
    // PATCH 성공 응답 모킹
    await mockApi(page, 'PATCH', '/api/v1/channels/settings/EMAIL/preference', {}, { status: 204 });

    await page.goto('/settings/channels');
    await expect(page.getByText('이메일', { exact: true })).toBeVisible();

    const emailToggle = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(emailToggle).not.toBeChecked(); // 초기값: enabled=false

    // 토글 ON → 성공 토스트 검증
    await emailToggle.click();
    await expect(page.getByText('이메일 채널이 활성화되었습니다.')).toBeVisible();
  });

  test('SLACK needsReauth 상태 — "재연결" 버튼과 주황 배지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    // Slack 카드 확인
    await expect(page.getByText('Slack', { exact: true })).toBeVisible();

    // 재인증 필요 배지 확인
    await expect(page.getByText('재인증 필요')).toBeVisible();

    // "재연결" 버튼 확인
    await expect(page.getByRole('button', { name: '재연결' })).toBeVisible();
  });

  test('CHAT 토글은 비활성화(disabled) 상태다', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    // 앱 알림 토글은 disabled
    const chatToggle = page.getByRole('switch', { name: '앱 알림 채널 활성화' });
    await expect(chatToggle).toBeVisible();
    await expect(chatToggle).toBeDisabled();
  });

  test('EMAIL displayAddress가 카드에 표시된다', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    // EMAIL 카드에 displayAddress 표시
    await expect(page.getByText('test@example.com')).toBeVisible();
  });

  test('KAKAO 연결됨 상태 — "연결 해제" 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    // KAKAO를 connected: true, needsReauth: false 상태로 오버라이드
    const connectedSettings: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'KAKAO'
        ? { ...s, connected: true, needsReauth: false, enabled: true }
        : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', connectedSettings);
    await page.goto('/settings/channels');

    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();
    // "연결 해제" 버튼 표시 확인
    await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible();
  });

  /**
   * 회귀 테스트: EMAIL 채널 미연결 시 SMTP 안내 툴팁이 표시되어야 한다 (#16)
   * - 이메일 스위치가 disabled 상태일 때 hover하면 SMTP 설정 안내 툴팁이 나타나야 함
   */
  test('EMAIL 채널 미연결 시 스위치 hover로 SMTP 안내 툴팁이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // EMAIL을 미연결 상태로 오버라이드
    const disconnectedSettings: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'EMAIL'
        ? { ...s, connected: false, enabled: false }
        : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', disconnectedSettings);
    await page.goto('/settings/channels');

    // 이메일 토글이 disabled 상태인지 확인
    const emailToggle = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(emailToggle).toBeVisible();
    await expect(emailToggle).toBeDisabled();

    // 이메일 토글 hover → SMTP 안내 툴팁 표시 검증
    await emailToggle.hover();
    await expect(page.getByText('이메일 채널을 사용하려면 관리자가 SMTP 설정을 먼저 완료해야 합니다.')).toBeVisible();
  });

  /**
   * 회귀 테스트: 카카오 알림톡 카드에 브랜드 아이콘이 적용되어야 한다 (#6)
   * - 일반 채팅 버블(MessageSquare)이 아닌 카카오 브랜드 SVG 아이콘이 렌더링되어야 함
   * - 아이콘 컨테이너 배경이 카카오 브랜드 색상(#FEE500)이어야 함
   */
  /**
   * 회귀 테스트: 채널별 "테스트 발송" 버튼이 ChannelCard에 추가되어야 한다 (#85)
   * - CHAT 카드에는 버튼이 없어야 함 (항상 활성 — 테스트 불필요)
   * - EMAIL/KAKAO/SLACK 카드에는 버튼이 표시되어야 함
   * - 미연결/재인증 필요 상태에서는 disabled
   */
  test('CHAT 카드에는 "테스트 발송" 버튼이 없다 (#85)', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');
    await expect(page.getByText('앱 알림', { exact: true })).toBeVisible();

    // CHAT 카드의 영역 내부에는 테스트 발송 버튼이 없어야 한다
    const chatCard = page.locator('[data-slot="card"]', { hasText: '앱 알림' }).first();
    await expect(chatCard.getByRole('button', { name: '테스트 발송' })).toHaveCount(0);
  });

  test('EMAIL 연결됨 — "테스트 발송" 버튼이 활성 상태로 표시된다 (#85)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    const emailCard = page.locator('[data-slot="card"]', { hasText: '이메일' }).first();
    const testButton = emailCard.getByRole('button', { name: '테스트 발송' });
    await expect(testButton).toBeVisible();
    await expect(testButton).toBeEnabled();
  });

  test('KAKAO 미연결 — "테스트 발송" 버튼은 disabled (#85)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    const kakaoCard = page.locator('[data-slot="card"]', { hasText: '카카오 알림톡' }).first();
    const testButton = kakaoCard.getByRole('button', { name: '테스트 발송' });
    await expect(testButton).toBeVisible();
    await expect(testButton).toBeDisabled();
  });

  test('SLACK 재인증 필요 — "테스트 발송" 버튼은 disabled (#85)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();
    const testButton = slackCard.getByRole('button', { name: '테스트 발송' });
    await expect(testButton).toBeVisible();
    await expect(testButton).toBeDisabled();
  });

  test('EMAIL 테스트 발송 클릭 → POST /channels/settings/EMAIL/test 호출 + 성공 토스트 (#85)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    const postCapture = await mockApi(
      page,
      'POST',
      '/api/v1/channels/settings/EMAIL/test',
      { success: true, message: '테스트 메시지가 발송되었습니다.' },
      { capture: true, status: 200 },
    );

    await page.goto('/settings/channels');
    const emailCard = page.locator('[data-slot="card"]', { hasText: '이메일' }).first();
    await emailCard.getByRole('button', { name: '테스트 발송' }).click();

    // 백엔드 호출 검증
    const req = await postCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/channels/settings/EMAIL/test');

    // 성공 토스트 검증
    await expect(page.getByText('이메일 테스트 발송 성공')).toBeVisible();
  });

  test('EMAIL 테스트 발송 — 백엔드 success=false → 실패 토스트 표시 (#85)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/channels/settings/EMAIL/test',
      { success: false, message: 'SMTP 호스트 미설정' },
      { status: 200 },
    );

    await page.goto('/settings/channels');
    const emailCard = page.locator('[data-slot="card"]', { hasText: '이메일' }).first();
    await emailCard.getByRole('button', { name: '테스트 발송' }).click();

    await expect(page.getByText(/테스트 발송 실패.*SMTP 호스트 미설정/)).toBeVisible();
  });

  test('KAKAO 카드 아이콘 컨테이너에 카카오 브랜드 배경색이 적용된다', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();

    // 카카오 카드의 아이콘 컨테이너 배경색 검증
    // CardHeader 내 h-10.w-10 아이콘 컨테이너를 '카카오 알림톡' 텍스트 기준으로 탐색
    const bgColor = await page.evaluate(() => {
      // '카카오 알림톡' 텍스트 노드를 포함하는 카드 헤더 내 h-10 w-10 div를 찾는다
      const allDivs = Array.from(document.querySelectorAll<HTMLElement>('div.h-10.w-10'));
      const kakaoIconContainer = allDivs.find((div) => {
        // 같은 카드 헤더 내에 '카카오 알림톡' 텍스트가 있는지 확인
        const card = div.closest('[data-slot="card-header"], [data-slot="card"], header, .pb-3')
          ?? div.closest('div');
        return card?.textContent?.includes('카카오 알림톡');
      });
      if (!kakaoIconContainer) return null;
      return window.getComputedStyle(kakaoIconContainer).backgroundColor;
    });

    // #FEE500 → rgb(254, 229, 0)
    expect(bgColor).toBe('rgb(254, 229, 0)');
  });
});
