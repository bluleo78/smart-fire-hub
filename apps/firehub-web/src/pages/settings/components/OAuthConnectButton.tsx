import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { toast } from 'sonner';

import { type ChannelSetting, type ChannelType, getChannelSettings, getOAuthUrl } from '../../../api/channels';
import { Button } from '../../../components/ui/button';
import { extractApiError } from '../../../lib/api-error';

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
 * - 팝업 차단 시 경고 toast. 60초 타임아웃 시에는 실제 연동 상태를 서버에서 재확인한 뒤
 *   연동이 안 된 경우에만 "취소됨" toast를 띄운다(2FA 등으로 60초를 넘겨도 백엔드 콜백은
 *   이미 성공했을 수 있으므로 무조건 취소로 단정하지 않는다).
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
    // 2FA·최초 로그인 등으로 60초를 넘기는 경우가 실제 환경에서 흔하며, 이 시점에 백엔드 콜백은
    // 이미 성공 처리됐을 수 있다. 따라서 "취소됨"으로 단정하지 않고 서버에서 실제 연동 상태를
    // 재조회한 뒤에만 취소/성공 여부를 안내한다.
    const timeoutId = setTimeout(() => {
      clearTimer();
      if (!popup.closed) {
        popup.close();
      }
      // 캐시 무효화(+ 강제 재조회)로 화면을 최신 상태로 반영하고, 재조회 결과를 직접 검사해
      // 실제로 연동이 안 된 경우에만 "취소됨" 메시지를 띄운다.
      void queryClient
        .fetchQuery({ queryKey: ['channel-settings'], queryFn: () => getChannelSettings().then((r) => r.data) })
        .then((settings: ChannelSetting[]) => {
          const isConnected = settings.some((s) => s.channel === channel && s.connected);
          if (isConnected) {
            toast.success(`${channel} 연동이 완료되었습니다.`);
          } else {
            toast.info(`${channel} 연동이 취소되었습니다.`);
          }
        })
        .catch(() => {
          // 재조회 자체가 실패한 경우 — 상태를 확정할 수 없으므로 오해를 줄 수 있는 "취소됨"
          // 대신 재확인을 안내한다.
          toast.info(`${channel} 연동 상태를 확인하지 못했습니다. 새로고침 후 확인해 주세요.`);
        });
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
    } catch (error) {
      // 400: OAuth 자격증명 미설정 (서버에서 명시적 에러 메시지 반환)
      // 그 외: 네트워크 오류 등 예기치 않은 오류
      const message = extractApiError(error, `${channel} 연동 URL을 불러오지 못했습니다.`);
      toast.error(message);
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
