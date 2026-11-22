import { expect, type Page } from '@playwright/test';

/**
 * 표 조회 실패 행(TableErrorRow, WD-930·WD-938)이 보조기술에 알려지는지 확인하는 공용 단언.
 * - alert 영역은 aria-live=assertive 이고, 낭독 지연(DelayedStatus) 뒤 문구가 접근성 트리에 실제로 드러난다.
 *   aria-hidden 노드는 aria 스냅샷에 안 잡히므로, 숨긴 상태에 멈추면(textContent 만 보는 toHaveText 는 통과해도) 여기서 실패한다.
 * - alert 는 td 안쪽에 있어 셀이 cell 역할을 유지한다(표 구조 보존). alert 자손 문구는 셀 이름에 안 잡혀 has 로 찾는다.
 * - options.action 을 주면 셀 안에 그 이름의 버튼이 보이고 alert 밖에 있음을 확인한다(재시도 버튼 등).
 *   없으면 셀 문구가 message 와 정확히 같아야 한다.
 */
export async function expectTableErrorAlert(
  page: Page,
  message = '데이터를 불러오는 데 실패했습니다.',
  options: { action?: string } = {},
): Promise<void> {
  // 문구로 거르지 않는다 — 실패 화면에 다른 alert 이 겹쳐 뜨면(이중 낭독) strict 위반으로 잡는다
  const alert = page.getByRole('alert');
  // 전역 재시도 1회 뒤에 확정되도록 넉넉히 기다린다
  await expect(alert).toBeVisible({ timeout: 15000 });
  await expect(alert).toHaveAttribute('aria-live', 'assertive');
  // 서버 사유에 「: 」 같은 YAML 문법이 들어도 깨지지 않게 따옴표로 감싼다
  await expect(alert).toMatchAriaSnapshot(`- alert: ${JSON.stringify(message)}`);
  const errorCell = page.getByRole('cell').filter({ has: alert });
  await expect(errorCell).toHaveCount(1);
  if (options.action === undefined) {
    await expect(errorCell).toHaveText(message);
    return;
  }
  await expect(errorCell).toContainText(message);
  await expect(errorCell.getByRole('button', { name: options.action })).toBeVisible();
  await expect(alert.getByRole('button')).toHaveCount(0);
}
