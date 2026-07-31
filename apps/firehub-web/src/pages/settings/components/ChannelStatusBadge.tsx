import { Badge } from '../../../components/ui/badge';

interface ChannelStatusBadgeProps {
  connected: boolean;
  needsReauth: boolean;
}

/**
 * 채널 연결 상태 배지
 * - 연결됨: 초록(--success)
 * - 재인증 필요: 주황(--caution)
 * - 미연결: 회색
 *
 * #367: 예전에는 Tailwind 팔레트(green-600/orange-600)를 하드코딩해 라이트에서
 * 3.22:1까지 떨어졌다. 시맨틱 토큰으로 바꾸면 테마 전환도 토큰이 알아서 처리한다.
 */
export function ChannelStatusBadge({ connected, needsReauth }: ChannelStatusBadgeProps) {
  if (connected && needsReauth) {
    return (
      <Badge variant="outline" className="border-caution/40 text-caution">
        재인증 필요
      </Badge>
    );
  }

  if (connected) {
    return (
      <Badge variant="outline" className="border-success/40 text-success">
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
