import { expect, type Page } from '@playwright/test';

/**
 * 표 조회 실패 행(TableErrorRow, WD-930)이 보조기술에 알려지는지 확인하는 공용 단언.
 * - alert 영역은 aria-live=assertive 이고, 낭독 지연(DelayedStatus) 뒤 문구가 접근성 트리에 실제로 드러난다.
 *   aria-hidden 노드는 aria 스냅샷에 안 잡히므로, 숨긴 상태에 멈추면(textContent 만 보는 toHaveText 는 통과해도) 여기서 실패한다.
 * - alert 는 td 안쪽에 있어 셀이 cell 역할을 유지한다(표 구조 보존). alert 자손 문구는 셀 이름에 안 잡혀 has 로 찾는다.
 */
export async function expectTableErrorAlert(page: Page, message = '데이터를 불러오는 데 실패했습니다.'): Promise<void> {
  const alert = page.getByRole('alert');
  await expect(alert).toHaveAttribute('aria-live', 'assertive');
  await expect(alert).toMatchAriaSnapshot(`- alert: ${message}`);
  const errorCell = page.getByRole('cell').filter({ has: alert });
  await expect(errorCell).toHaveCount(1);
  await expect(errorCell).toHaveText(message);
}
