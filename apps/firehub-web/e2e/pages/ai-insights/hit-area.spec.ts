import type { Locator, Page } from '@playwright/test';

import {
  mockProactiveAdminPermissions,
  mockProactivePermissions,
  setupJobDetailMocks,
  setupJobListMocks,
  setupTemplateListMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AI 인사이트 화면군 히트 영역 회귀 시험 (WD-891)
 * - 스마트 작업 목록·상세, 리포트 양식 화면의 클릭 요소(탭·행 아이콘 버튼·활성화 스위치·양식 이름 링크)가
 *   디자인 최소 히트 영역 32×32px 를 넘는지 boundingBox 로 실측한다.
 * - 크기 단언은 expect.soft 로 모아 남긴다 — 한 번의 실행에서 부족한 요소를 전부 보고해야,
 *   원인을 고치는 작업(공용 탭·스위치 프리미티브 / 페이지 전용 클래스)이 어디까지 통과했는지 한눈에 알 수 있기 때문이다.
 *   대신 요소 부재 단언만 단호하게 남겨, 모킹·권한 문제로 요소가 0건일 때 빈 스캔으로 통과하지 못하게 한다.
 */

/** 디자인 최소 히트 영역 — 가로·세로 모두 이 이상이어야 한다(픽셀). */
const MIN_HIT = 32;
/** 행 아이콘 버튼 안 시각 아이콘 최소 크기 — 버튼 박스는 32, 아이콘 자체는 24px 이상으로 둔다. */
const MIN_ICON = 24;
/** 스위치 알약(track) 크기 — 히트 상자가 32로 넓어져도 알약 시각 크기는 32×18 그대로여야 한다. */
const TRACK_WIDTH = 32;
const TRACK_HEIGHT = 18;
/** 체크 상태에서 thumb 오른쪽 끝과 track 오른쪽 끝 사이에 남아야 할 최소 여백(px) — thumb 이 알약 밖으로 삐져나가지 않게 한다. */
const MIN_THUMB_GAP = 1;

/**
 * 요소 하나의 히트 영역 실측 — soft 단언이라 실패해도 뒤 요소 실측을 계속 모은다.
 * @param locator - 실측 대상 요소
 * @param label - 실패 메시지에 붙는 이름(어디의 무엇인지)
 * @param min - 최소 크기(px) — 기본 32, 시각 아이콘 등 24 기준 요소에 쓴다
 */
async function expectHitArea(locator: Locator, label: string, min = MIN_HIT) {
  const box = await locator.boundingBox();
  expect.soft(box, `${label}: 실측할 박스가 없다 — 요소가 보이지 않는다`).not.toBeNull();
  if (!box) return;
  expect.soft(box.width, `${label} 가로`).toBeGreaterThanOrEqual(min);
  expect.soft(box.height, `${label} 세로`).toBeGreaterThanOrEqual(min);
}

/**
 * 반복 실측 대상(탭·버튼·스위치·링크)이 1건 이상 보이는지 확인하고, 전수 실측에 쓸 목록을 돌려준다.
 * 0건이면 단호하게 실패 — 모킹·권한 누락으로 요소가 없어도 빈 스캔으로 회귀가 통과하지 않게 하기 위함이다.
 * @param targets - 실측할 요소 목록
 * @param label - 실패 메시지에 붙는 이름(어디의 무엇인지)
 */
async function expectTargetsPresent(targets: Locator, label: string) {
  // 첫 요소가 보일 때까지 기다려 로딩 중 0건으로 성급하게 실패하지 않게 한다.
  await expect(targets.first(), `${label}: 대상이 렌더링되지 않았다`).toBeVisible();
  const count = await targets.count();
  expect(count, `${label}: 렌더링된 대상 개수`).toBeGreaterThan(0);
  return { count, targets };
}

/**
 * 화면의 role=tab 전부를 히트 영역 기준으로 실측한다.
 * @param page - 대상 페이지
 * @param label - 실패 메시지에 붙는 화면 이름
 */
async function expectTabsHitArea(page: Page, label: string) {
  const { count, targets: tabs } = await expectTargetsPresent(page.getByRole('tab'), `${label} 탭`);
  for (let i = 0; i < count; i++) {
    await expectHitArea(tabs.nth(i), `${label} 탭 ${i + 1}`);
  }
}

test.describe('AI 인사이트 히트 영역 32×32 (WD-891)', () => {
  // 생성·복제·지금 실행 버튼은 proactive:write 가 있어야 보인다(WD-133) — 이 spec 은 쓰기 권한 사용자를 기본으로 한다.
  // 관리자 탭이 필요한 목록 테스트는 테스트 본문에서 admin 권한을 다시 등록해 나중 등록이 이기게 한다(권한 fixture 문서 참조).
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('스마트 작업 목록의 탭·행 버튼·활성화 스위치가 32×32 이상이다', async ({ authenticatedPage: page }) => {
    // 「내 작업」/「워크스페이스 전체」 탭은 proactive:admin 일 때만 렌더링된다 — 본문에서 등록해야 beforeEach 권한보다 이긴다.
    await mockProactiveAdminPermissions(page);
    // 작업 2건 — 행마다 복제·지금 실행 버튼과 활성화 스위치가 생긴다.
    await setupJobListMocks(page, 2);

    await page.goto('/ai-insights/jobs');

    // 1) 관리자 보기 탭 — 0건이면 권한 모킹이 실패한 것이므로 빈 스캔으로 남기지 않는다.
    await expectTabsHitArea(page, '목록');

    // 2) 행 동작 아이콘 버튼(복제·지금 실행)과 그 안쪽 시각 아이콘
    for (const name of ['복제', '지금 실행']) {
      const { count, targets: buttons } = await expectTargetsPresent(
        page.getByRole('button', { name }),
        `행 버튼 「${name}」`,
      );
      for (let i = 0; i < count; i++) {
        const button = buttons.nth(i);
        await expectHitArea(button, `행 버튼 「${name}」 ${i + 1}`);
        // 시각 아이콘은 24px 이상 — 버튼 박스만 32를 채우고 아이콘을 더 키우면 또 오조작 대상이 된다.
        await expectHitArea(button.locator('svg'), `행 버튼 「${name}」 ${i + 1} 안 아이콘`, MIN_ICON);
      }
    }

    // 3) 활성화 스위치 — 루트(히트 상자) ≥32, 안쪽 알약(track) 정확히 32×18, 체크 상태 thumb 은 track 안.
    const { count: switchCount, targets: switches } = await expectTargetsPresent(
      page.getByRole('switch'),
      '활성화 스위치',
    );
    for (let i = 0; i < switchCount; i++) {
      await expectHitArea(switches.nth(i), `활성화 스위치 ${i + 1}`);
    }

    // thumb 여백은 체크된 스위치에서만 의미가 있다 — 꺼진 스위치는 thumb 이 왼쪽이라 단언이 공허하게 통과한다(팩토리 기본 enabled: true).
    const checkedSwitch = switches.first();
    await expect(checkedSwitch).toBeChecked();

    // 알약은 히트 상자와 분리되어 따로 있어야 한다 — 수정 전에는 루트 자체가 알약이라 32×18 밖에 되지 않는다.
    const track = checkedSwitch.locator('[data-slot="switch-track"]');
    await expect(
      track,
      '스위치 안쪽 [data-slot=switch-track] 이 없다 — 히트 상자와 알약으로 나뉘지 않았다 (WD-891)',
    ).toHaveCount(1);

    const trackBox = await track.boundingBox();
    expect.soft(trackBox, '스위치 track: 실측할 박스가 없다').not.toBeNull();
    if (trackBox) {
      // 알약 시각 크기는 반올림해 정확히 32×18 — 히트 상자를 넓혀도 알약이 커지면 안 된다.
      expect.soft(Math.round(trackBox.width), '스위치 track 가로').toBe(TRACK_WIDTH);
      expect.soft(Math.round(trackBox.height), '스위치 track 세로').toBe(TRACK_HEIGHT);
    }

    const thumbBox = await checkedSwitch.locator('[data-slot="switch-thumb"]').boundingBox();
    expect.soft(thumbBox, '스위치 thumb: 실측할 박스가 없다').not.toBeNull();
    if (trackBox && thumbBox) {
      // 체크 상태 thumb 오른쪽 끝과 track 오른쪽 끝 사이 여백 ≥1 — thumb translate 는 thumb 폭 기준이라 알약이 줄면 밖으로 삐져나온다.
      const gap = trackBox.x + trackBox.width - (thumbBox.x + thumbBox.width);
      expect.soft(gap, '스위치 thumb 의 track 오른쪽 여백').toBeGreaterThanOrEqual(MIN_THUMB_GAP);
    }
  });

  test('작업 상세의 개요·모니터링·실행 이력 탭이 32×32 이상이다', async ({ authenticatedPage: page }) => {
    // 잡 1 단건 + 실행 이력·템플릿·이상 탐지 API 를 함께 모킹한다.
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1?tab=overview');

    // 개요 탭이 보이는지 먼저 확인 — 진입에 실패한 것을 히트 영역 실패로 오인하지 않게 한다.
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expectTabsHitArea(page, '상세');
  });

  test('리포트 화면의 탭과 양식 이름 링크가 32×32 이상이다', async ({ authenticatedPage: page }) => {
    // 양식 목록 + 생성된 리포트 탭·스마트 작업 선택기가 조회하는 목록 API — 비어 있어도 렌더는 방해하지 않는다.
    await setupTemplateListMocks(page);
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);

    // /ai-insights/templates 는 리포트 화면의 양식 탭으로 리다이렉트된다.
    await page.goto('/ai-insights/templates');

    await expectTabsHitArea(page, '리포트');

    // 양식 이름 링크 전수 실측 — 링크가 0건이면 모킹 문제가 있어 단호하게 실패시킨다.
    const { count, targets: links } = await expectTargetsPresent(
      page.locator('a[href^="/ai-insights/templates/"]'),
      '양식 이름 링크',
    );
    for (let i = 0; i < count; i++) {
      await expectHitArea(links.nth(i), `양식 이름 링크 ${i + 1}`);
    }
  });
});
