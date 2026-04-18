import { useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { toast } from 'sonner';

import type { ChannelType } from '../../../api/channels';
import { Button } from '../../../components/ui/button';

interface OAuthConnectButtonProps {
  channel: ChannelType;
  oauthStartUrl: string;
  /** 재연결 여부 — true이면 "재연결" 레이블 */
  reauth?: boolean;
}

/**
 * OAuth 팝업 연동 버튼
 * - window.open으로 OAuth 팝업을 열고, 팝업 닫힘 감지 후 쿼리 캐시를 갱신한다.
 * - 팝업 차단 시 경고 toast, 60초 타임아웃 시 "취소됨" toast.
 */
export function OAuthConnectButton({ channel, oauthStartUrl, reauth = false }: OAuthConnectButtonProps) {
  const queryClient = useQueryClient();
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const clearTimer = () => {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  };

  const handleConnect = () => {
    const popup = window.open(
      oauthStartUrl,
      `oauth_${channel}`,
      'width=640,height=720,noopener,noreferrer',
    );

    if (!popup) {
      toast.warning('팝업이 차단되었습니다. 브라우저 설정에서 팝업을 허용해 주세요.');
      return;
    }

    // 60초 타임아웃 설정
    const timeoutId = setTimeout(() => {
      clearTimer();
      if (!popup.closed) {
        popup.close();
      }
      toast.info(`${channel} 연동이 취소되었습니다.`);
    }, 60_000);

    // 팝업 닫힘 폴링 (200ms 간격)
    timerRef.current = setInterval(() => {
      if (popup.closed) {
        clearTimer();
        clearTimeout(timeoutId);
        // 팝업 닫힘 → 캐시 갱신하여 연결 상태 반영
        queryClient.invalidateQueries({ queryKey: ['channel-settings'] });
      }
    }, 200);
  };

  return (
    <Button size="sm" variant={reauth ? 'outline' : 'default'} onClick={handleConnect}>
      {reauth ? '재연결' : '연동하기'}
    </Button>
  );
}
