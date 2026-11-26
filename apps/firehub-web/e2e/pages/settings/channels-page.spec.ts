import type { Locator, Page } from '@playwright/test';

import type { ChannelSetting } from '../../../src/api/channels';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectSingleHeldRequest, holdApi, holdRequests } from '../../fixtures/hold-api';
import { expectSingleLine } from '../../fixtures/long-name-layout';
import { expectNoHorizontalOverflow } from '../../fixtures/long-unbroken-name';

/**
 * 채널 설정 페이지 E2E 테스트
 * - /api/v1/channels/settings를 모킹하여 백엔드 없이 테스트한다.
 */

/**
 * 기본 채널 설정 목업 데이터 — ChannelSetting 타입으로 API 스펙 정합성 보장.
 * displayAddress 는 서버(ChannelSettingsService)처럼 모든 채널에 채운다 — CHAT 「웹 인박스」, 미연결 KAKAO 「카카오톡」 (WD-896)
 */
const MOCK_CHANNEL_SETTINGS: ChannelSetting[] = [
  {
    channel: 'CHAT',
    enabled: true,
    connected: true,
    needsReauth: false,
    displayAddress: '웹 인박스',
    oauthStartUrl: null,
    workspaceId: null,
  },
  {
    channel: 'EMAIL',
    enabled: true,
    connected: true,
    needsReauth: false,
    displayAddress: 'test@example.com',
    oauthStartUrl: null,
    workspaceId: null,
  },
  {
    channel: 'KAKAO',
    enabled: false,
    connected: false,
    needsReauth: false,
    displayAddress: '카카오톡',
    oauthStartUrl: 'https://kauth.kakao.com/oauth/authorize?client_id=test',
    workspaceId: null,
  },
  {
    channel: 'SLACK',
    enabled: false,
    connected: true,
    needsReauth: true,
    displayAddress: 'workspace.slack.com',
    oauthStartUrl: 'https://slack.com/oauth/v2/authorize?client_id=test',
    workspaceId: null,
  },
];

/**
 * 채널 설정 API 모킹 헬퍼
 * @param permissions - 내 권한 코드. Slack 연동/재연결 버튼은 notification:admin 이 있어야 보인다(WD-433). 생략하면 픽스처 기본값(없음).
 */
async function setupChannelMocks(page: Parameters<typeof mockApi>[0], permissions?: string[]) {
  await mockApi(page, 'GET', '/api/v1/channels/settings', MOCK_CHANNEL_SETTINGS);
  if (permissions) await mockApi(page, 'GET', '/api/v1/auth/me/permissions', permissions);
}

/**
 * KAKAO 만 connected: true, needsReauth: false 로 바꾼 설정을 모킹한다 (연결 해제 버튼이 보이는 상태).
 * "연결됨" 테스트 둘이 같은 모킹을 복붙하지 않고 공유한다.
 */
async function setupKakaoConnectedMocks(page: Parameters<typeof mockApi>[0]) {
  const connectedSettings: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
    s.channel === 'KAKAO' ? { ...s, connected: true, needsReauth: false, enabled: true } : s,
  );
  await mockApi(page, 'GET', '/api/v1/channels/settings', connectedSettings);
}

test.describe('채널 설정 페이지', () => {
  test('페이지 진입 시 4개 채널 카드가 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('EMAIL 토글 OFF → PATCH /api/v1/channels/settings/EMAIL/preference 호출', { tag: '@smoke' }, async ({
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
    // Slack 재연결은 워크스페이스 설치 OAuth 라 notification:admin 이 있어야 버튼이 보인다(WD-433).
    await setupChannelMocks(page, ['notification:admin']);
    await page.goto('/settings/channels');

    // Slack 카드 확인
    await expect(page.getByText('Slack', { exact: true })).toBeVisible();

    // 재인증 필요 배지 확인
    await expect(page.getByText('재인증 필요')).toBeVisible();

    // "재연결" 버튼 확인
    await expect(page.getByRole('button', { name: '재연결' })).toBeVisible();
  });

  /**
   * WD-433: Slack 연동/재연결(auth-url)은 서버가 notification:admin 으로만 허락한다. 권한이 없는 사람에게 버튼을 보이면
   * 눌러도 403 토스트만 뜨므로, 버튼 대신 관리자에게 요청하라는 안내가 보여야 한다.
   * 카카오는 사용자 개인 연동이라 같은 권한 없이도 「연동하기」가 그대로 보인다.
   */
  test('SLACK 재연결 — notification:admin 이 없으면 버튼 대신 관리자 안내가 보인다 (WD-433)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();
    await expect(slackCard.getByText('재인증 필요')).toBeVisible();
    await expect(
      slackCard.getByText('Slack 워크스페이스 재연결은 관리자만 할 수 있습니다. 관리자에게 요청하세요.'),
    ).toBeVisible();
    await expect(slackCard.getByRole('button', { name: '재연결' })).toHaveCount(0);
    // 카카오 개인 연동은 권한과 무관하게 그대로다.
    const kakaoCard = page.locator('[data-slot="card"]', { hasText: '카카오 알림톡' }).first();
    await expect(kakaoCard.getByRole('button', { name: '연동하기' })).toBeVisible();
  });

  test('SLACK 미설치 — notification:admin 이 없으면 「연동」 안내, 있으면 「연동하기」 버튼 (WD-433)', async ({
    authenticatedPage: page,
  }) => {
    const notInstalled: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'SLACK' ? { ...s, connected: false, needsReauth: false, workspaceId: null } : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', notInstalled);
    await page.goto('/settings/channels');

    const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();
    await expect(
      slackCard.getByText('Slack 워크스페이스 연동은 관리자만 할 수 있습니다. 관리자에게 요청하세요.'),
    ).toBeVisible();
    await expect(slackCard.getByRole('button', { name: '연동하기' })).toHaveCount(0);

    // 권한이 생기면(관리자) 같은 상태에서 버튼이 보인다 — 다시 읽어 권한 쿼리를 새로 받는다.
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['notification:admin']);
    await page.reload();
    await expect(slackCard.getByRole('button', { name: '연동하기' })).toBeVisible();
    await expect(slackCard.getByText('관리자만 할 수 있습니다')).toHaveCount(0);
  });

  /**
   * WD-433: 연결을 해제한 일반 사용자(REVOKED 이력 → needsReauth, 워크스페이스는 설치됨)는 재연결 OAuth(관리자 전용) 대신 회원 ID 연동으로
   * 다시 붙는다. 관리자는 같은 상태에서 그대로 「재연결」 버튼을 본다.
   */
  test('SLACK 연결 해제 뒤 — 일반 사용자는 회원 ID 연동, 관리자는 재연결 버튼 (WD-433)', async ({
    authenticatedPage: page,
  }) => {
    const revoked: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'SLACK' ? { ...s, connected: false, needsReauth: true, workspaceId: 5 } : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', revoked);
    const linkCapture = await mockApi(
      page,
      'POST',
      '/api/v1/oauth/slack/link-user',
      {},
      { capture: true, status: 204 },
    );
    await page.goto('/settings/channels');

    const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();
    await expect(slackCard.getByText('Slack 회원 ID 연동')).toBeVisible();
    await expect(slackCard.getByRole('button', { name: '재연결' })).toHaveCount(0);
    await expect(slackCard.getByText('관리자만 할 수 있습니다')).toHaveCount(0);

    // 회원 ID 를 넣어 연동하면 설치된 워크스페이스 id 로 link-user 를 부른다.
    await slackCard.getByLabel('Slack 회원 ID').fill('u0123456789');
    await slackCard.getByRole('button', { name: '연동 확인 DM 받기' }).click();
    const req = await linkCapture.waitForRequest();
    expect(req.payload).toMatchObject({ workspaceId: 5, slackUserId: 'U0123456789' });

    // 관리자는 같은 상태에서 재연결(워크스페이스 OAuth)을 그대로 본다.
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['notification:admin']);
    await page.reload();
    await expect(slackCard.getByRole('button', { name: '재연결' })).toBeVisible();
    await expect(slackCard.getByText('Slack 회원 ID 연동')).toHaveCount(0);
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

    // EMAIL 카드에 displayAddress 표시 — 카드 안으로 좁힌다(WD-481): 같은 주소가 사이드바 사용자 메뉴에도 있어, 페이지 전체로 찾으면
    // 카드가 먼저 그려졌을 때 두 곳이 걸려 strict mode 위반으로 실패하고, 늦게 그려지면 사이드바만 보고 통과해 카드를 확인하지 못한다.
    const emailCard = page.locator('[data-slot="card"]', { hasText: '이메일로 알림을 받습니다' }).first();
    await expect(emailCard.getByText('test@example.com')).toBeVisible();
  });

  test('KAKAO 연결됨 상태 — "연결 해제" 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupKakaoConnectedMocks(page);
    await page.goto('/settings/channels');

    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();
    // "연결 해제" 버튼 표시 확인
    await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible();
  });

  test('연결 해제 확인 버튼은 destructive 다 (WD-13)', async ({ authenticatedPage: page }) => {
    await setupKakaoConnectedMocks(page);
    await page.goto('/settings/channels');

    await page.getByRole('button', { name: '연결 해제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('button', { name: '연결 해제' })).toHaveAttribute('data-variant', 'destructive');
    await expect(dialog.getByRole('button', { name: '취소' })).toHaveAttribute('data-variant', 'outline');
  });

  test('연결 해제 성공 뒤 재조회가 500 이면 「최신이 아닐 수 있음」 안내가 뜨고, 다시 불러오기로 맞춰진다 (WD-1169 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await setupKakaoConnectedMocks(page);
    await page.goto('/settings/channels');
    await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible();
    const deleted = await mockApi(page, 'DELETE', '/api/v1/channels/settings/KAKAO', {}, { status: 204, capture: true });
    // 첫 조회는 끝났으므로 연결 해제 뒤 재조회만 500 으로 실패시킨다
    await mockApi(page, 'GET', '/api/v1/channels/settings', { message: '서버 오류' }, { status: 500 });
    await page.getByRole('button', { name: '연결 해제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '연결 해제' }).click();
    await expect.poll(() => deleted.requests.length).toBe(1);
    await expect(page.getByText('카카오 알림톡 연결이 해제되었습니다.')).toBeVisible();
    // 연결 해제는 재조회로만 카드가 바뀌므로 카드는 옛 「연결됨」 그대로다 — 토스트와 어긋남을 안내로 알린다
    const notice = page.getByRole('status').filter({ hasText: '최신 채널 설정을 불러오지 못했습니다.' });
    await expect(notice).toBeVisible();
    // 보여 줄 카드가 있으므로 카드를 가리는 빨간 오류 배너는 아니다
    await expect(page.getByText('채널 설정을 불러오는 데 실패했습니다.', { exact: false })).toHaveCount(0);
    // 서버가 다시 응답하면 「다시 불러오기」로 실제 상태(미연결)에 맞춰지고 안내가 사라진다
    await mockApi(page, 'GET', '/api/v1/channels/settings', MOCK_CHANNEL_SETTINGS);
    await notice.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(notice).toHaveCount(0);
    await expect(page.getByRole('button', { name: '연결 해제' })).toHaveCount(0);
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

  /**
   * 회귀 테스트: 채널을 비활성화하면 "테스트 발송" 버튼도 함께 비활성화되어야 한다 (#538)
   * - 채널이 연결(connected)되어 있어도 사용자가 활성화 토글을 끈 상태(enabled: false)라면
   *   "테스트 발송" 버튼이 클릭 가능해서는 안 된다 (비활성 채널로 실제 발송되는 사고 방지).
   */
  test('SLACK 연결됨 + 비활성화(enabled: false) — "테스트 발송" 버튼은 disabled + 안내 툴팁 (#538)', async ({
    authenticatedPage: page,
  }) => {
    // SLACK: connected=true, needsReauth=false, enabled=false — 연결은 되어 있으나 사용자가 끈 상태
    const disabledButConnectedSlack: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'SLACK'
        ? { ...s, connected: true, needsReauth: false, enabled: false }
        : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', disabledButConnectedSlack);

    await page.goto('/settings/channels');

    const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();
    const testButton = slackCard.getByRole('button', { name: '테스트 발송' });
    await expect(testButton).toBeVisible();
    await expect(testButton).toBeDisabled();

    // hover 시 안내 툴팁 표시 확인 (disabled 버튼 자체는 pointer-events가 막히므로 wrapper에 hover)
    await testButton.hover({ force: true });
    await expect(page.getByText('채널을 먼저 활성화하세요.')).toBeVisible();
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

  /**
   * 회귀 테스트: OAuth 자격증명 미설정 시 "연동하기" 클릭 → 에러 토스트 표시 (#129)
   * - /oauth/kakao/auth-url 이 400 + {message: "..."} 반환 시 에러 메시지가 토스트로 표시되어야 한다.
   * - 빈 client_id로 OAuth 팝업이 열리면 안 된다.
   *
   * 참고: oauthStartUrl은 백엔드가 반환하는 내부 API 경로("/api/v1/oauth/kakao/auth-url")로 설정해야
   * OAuthConnectButton이 해당 경로로 GET 요청을 보낸다.
   */
  test('KAKAO 연동하기 클릭 — auth-url 400 응답 시 에러 토스트 표시 (refs #129)', async ({
    authenticatedPage: page,
  }) => {
    // KAKAO oauthStartUrl을 실제 백엔드 API 경로로 설정 (기존 mock은 외부 URL 사용)
    const settingsWithApiPath: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'KAKAO'
        ? { ...s, oauthStartUrl: '/api/v1/oauth/kakao/auth-url' }
        : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', settingsWithApiPath);
    // OAuth auth-url 엔드포인트가 400 반환 (자격증명 미설정)
    await mockApi(
      page,
      'GET',
      '/api/v1/oauth/kakao/auth-url',
      { message: '카카오 OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요.' },
      { status: 400 },
    );

    await page.goto('/settings/channels');
    await expect(page.getByText('카카오 알림톡', { exact: true })).toBeVisible();

    // "연동하기" 버튼 클릭
    await page.getByRole('button', { name: '연동하기' }).click();

    // 에러 토스트에 서버 메시지가 표시되어야 한다 (빈 client_id로 팝업이 열리면 안 됨)
    await expect(
      page.getByText('카카오 OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요.'),
    ).toBeVisible();
  });

  /**
   * 회귀 테스트: Slack OAuth 자격증명 미설정 시 에러 토스트 표시 (#129)
   */
  test('SLACK 재연결 클릭 — auth-url 400 응답 시 에러 토스트 표시 (refs #129)', async ({
    authenticatedPage: page,
  }) => {
    // SLACK oauthStartUrl을 실제 백엔드 API 경로로 설정
    const settingsWithApiPath: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'SLACK'
        ? { ...s, oauthStartUrl: '/api/v1/oauth/slack/auth-url' }
        : s,
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', settingsWithApiPath);
    // OAuth auth-url 엔드포인트가 400 반환 (자격증명 미설정)
    await mockApi(
      page,
      'GET',
      '/api/v1/oauth/slack/auth-url',
      { message: 'Slack OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요.' },
      { status: 400 },
    );
    // 재연결 버튼은 notification:admin 이 있어야 보인다(WD-433).
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['notification:admin']);

    await page.goto('/settings/channels');
    await expect(page.getByText('Slack', { exact: true })).toBeVisible();

    // "재연결" 버튼 클릭 (MOCK 데이터에서 SLACK은 needsReauth: true)
    await page.getByRole('button', { name: '재연결' }).click();

    // 에러 토스트에 서버 메시지가 표시되어야 한다
    await expect(
      page.getByText('Slack OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요.'),
    ).toBeVisible();
  });

  // WD-895: 페이지 제목 h1 이 다른 페이지와 같은 앱 표준 타이포그래피(28px/600/36px)인지 검증
  test('페이지 제목 h1 이 앱 표준 28px / 600 / 줄 높이 36px 이다 (WD-895)', async ({
    authenticatedPage: page,
  }) => {
    await setupChannelMocks(page);
    await page.goto('/settings/channels');

    const h1 = page.getByRole('heading', { level: 1, name: '알림 채널 설정' });
    await expect(h1).toHaveCSS('font-size', '28px');
    await expect(h1).toHaveCSS('font-weight', '600');
    await expect(h1).toHaveCSS('line-height', '36px');

    // 모바일 폭(375px)에서도 제목이 한 줄이고 페이지·본문이 가로로 넘치지 않는지 확인.
    // 크기를 바꾼 뒤 레이아웃(모바일 서랍 전환)이 늦게 반영될 수 있어 toPass 로 다시 잰다.
    await page.setViewportSize({ width: 375, height: 800 });
    await expect(async () => {
      await expectSingleLine(h1);
      await expectNoHorizontalOverflow(page);
    }).toPass();
  });

  test('로딩 스켈레톤이 실제 채널 카드와 같은 높이·모서리·테두리라 로딩이 끝나도 밀리지 않는다 (WD-896)', async ({ authenticatedPage: page }) => {
    // 기본(권한 로딩 완료·notification:admin) 상태 기준 — SLACK 의 OAuth 줄이 버튼으로 그려진 뒤에 잰다
    await setupChannelMocks(page, ['notification:admin']);

    const skeletons = page.getByTestId('channel-card-skeleton');
    // 스켈레톤도 Card(data-slot="card")라서 제외해야 실제 카드만 가리킨다
    const cards = page.locator('main [data-slot="card"]:not([data-testid="channel-card-skeleton"])');
    const slackReconnect = page.getByRole('button', { name: '재연결' });
    const measure = (loc: Locator) =>
      loc.evaluateAll((els) =>
        els.map((el) => {
          const cs = window.getComputedStyle(el);
          return {
            height: el.getBoundingClientRect().height,
            radius: cs.borderTopLeftRadius,
            border: cs.borderTopWidth,
          };
        }),
      );

    /** 주어진 폭에서 설정 응답을 붙잡아 스켈레톤을 재고, 풀어 준 뒤 실제 카드를 재서 둘을 돌려준다 */
    const measureLoading = async (width: number) => {
      // 나중에 등록한 라우트가 먼저 받으므로 단계마다 새로 붙잡는다 — 풀면 setupChannelMocks 의 응답으로 넘어간다
      const held = await holdRequests(page, '/api/v1/channels/settings', 'GET');
      await page.setViewportSize({ width, height: 800 });
      await page.goto('/settings/channels');
      await expect(skeletons).toHaveCount(4);
      await expectNoHorizontalOverflow(page);
      const before = await measure(skeletons);
      held.release();
      await expect(skeletons).toHaveCount(0);
      await expect(cards).toHaveCount(4);
      await expect(slackReconnect).toBeVisible();
      return { before, after: await measure(cards) };
    };

    // 1) 데스크톱 2열 — 높이·모서리·테두리
    const desktop = await measureLoading(1280);
    desktop.before.forEach((s, i) => {
      expect(Math.abs(s.height - desktop.after[i].height)).toBeLessThanOrEqual(2);
      expect(s.radius).toBe(desktop.after[i].radius);
      expect(s.border).toBe(desktop.after[i].border);
    });

    // 2) 모바일 1열(같은 줄에 늘려지는 카드가 없어 4장 모두 자기 높이), 좁은 2열(EMAIL 설명이 두 줄로 꺾이는 폭)
    for (const width of [375, 768]) {
      const narrow = await measureLoading(width);
      narrow.before.forEach((s, i) => {
        expect(Math.abs(s.height - narrow.after[i].height), `${width}px ${i}번째 카드`).toBeLessThanOrEqual(2);
      });
    }
  });

  /**
   * 회귀 테스트: Slack 워크스페이스 설치는 완료되었으나 user binding이 없는 상태 (#234)
   *
   * 핵심 시나리오:
   * - SLACK 채널: connected=false, needsReauth=false, workspaceId=1 (워크스페이스 설치 완료)
   * - "사용자 매핑 추후 지원" Badge 대신 회원 ID 입력 UI가 노출되어야 한다
   * - "연동하기" OAuth 버튼은 노출되지 않아야 한다 (재설치 흐름이 아니므로)
   * - 입력 → "연동 확인 DM 받기" 버튼 → POST /api/v1/oauth/slack/link-user 호출
   *   payload: { workspaceId: 1, slackUserId: <대문자 정규화된 입력값> }
   * - 성공 시 토스트 표시 + GET /api/v1/channels/settings 재조회
   */
  test.describe('SLACK 사용자 매핑 UI (#234)', () => {
    /** 워크스페이스 설치 완료 + binding 없음 상태 — link-user UI가 노출되어야 하는 정확한 조건. */
    const SLACK_INSTALLED_NO_BINDING: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'SLACK'
        ? {
            ...s,
            connected: false,
            needsReauth: false,
            enabled: false,
            workspaceId: 1,
            displayAddress: 'Slack',
            oauthStartUrl: '/api/v1/oauth/slack/auth-url',
          }
        : s,
    );

    test('워크스페이스 설치 + binding 없음 → 사용자 ID 입력 UI 노출 + OAuth 버튼 비노출', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/channels/settings', SLACK_INSTALLED_NO_BINDING);
      await page.goto('/settings/channels');

      const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();

      // 사용자 ID 입력창과 연동 버튼이 노출된다
      await expect(slackCard.getByLabel('Slack 회원 ID')).toBeVisible();
      await expect(slackCard.getByRole('button', { name: '연동 확인 DM 받기' })).toBeVisible();

      // 워크스페이스가 이미 설치된 상태이므로 "연동하기" OAuth 버튼은 표시되지 않는다
      await expect(slackCard.getByRole('button', { name: '연동하기' })).toHaveCount(0);

      // 미구현 안내 배지는 더 이상 표시되지 않는다 (회귀 방지)
      await expect(slackCard.getByText('사용자 매핑 추후 지원')).toHaveCount(0);
    });

    test('정상 입력 → POST /oauth/slack/link-user 호출 + 성공 토스트', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/channels/settings', SLACK_INSTALLED_NO_BINDING);
      const linkCapture = await mockApi(
        page,
        'POST',
        '/api/v1/oauth/slack/link-user',
        {},
        { capture: true, status: 204 },
      );

      await page.goto('/settings/channels');
      const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();

      // 소문자로 입력해도 백엔드 전송 시 자동 대문자 정규화 (Slack user ID는 항상 대문자)
      await slackCard.getByLabel('Slack 회원 ID').fill('u0abcdef12');
      await slackCard.getByRole('button', { name: '연동 확인 DM 받기' }).click();

      // payload 검증 — workspaceId 정수 + slackUserId 대문자 정규화
      const req = await linkCapture.waitForRequest();
      expect(req.url.pathname).toBe('/api/v1/oauth/slack/link-user');
      expect(req.payload).toMatchObject({ workspaceId: 1, slackUserId: 'U0ABCDEF12' });

      // 성공 토스트 검증
      await expect(
        page.getByText(/Slack 연동이 완료되었습니다/),
      ).toBeVisible();
    });

    test('잘못된 패턴 입력 → 클라이언트 검증으로 API 호출 차단 + 에러 토스트', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/channels/settings', SLACK_INSTALLED_NO_BINDING);
      // API가 호출되지 않아야 하지만 안전망으로 등록 (호출되면 테스트가 토스트 한 줄로는 잡지 못함)
      const linkCapture = await mockApi(
        page,
        'POST',
        '/api/v1/oauth/slack/link-user',
        {},
        { capture: true, status: 204 },
      );

      await page.goto('/settings/channels');
      const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();

      // U로 시작하지 않는 잘못된 값
      await slackCard.getByLabel('Slack 회원 ID').fill('XYZ123');
      await slackCard.getByRole('button', { name: '연동 확인 DM 받기' }).click();

      // 형식 에러 토스트 표시
      await expect(page.getByText(/올바른 Slack 회원 ID 형식이 아닙니다/)).toBeVisible();

      // API 호출되지 않았는지 검증 (캡처된 요청이 없어야 함)
      expect(linkCapture.requests).toHaveLength(0);
    });

    test('백엔드 4xx 응답 → 에러 메시지 토스트 표시', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/channels/settings', SLACK_INSTALLED_NO_BINDING);
      await mockApi(
        page,
        'POST',
        '/api/v1/oauth/slack/link-user',
        { message: 'Slack 봇이 해당 사용자에게 DM을 보낼 수 없습니다. 채널에 봇을 초대해주세요.' },
        { status: 400 },
      );

      await page.goto('/settings/channels');
      const slackCard = page.locator('[data-slot="card"]', { hasText: 'Slack' }).first();

      await slackCard.getByLabel('Slack 회원 ID').fill('U0ABCDEF12');
      await slackCard.getByRole('button', { name: '연동 확인 DM 받기' }).click();

      // 백엔드 메시지가 그대로 토스트로 노출되어 사용자에게 사유 안내
      await expect(
        page.getByText(/Slack 봇이 해당 사용자에게 DM을 보낼 수 없습니다/),
      ).toBeVisible();
    });
  });

  /**
   * 회귀 테스트: OAuth 팝업 60초 타임아웃 시 실제 연동 상태를 재확인해야 한다 (#665)
   * - 2FA·최초 로그인 등으로 60초를 넘기는 경우, 팝업 강제 종료 시점에 백엔드 콜백은
   *   이미 성공 처리됐을 수 있다. 타임아웃 분기가 무조건 "취소됨"을 띄우던 결함을 재현·검증한다.
   * - window.open을 스텁으로 교체해 실제 팝업 없이 타임아웃 로직만 격리 검증하고,
   *   Playwright Clock으로 60초를 가상 진행시켜 setTimeout을 실제 대기 없이 발동시킨다.
   */
  test.describe('OAuth 팝업 60초 타임아웃 (#665)', () => {
    /** window.open을 가짜 팝업 객체로 대체 — 실제 팝업 없이 타임아웃 분기만 검증 */
    async function stubPopup(page: Parameters<typeof mockApi>[0]) {
      await page.addInitScript(() => {
        (window as unknown as { open: typeof window.open }).open = () => {
          // 버튼은 opener 를 끊고 주소를 location 으로 보낸다(WD-405) — 그 두 속성을 갖춘다
          const fakePopup = {
            closed: false,
            opener: null,
            location: { href: '' },
            close(this: { closed: boolean }) {
              this.closed = true;
            },
          } as unknown as Window;
          (window as unknown as { __oauthPopup: unknown }).__oauthPopup = fakePopup;
          return fakePopup;
        };
      });
    }

    /** KAKAO oauthStartUrl을 내부 API 경로로 오버라이드 + auth-url 200 응답 모킹 */
    async function setupOAuthStartMocks(page: Parameters<typeof mockApi>[0], initialSettings: ChannelSetting[]) {
      await mockApi(page, 'GET', '/api/v1/channels/settings', initialSettings);
      await mockApi(page, 'GET', '/api/v1/oauth/kakao/auth-url', { url: 'https://kauth.kakao.com/oauth/authorize?client_id=test' });
    }

    const KAKAO_DISCONNECTED_WITH_API_PATH: ChannelSetting[] = MOCK_CHANNEL_SETTINGS.map((s) =>
      s.channel === 'KAKAO' ? { ...s, oauthStartUrl: '/api/v1/oauth/kakao/auth-url' } : s,
    );

    test('타임아웃 시점에 백엔드 콜백이 이미 성공 처리된 경우 — 성공 토스트 + 캐시 갱신', async ({
      authenticatedPage: page,
    }) => {
      await stubPopup(page);
      await setupOAuthStartMocks(page, KAKAO_DISCONNECTED_WITH_API_PATH);
      await page.clock.install();

      await page.goto('/settings/channels');
      await page.getByRole('button', { name: '연동하기' }).click();

      // 팝업이 열릴 때까지 대기 (실제 팝업 대신 스텁 객체)
      await page.waitForFunction(() => Boolean((window as unknown as { __oauthPopup: unknown }).__oauthPopup));

      // 타임아웃 발동 전, 백엔드 콜백이 이미 처리되어 KAKAO가 connected: true로 바뀐 상황을 시뮬레이션
      const CONNECTED_SETTINGS: ChannelSetting[] = KAKAO_DISCONNECTED_WITH_API_PATH.map((s) =>
        s.channel === 'KAKAO' ? { ...s, connected: true, enabled: true } : s,
      );
      await mockApi(page, 'GET', '/api/v1/channels/settings', CONNECTED_SETTINGS);

      // 60초 가상 진행 → setTimeout 타임아웃 분기 발동
      await page.clock.fastForward(60_000);

      // 실제로는 연동에 성공했으므로 "취소됨"이 아닌 성공 토스트가 표시되어야 한다
      await expect(page.getByText('KAKAO 연동이 완료되었습니다.')).toBeVisible();
      await expect(page.getByText('KAKAO 연동이 취소되었습니다.')).toHaveCount(0);

      // 캐시가 갱신되어 카드도 "연결됨" 상태(연결 해제 버튼 노출)로 반영되어야 한다
      await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible();
    });

    test('타임아웃 시점에 실제로 연동되지 않은 경우 — 기존과 동일하게 취소 토스트', async ({
      authenticatedPage: page,
    }) => {
      await stubPopup(page);
      await setupOAuthStartMocks(page, KAKAO_DISCONNECTED_WITH_API_PATH);
      await page.clock.install();

      await page.goto('/settings/channels');
      await page.getByRole('button', { name: '연동하기' }).click();

      await page.waitForFunction(() => Boolean((window as unknown as { __oauthPopup: unknown }).__oauthPopup));

      // 60초 가상 진행 — channel-settings는 여전히 미연결 상태를 반환
      await page.clock.fastForward(60_000);

      await expect(page.getByText('KAKAO 연동이 취소되었습니다.')).toBeVisible();
      await expect(page.getByRole('button', { name: '연동하기' })).toBeVisible();
    });
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

/** 선호 저장 요청 중 스위치 (WD-1150) — 비활성(disabled)이 아니라 busy 로 두고 토글은 무시한다. */
test.describe('채널 스위치 요청 중 busy (WD-1150)', () => {
  const PATH = '/api/v1/channels/settings/EMAIL/preference';

  /** 성공 뒤 재조회가 새 값(EMAIL 꺼짐)을 돌려주도록 채널 목록 GET 모킹을 바꾼다. */
  async function mockEmailOffSettings(page: Page) {
    await mockApi(
      page,
      'GET',
      '/api/v1/channels/settings',
      MOCK_CHANNEL_SETTINGS.map((s) => (s.channel === 'EMAIL' ? { ...s, enabled: false } : s)),
    );
  }

  test('요청 중 클릭·Space 를 더 해도 요청은 1회, disabled 없이 aria-busy', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(sw).toBeChecked();
    await sw.click();
    await expect(sw).toHaveAttribute('aria-busy', 'true');
    // 요청 중에는 요청한 값(꺼짐)을 바로 보이고, 보조기기에는 aria-disabled 로 토글이 무시됨을 알린다
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    await expect(sw).toHaveAttribute('aria-disabled', 'true');
    // 진짜 비활성이 아니므로 disabled 속성이 없다
    await expect(sw).not.toHaveAttribute('disabled');
    // aria-disabled 라 Playwright 가 비활성으로 보고 기다리므로 force 로 실제 클릭을 보낸다(앱이 무시해야 한다)
    await sw.click({ force: true });
    await sw.focus();
    await page.keyboard.press('Space');
    await expectSingleHeldRequest(() => held.requests.length);
    // 성공 후 재조회가 새 값(꺼짐)을 돌려주도록 목록 모킹을 바꿔 둔다
    await mockEmailOffSettings(page);
    held.release();
    await expect(sw).not.toHaveAttribute('aria-busy');
    await expect(sw).not.toHaveAttribute('aria-disabled');
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
  });

  test('요청 중에는 스위치 옆에 스피너가 보이고 끝나면 사라지며, 스위치는 움직이지 않는다 (WD-1169)', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    const spinner = page.getByTestId('channel-switch-pending-EMAIL');
    await expect(sw).toBeChecked();
    await expect(spinner).toHaveCount(0);
    const before = await sw.boundingBox();
    expect(before).not.toBeNull();
    await sw.click();
    await expect(spinner).toBeVisible();
    // 장식이므로 보조기기에는 숨긴다(상태는 스위치의 aria-busy 가 알린다)
    await expect(spinner).toHaveAttribute('aria-hidden', 'true');
    // 스피너 자리를 늘 확보하므로 스위치 위치가 그대로다
    expect((await sw.boundingBox())?.x).toBe(before?.x);
    held.release();
    await expect(spinner).toHaveCount(0);
  });

  test('고대비에서도 스피너가 보인다 (WD-1169)', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    const spinner = page.getByTestId('channel-switch-pending-EMAIL');
    await expect(sw).toBeChecked();
    await sw.click();
    await expect(spinner).toBeVisible();
    // 강제 색 모드에서도 스피너를 그리는 선(stroke)이 실제 색이고, 뒤 배경색과 달라야 보인다 — Loader2 는 stroke 로 그린다.
    // currentColor 가 배경과 같은 색으로 풀리면 비투명이어도 보이지 않으므로 가장 가까운 불투명 배경과 비교한다
    const { stroke, background } = await spinner.evaluate((el) => {
      let node: Element | null = el.parentElement;
      let bg = 'rgba(0, 0, 0, 0)';
      while (node) {
        const c = getComputedStyle(node).backgroundColor;
        if (c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent') {
          bg = c;
          break;
        }
        node = node.parentElement;
      }
      return { stroke: getComputedStyle(el).stroke, background: bg };
    });
    expect(stroke).not.toMatch(/^(none|transparent|rgba\(0, 0, 0, 0\))$/);
    expect(stroke).not.toBe(background);
    // 진짜 disabled 가 아니므로 GrayText 로 바뀌지 않는다
    await expect(sw).not.toHaveAttribute('disabled');
    held.release();
    await expect(spinner).toHaveCount(0);
  });

  test('PATCH 성공에서 잠김이 풀리고 재조회가 붙잡혀도 요청한 값을 유지한다 (WD-1169)', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(sw).toBeChecked();
    // 재조회(GET 목록)는 새 값(꺼짐)을 돌려주도록 바꾸고, PATCH 성공 뒤 그 응답을 붙잡는다 — 목록 모킹을 먼저 바꾸고 붙잡기를 나중에 건다
    await mockEmailOffSettings(page);
    const refetch = await holdRequests(page, '/api/v1/channels/settings', 'GET');
    // 클릭 전부터 data-state 변화를 모두 기록한다 — 고정 대기 없이 옛 값(checked)이 한 번이라도 비치면 잡아내기 위함
    await sw.evaluate((el) => {
      const w = window as unknown as { __states: string[] };
      w.__states = [];
      new MutationObserver(() => w.__states.push(el.getAttribute('data-state') ?? '')).observe(el, {
        attributes: true,
        attributeFilter: ['data-state'],
      });
    });
    await sw.click();
    await expect(sw).toHaveAttribute('aria-busy', 'true');
    held.release();
    await expect.poll(refetch.count).toBeGreaterThanOrEqual(1);
    // 재조회를 놓기 전 — 잠김은 PATCH 성공에서 풀리고, 캐시 선반영으로 요청한 값(꺼짐)과 토스트가 보인다 (WD-1169)
    await expect(sw).not.toHaveAttribute('aria-busy');
    await expect(sw).not.toHaveAttribute('aria-disabled');
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    await expect(page.getByText('이메일 채널이 비활성화되었습니다.')).toBeVisible();
    refetch.release();
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    const states = await page.evaluate(() => (window as unknown as { __states: string[] }).__states);
    expect(states, '재조회 전후로 옛 값(checked)으로 깜빡였다 (WD-1150)').not.toContain('checked');
  });

  test('PATCH 성공 뒤 재조회가 500 이어도 스위치는 새 값·토스트와 일치한다 (WD-1169)', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(sw).toBeChecked();
    // 첫 조회는 끝났으므로 이후 재조회만 500 으로 실패시킨다
    await mockApi(page, 'GET', '/api/v1/channels/settings', { message: '서버 오류' }, { status: 500 });
    let failedGets = 0;
    page.on('response', (res) => {
      if (new URL(res.url()).pathname === '/api/v1/channels/settings' && res.request().method() === 'GET' && res.status() === 500) {
        failedGets += 1;
      }
    });
    await sw.click();
    held.release();
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    await expect(sw).not.toHaveAttribute('aria-busy');
    await expect(page.getByText('이메일 채널이 비활성화되었습니다.')).toBeVisible();
    // 재조회가 500 으로 끝난 뒤에도(앱의 1회 재시도까지 실패해 오류 상태가 된 뒤에도) 새 값이 유지돼야 한다
    await expect.poll(() => failedGets, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    // 카드는 맞게 보이므로 빨간 오류 배너로 가리지 않고, 서버와 다를 수 있다는 가벼운 안내만 띄운다 (WD-1169 리뷰)
    await expect(page.getByText('채널 설정을 불러오는 데 실패했습니다.', { exact: false })).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: '최신 채널 설정을 불러오지 못했습니다.' })).toBeVisible();
  });

  test('성공 직후 다시 토글하면 두 번째 PATCH 가 나간다 (WD-1169)', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const bodies: unknown[] = [];
    await page.route(
      (url) => url.pathname === PATH,
      async (route) => {
        if (route.request().method() !== 'PATCH') return route.fallback();
        bodies.push(route.request().postDataJSON());
        await route.fulfill({ status: 204, contentType: 'application/json', body: '{}' });
      },
    );
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await expect(sw).toBeChecked();
    // 재조회는 붙잡아 둔다 — 재조회가 끝나기 전에도 다시 토글할 수 있어야 한다
    const refetch = await holdRequests(page, '/api/v1/channels/settings', 'GET');
    await sw.click();
    await expect.poll(refetch.count).toBeGreaterThanOrEqual(1);
    await expect(sw).not.toHaveAttribute('aria-busy');
    await sw.click();
    await expect.poll(() => bodies.length).toBe(2);
    expect(bodies).toEqual([{ enabled: false }, { enabled: true }]);
    // 두 번째 토글의 재조회까지 붙잡힌 뒤 놓고, 붙잡은 재조회가 모두(끝나거나 취소돼) 정리될 때까지 기다린다 —
    // 시험이 끝난 뒤 남은 요청이 다음 시험을 흔들지 않게
    await expect.poll(refetch.count).toBeGreaterThanOrEqual(2);
    let settledGets = 0;
    const onSettled = (req: { url: () => string; method: () => string }) => {
      if (new URL(req.url()).pathname === '/api/v1/channels/settings' && req.method() === 'GET') settledGets += 1;
    };
    page.on('requestfinished', onSettled);
    page.on('requestfailed', onSettled);
    refetch.release();
    await expect.poll(() => settledGets).toBeGreaterThanOrEqual(refetch.count());
    await expect(sw).toBeChecked();
    await expect(sw).not.toHaveAttribute('aria-busy');
  });

  test('요청이 500 으로 끝나면 원래 상태로 돌아오고 aria-busy 가 사라진다', async ({ authenticatedPage: page }) => {
    await setupChannelMocks(page);
    const held = await holdApi(page, 'PATCH', PATH, 500, { message: '서버 오류' });
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: '이메일 채널 활성화' });
    await sw.click();
    await expect(sw).toHaveAttribute('aria-busy', 'true');
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    held.release();
    await expect(sw).not.toHaveAttribute('aria-busy');
    // 실패하면 서버 값(켜짐)으로 돌아온다
    await expect(sw).toBeChecked();
    await expect(page.getByText('채널 설정 변경에 실패했습니다.')).toBeVisible();
  });
});
