import { Badge } from '@/components/ui/badge';

/**
 * API 연결 상태 배지
 * - UP → 정상(success)
 * - DOWN → 이상(destructive)
 * - null → 미확인(outline)
 */
export function StatusBadge({
  status,
  checkedAt,
}: {
  status: 'UP' | 'DOWN' | null;
  checkedAt: string | null;
}) {
  if (!status) return <Badge variant="outline">미확인</Badge>;
  const title = checkedAt
    ? `${new Date(checkedAt).toLocaleString('ko-KR')} 확인`
    : undefined;
  if (status === 'UP') {
    return (
      <Badge
        className="bg-success text-success-foreground hover:bg-success/80"
        title={title}
      >
        정상
      </Badge>
    );
  }
  return (
    <Badge variant="destructive" title={title}>
      이상
    </Badge>
  );
}
