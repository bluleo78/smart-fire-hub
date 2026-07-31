import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 목록 정렬 전이 가드 (#359 조사 산출물)
 *
 * 무엇: CPU 스로틀로 리렌더를 지연시킨 상태에서 헤더를 대기 없이 연속 클릭해도
 *       asc → desc → none 순환이 유실되지 않는지 검증한다.
 *
 * 왜: #358(데이터셋 목록)은 setSearchParams 가 react-router 내부에서
 *     startTransition 으로 감싸져 지연 가능한 레인을 타기 때문에 이 하네스에서 결정적으로 실패했다.
 *     이 페이지는 정렬 상태를 로컬 useState 로 들고 있고 SortableHeader 가 onClick 에서
 *     onSort 를 직접 호출하므로(이산 갱신, 이벤트마다 flush) 동일 결함이 재현되지 않는다.
 *     다만 PipelineListPage 는 "백엔드 sort 파라미터 연동은 후속 작업"으로 남아 있어,
 *     서버 사이드 정렬(useSearchParams)로 전환되는 순간 #358 과 같은 레인에 들어간다.
 *     이 테스트는 그 리팩터링을 대비한 사전 가드다.
 */
test.describe('파이프라인 목록 — 정렬 전이 가드 (#359)', () => {
  test('리렌더가 지연되는 상황에서 연속 클릭해도 정렬 해제까지 전이가 유실되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineMocks(page, 5);

    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });

    await page.goto('/pipelines');

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await nameHeader.getByRole('button').click(); // asc
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    // 중간 상태를 기다리지 않고 연속 클릭 — desc 를 거쳐 정렬 해제(none)에 도달해야 한다
    await nameHeader.getByRole('button').click(); // desc
    await nameHeader.getByRole('button').click(); // none
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();

    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  });
});
