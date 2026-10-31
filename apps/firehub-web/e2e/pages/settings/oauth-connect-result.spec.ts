import type { Page } from '@playwright/test';

import type { ChannelSetting } from '../../../src/api/channels';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 알림 채널 OAuth 연동 결과 안내 (WD-405)
 * - 예전에는 제공자 동의 화면에서 「취소」하면 콜백이 500 JSON 을 팝업에 남기고, 원래 화면은 아무 안내도 하지 않았다.
 *   또 팝업을 noopener 로 열어 window.open 이 늘 null 을 돌려줘, 실제로 열렸는데도 「팝업이 차단되었습니다」가 떴다.
 * - 이제 콜백 페이지가 BroadcastChannel('firehub-oauth') 로 결과를 알리고 창을 닫으며, 버튼은 그 결과를 토스트로 보인다.
 */

const RESULT_CHANNEL = 'firehub-oauth';
const KAKAO_AUTHORIZE = 'https://kauth.kakao.com/oauth/authorize?client_id=test';

const SETTINGS: ChannelSetting[] = [
  {
    channel: 'KAKAO',
    enabled: false,
    connected: false,
    needsReauth: false,
    displayAddress: null,
    oauthStartUrl: '/api/v1/oauth/kakao/auth-url',
    workspaceId: null,
  },
];

const CONNECTED: ChannelSetting[] = SETTINGS.map((s) => ({ ...s, connected: true, enabled: true }));

/** window.open 을 가짜 팝업으로 바꾼다 — 버튼이 보낸 주소를 확인할 수 있게 window.__oauthPopup 에 남긴다 */
async function stubPopup(page: Page) {
  await page.addInitScript(() => {
    window.open = () => {
      const fakePopup = { closed: false, opener: {}, location: { href: '' }, close() {} } as unknown as Window;
      (window as unknown as { __oauthPopup: unknown }).__oauthPopup = fakePopup;
      return fakePopup;
    };
  });
}

/** 콜백 페이지가 보내는 것과 같은 결과 알림을 보낸다(같은 출처의 다른 BroadcastChannel 인스턴스) */
async function broadcastResult(page: Page, channel: string, result: string) {
  await page.evaluate(
    ([name, ch, res]) => {
      const bc = new BroadcastChannel(name);
      bc.postMessage({ channel: ch, result: res });
      bc.close();
    },
    [RESULT_CHANNEL, channel, result],
  );
}

/** 「연동하기」를 눌러 (가짜) 팝업이 열린 상태까지 만든다 */
async function startConnect(page: Page) {
  await mockApi(page, 'GET', '/api/v1/channels/settings', SETTINGS);
  await mockApi(page, 'GET', '/api/v1/oauth/kakao/auth-url', { url: KAKAO_AUTHORIZE });
  await page.goto('/settings/channels');
  await page.getByRole('button', { name: '연동하기' }).click();
  await page.waitForFunction(() => Boolean((window as unknown as { __oauthPopup: unknown }).__oauthPopup));
}

test.describe('알림 채널 OAuth 연동 결과 안내 (WD-405)', () => {
  test('동의를 취소하면 원래 화면에 「취소」 안내가 뜨고, 팝업은 opener 를 끊은 채 제공자 주소로 간다', async ({
    authenticatedPage: page,
  }) => {
    await stubPopup(page);
    await startConnect(page);

    const popup = await page.evaluate(() => {
      const p = (window as unknown as { __oauthPopup: { opener: unknown; location: { href: string } } }).__oauthPopup;
      return { opener: p.opener, href: p.location.href };
    });
    expect(popup).toEqual({ opener: null, href: KAKAO_AUTHORIZE });

    await broadcastResult(page, 'KAKAO', 'cancelled');
    await expect(page.getByText('KAKAO 연동이 취소되었습니다.')).toBeVisible();
    await expect(page.getByText('팝업이 차단되었습니다', { exact: false })).toHaveCount(0);
  });

  test('연결되면 성공 안내와 함께 설정을 다시 읽어 카드가 「연결됨」으로 바뀐다', async ({ authenticatedPage: page }) => {
    await stubPopup(page);
    await startConnect(page);

    await mockApi(page, 'GET', '/api/v1/channels/settings', CONNECTED);
    await broadcastResult(page, 'KAKAO', 'connected');

    await expect(page.getByText('KAKAO 연동이 완료되었습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible();
  });

  test('제공자 오류(failed)는 다시 시도하라는 오류 안내를 띄우고, 다른 채널의 결과는 무시한다', async ({
    authenticatedPage: page,
  }) => {
    await stubPopup(page);
    await startConnect(page);

    await broadcastResult(page, 'SLACK', 'cancelled');
    await broadcastResult(page, 'KAKAO', 'failed');

    await expect(page.getByText('KAKAO 연동을 완료하지 못했습니다. 다시 시도해 주세요.')).toBeVisible();
    await expect(page.getByText('SLACK 연동이 취소되었습니다.')).toHaveCount(0);
  });

  test('실제 팝업 — 열리면 「차단됨」 안내가 없고, 콜백 페이지가 취소를 알리고 창을 닫으면 원래 화면에 취소 안내가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    // 제공자 대신, 취소 뒤 돌아오는 콜백 페이지와 같은 일(결과 알림 + 창 닫기)을 하는 같은 출처 페이지로 보낸다.
    // 팝업은 새 페이지라 page.route 가 닿지 않으므로 컨텍스트에 건다.
    await page.context().route('**/oauth-cancel-test', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: `<!doctype html><p>카카오 연결을 취소했습니다.</p><script>setTimeout(()=>{const c=new BroadcastChannel('${RESULT_CHANNEL}');c.postMessage({channel:'KAKAO',result:'cancelled'});c.close();window.close();},300)</script>`,
      }),
    );
    await mockApi(page, 'GET', '/api/v1/channels/settings', SETTINGS);
    await mockApi(page, 'GET', '/api/v1/oauth/kakao/auth-url', { url: '/oauth-cancel-test' });
    await page.goto('/settings/channels');

    const popupPromise = page.waitForEvent('popup');
    await page.getByRole('button', { name: '연동하기' }).click();
    const popup = await popupPromise;
    await popup.waitForEvent('close');

    await expect(page.getByText('KAKAO 연동이 취소되었습니다.')).toBeVisible();
    await expect(page.getByText('팝업이 차단되었습니다', { exact: false })).toHaveCount(0);
  });
});
