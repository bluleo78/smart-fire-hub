import { Badge } from '../../../components/ui/badge';

interface ChannelStatusBadgeProps {
  connected: boolean;
  needsReauth: boolean;
}

/**
 * 채널 연결 상태 배지
 * - 연결됨: 초록
 * - 재인증 필요: 주황
 * - 미연결: 회색
 */
export function ChannelStatusBadge({ connected, needsReauth }: ChannelStatusBadgeProps) {
  if (connected && needsReauth) {
    return (
      <Badge variant="outline" className="border-orange-400 text-orange-600 dark:text-orange-400">
        재인증 필요
      </Badge>
    );
  }

  if (connected) {
    return (
      <Badge variant="outline" className="border-green-500 text-green-600 dark:text-green-400">
        연결됨
      </Badge>
    );
  }

  return (
    <Badge variant="outline" className="text-muted-foreground">
      미연결
    </Badge>
  );
}
