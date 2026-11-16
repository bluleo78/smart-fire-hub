import { StatusBadge as UnifiedStatusBadge } from '@/components/ui/status-badge';
import { isBlankLikeServer } from '@/lib/api-connection-secret';
import { parseUtcDate } from '@/lib/formatters';

/**
 * API 연결 상태 배지 (도메인 wrapper)
 * - UP   → success(녹색) "정상"
 * - DOWN → error(빨강)  "이상"
 * - null → unknown(테두리) "미확인"(헬스체크 경로가 있을 때)
 * - 헬스체크 경로가 없음 → 상태와 상관없이 unknown(테두리) "점검 안 함"(WD-753)
 *
 * 색 매핑은 공통 StatusBadge에 위임하여 앱 전체 의미↔색 일관성을 보장한다.
 */
export function StatusBadge({
  status,
  checkedAt,
  healthCheckPath,
}: {
  status: 'UP' | 'DOWN' | null;
  checkedAt: string | null;
  /**
   * 연결의 헬스체크 경로(WD-753). null·빈 값이면 10분 주기 점검을 하지 않으므로 마지막 상태와 상관없이 「점검 안 함」으로 보인다 —
   * 경로를 비운 뒤에도 진행 중이던 점검이 상태를 다시 쓰거나, 서버가 상태를 지우기 전에 경로를 비운 옛 연결에 지난 「이상」이 남아도
   * 고칠 길 없는 상태로 보이지 않게, 판단 기준을 상태가 아니라 경로로 둔다(WD-753 리뷰).
   * 빈 값 판정은 서버(`String.isBlank()`)와 같은 공백 집합으로 한다.
   */
  healthCheckPath: string | null;
}) {
  const title = checkedAt
    ? `${parseUtcDate(checkedAt).toLocaleString('ko-KR')} 확인`
    : undefined;

  // 경로가 없으면 상태보다 먼저 본다 — 점검하지 않는 연결의 지난 결과는 지금 상태가 아니다(WD-753 리뷰)
  if (healthCheckPath === null || isBlankLikeServer(healthCheckPath)) {
    return (
      <UnifiedStatusBadge type="unknown" title="헬스체크 경로가 없어 자동 점검하지 않습니다">
        점검 안 함
      </UnifiedStatusBadge>
    );
  }
  if (!status) {
    return <UnifiedStatusBadge type="unknown">미확인</UnifiedStatusBadge>;
  }
  if (status === 'UP') {
    return (
      <UnifiedStatusBadge type="success" title={title}>
        정상
      </UnifiedStatusBadge>
    );
  }
  return (
    <UnifiedStatusBadge type="error" title={title}>
      이상
    </UnifiedStatusBadge>
  );
}
