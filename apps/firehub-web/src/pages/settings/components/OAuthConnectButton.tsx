import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { toast } from 'sonner';

import { type ChannelType,getOAuthUrl } from '../../../api/channels';
import { Button } from '../../../components/ui/button';

interface OAuthConnectButtonProps {
  channel: ChannelType;
  /** 백엔드의 /auth-url 엔드포인트 경로 — 인증된 요청으로 실제 OAuth URL을 받아온다 */
  oauthStartUrl: string;
  /** 재연결 여부 — true이면 "재연결" 레이블 */
  reauth?: boolean;
}

/**
 * OAuth 팝업 연동 버튼
 * - 팝업은 Bearer 헤더를 전달할 수 없으므로, 먼저 /auth-url API를 호출하여 실제 OAuth URL을 받는다.
 * - 받은 URL을 팝업으로 직접 열고, 팝업 닫힘 감지 후 쿼리 캐시를 갱신한다.
 * - 팝업 차단 시 경고 toast, 60초 타임아웃 시 "취소됨" toast.
 */
export function OAuthConnectButton({ channel, oauthStartUrl, reauth = false }: OAuthConnectButtonProps) {
  const queryClient = useQueryClient();
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [isPending, setIsPending] = useState(false);

  const clearTimer = () => {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  };

  const openPopup = (url: string) => {
    const popup = window.open(url, `oauth_${channel}`, 'width=640,height=720,noopener,noreferrer');

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

  const handleConnect = async () => {
    setIsPending(true);
    try {
      // 팝업이 Bearer 헤더를 전달할 수 없으므로, 먼저 인증된 요청으로 실제 OAuth URL을 받는다.
      const { data } = await getOAuthUrl(oauthStartUrl);
      openPopup(data.url);
    } catch {
      toast.error(`${channel} 연동 URL을 불러오지 못했습니다.`);
    } finally {
      setIsPending(false);
    }
  };

  return (
    <Button size="sm" variant={reauth ? 'outline' : 'default'} onClick={handleConnect} disabled={isPending}>
      {reauth ? '재연결' : '연동하기'}
    </Button>
  );
}
