import { StatusBadge as UnifiedStatusBadge } from '@/components/ui/status-badge';

/**
 * API 연결 상태 배지 (도메인 wrapper)
 * - UP   → success(녹색) "정상"
 * - DOWN → error(빨강)  "이상"
 * - null → unknown(테두리) "미확인"
 *
 * 색 매핑은 공통 StatusBadge에 위임하여 앱 전체 의미↔색 일관성을 보장한다.
 */
export function StatusBadge({
  status,
  checkedAt,
}: {
  status: 'UP' | 'DOWN' | null;
  checkedAt: string | null;
}) {
  const title = checkedAt
    ? `${new Date(checkedAt).toLocaleString('ko-KR')} 확인`
    : undefined;

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
