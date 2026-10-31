import type { DashboardShareImpact } from '../../types/analytics';

interface DashboardShareNoticeProps {
  /** 저장된(창을 열 때의) 공개 여부 — 새 대시보드면 false */
  wasShared: boolean;
  /** 스위치의 지금 값 */
  isShared: boolean;
  /** 공유 영향 개수 — 새 대시보드(위젯 없음)·조회 전·조회 실패면 없음 */
  impact?: DashboardShareImpact;
}

/** 「차트 2개·저장 쿼리 1개」 — 0개인 쪽은 뺀다. 둘 다 0이면 빈 문자열 */
function countPhrase(charts: number, queries: number): string {
  const parts: string[] = [];
  if (charts > 0) parts.push(`차트 ${charts}개`);
  if (queries > 0) parts.push(`저장 쿼리 ${queries}개`);
  return parts.join('·');
}

/**
 * 「공개 대시보드」 스위치 아래 안내(WD-325).
 *
 * 대시보드를 공유하면 위젯이 쓰는 내 비공개 차트·저장 쿼리가 함께 공개되고(WD-194), 공유를 꺼도 되돌리지 않는다 — 어느 것이 대시보드
 * 때문에 공개됐는지 기록하지 않아, 일괄로 되돌리면 작성자가 따로 공개한 것까지 비공개가 되기 때문이다. 예전에는 이 사실을 알리지 않아
 * 작성자는 공유를 끄면 차트도 다시 자기만 보는 줄 알았다. 그래서
 * - 켤 때: 새로 공개될 내 차트·쿼리 개수를 알리고(없으면 앞으로 넣는 것도 공개된다고만),
 * - 끌 때: 계속 공개로 남는 개수와 되돌리는 방법을 알린다.
 */
export function DashboardShareNotice({ wasShared, isShared, impact }: DashboardShareNoticeProps) {
  let text: string | null = null;
  if (isShared) {
    const newly = !wasShared && impact ? countPhrase(impact.privateChartCount, impact.privateQueryCount) : '';
    text = newly
      ? `이 대시보드의 내 ${newly}도 함께 공개됩니다. 앞으로 넣는 내 차트·저장 쿼리도 공개됩니다.`
      : '이 대시보드에 넣는 내 차트·저장 쿼리도 함께 공개됩니다.';
  } else if (wasShared && impact) {
    const remaining = countPhrase(impact.sharedChartCount, impact.sharedQueryCount);
    if (remaining) {
      text = `함께 공개된 내 ${remaining}는 계속 공개 상태입니다. 차트·저장 쿼리 목록에서 각각 비공개로 바꿀 수 있습니다.`;
    }
  }
  if (!text) return null;
  return (
    <p data-testid="dashboard-share-notice" className="text-xs text-muted-foreground">
      {text}
    </p>
  );
}
