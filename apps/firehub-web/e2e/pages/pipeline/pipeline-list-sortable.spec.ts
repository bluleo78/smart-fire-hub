import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 목록 정렬 전이 가드 (#359 조사 산출물)
 *
 * 무엇: 렌더를 기다리지 않고 헤더를 연속 클릭해도 asc → desc → none 순환이 유실되지 않는지 검증한다.
 *
 * 왜: #358(데이터셋 목록)은 정렬을 URL(setSearchParams)에 두어 갱신이 transition 레인을 타고,
 *     렌더 전에 온 다음 클릭이 이전 렌더의 sortOrder 로 전이를 계산해 desc→desc 로 멈췄다.
 *     이 페이지는 지금 정렬을 로컬 useState 로 들고 있어(이산 갱신 — 클릭마다 다음 작업 전에 렌더)
 *     같은 결함이 없다. 다만 「백엔드 sort 파라미터 연동은 후속 작업」이라 URL 정렬로 바꾸는 순간
 *     #358 과 같은 레인에 들어간다. 이 시험은 그 리팩터링을 대비한 사전 가드다.
 *
 * 어떻게(WD-948): 시간(CPU 스로틀) 대신 이벤트 루프 순서에 기댄다 — 2번째 클릭 뒤 마이크로태스크 하나만 넘기고 3번째를 누른다.
 *     useState 의 동기 갱신은 클릭 중에 잡힌 마이크로태스크에서 우리 await 보다 먼저 렌더되지만(실제 사용자 클릭과 같은 결과,
 *     React 가 동기 레인을 마이크로태스크로 렌더하는 동작에 기댄다),
 *     URL 정렬의 transition 렌더는 다음 작업으로 미뤄져 3번째 클릭이 낡은 값으로 계산하면 desc 에 멈춘다.
 *     한 작업 안 두 번(데이터셋 시험 방식)은 useState 렌더도 건너뛰어 지금 화면에서 사용자에게 없는 실패를 낸다.
 */
test.describe('파이프라인 목록 — 정렬 전이 가드 (#359)', () => {
  test('렌더 전에 연속 클릭해도 정렬 해제까지 전이가 유실되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineMocks(page, 5);
    await page.goto('/pipelines');
    // 목록이 다 그려진 뒤에 누른다 — 로드 중 렌더가 연속 클릭에 섞이지 않게
    await expect(page.getByRole('row').filter({ hasText: /파이프라인 \d/ })).toHaveCount(5);

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await nameHeader.getByRole('button').click(); // asc
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    // desc 뒤 마이크로태스크 하나만 넘기고 none — 위 「어떻게」
    await nameHeader.getByRole('button').evaluate(async (button: HTMLButtonElement) => {
      button.click(); // desc
      await Promise.resolve();
      button.click(); // none
    });
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();
  });
});

// WD-880 — 정렬 헤더 버튼(공용 SortableHeader)의 클릭 상자는 높이 32px 이상이어야 한다.
test('정렬 헤더 버튼이 32px 이상 (WD-880)', async ({ authenticatedPage: page }) => {
  await setupPipelineMocks(page, 5);
  await page.goto('/pipelines');

  const heads = page.locator('th[aria-sort] button');
  await expect(heads.first()).toBeVisible();
  const count = await heads.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) {
    const b = await heads.nth(i).boundingBox();
    expect(b).not.toBeNull();
    expect(b!.width).toBeGreaterThanOrEqual(32);
    expect(b!.height).toBeGreaterThanOrEqual(32);
  }
});
