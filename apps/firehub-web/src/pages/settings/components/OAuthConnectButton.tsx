import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
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

/** 콜백 페이지가 결과를 알리는 BroadcastChannel 이름 — 서버 OAuthCallbackPage.BROADCAST_CHANNEL 과 같은 값 (WD-405) */
export const OAUTH_RESULT_CHANNEL = 'firehub-oauth';

/** 콜백 페이지가 보내는 결과 — connected(연결됨)·cancelled(동의 취소)·failed(제공자 오류) */
interface OAuthResultMessage {
  channel: ChannelType;
  result: 'connected' | 'cancelled' | 'failed';
}

/**
 * OAuth 팝업 연동 버튼
 * - 팝업은 Bearer 헤더를 전달할 수 없으므로, 먼저 /auth-url API를 호출하여 실제 OAuth URL을 받는다.
 * - 받은 URL을 팝업으로 열고, 콜백 페이지가 BroadcastChannel 로 알린 결과(연결·취소·실패)를 토스트로 안내한다(WD-405).
 *   팝업 닫힘도 감지해 쿼리 캐시를 갱신한다.
 * - 팝업 차단 시 경고 toast. 60초 타임아웃 시에는 실제 연동 상태를 서버에서 재확인한 뒤
 *   연동이 안 된 경우에만 "취소됨" toast를 띄운다(2FA 등으로 60초를 넘겨도 백엔드 콜백은
 *   이미 성공했을 수 있으므로 무조건 취소로 단정하지 않는다).
 */
export function OAuthConnectButton({ channel, oauthStartUrl, reauth = false }: OAuthConnectButtonProps) {
  const queryClient = useQueryClient();
  // 진행 중인 팝업 감시(닫힘 폴링·타임아웃·결과 채널)를 한 번에 정리하는 함수 — 언마운트 때도 부른다
  const cleanupRef = useRef<(() => void) | null>(null);
  const [isPending, setIsPending] = useState(false);

  useEffect(() => () => cleanupRef.current?.(), []);

  const refreshSettings = () => queryClient.invalidateQueries({ queryKey: ['channel-settings'] });

  const openPopup = (url: string) => {
    // noopener 를 주면 window.open 이 (팝업이 열려도) 늘 null 을 돌려줘 「차단됨」으로 잘못 안내하고 닫힘도 볼 수 없었다(WD-405).
    // 빈 창을 먼저 열어 핸들을 얻고 opener 를 끊은 뒤 제공자 주소로 보낸다 — 제공자 페이지가 이 창에 닿지 못하는 효과는 같다.
    const popup = window.open('about:blank', `oauth_${channel}`, 'width=640,height=720');

    if (!popup) {
      toast.warning('팝업이 차단되었습니다. 브라우저 설정에서 팝업을 허용해 주세요.');
      return;
    }
    popup.opener = null;
    popup.location.href = url;

    cleanupRef.current?.();
    // 콜백 페이지(같은 출처)가 보내는 결과를 듣는다 — 팝업은 opener 가 없어 postMessage 로는 알릴 수 없다
    // 결과는 타임아웃(60초)까지 듣는다 — popup.closed 에 묶지 않는다. 제공자 페이지가 Cross-Origin-Opener-Policy 를 보내면
    // 사용자가 아직 동의 화면에 있어도 핸들이 「닫힘」으로 보일 수 있어, 닫힘에 맞춰 끊으면 결과를 놓친다.
    const resultChannel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(OAUTH_RESULT_CHANNEL);

    // 60초 타임아웃 설정
    // 2FA·최초 로그인 등으로 60초를 넘기는 경우가 실제 환경에서 흔하며, 이 시점에 백엔드 콜백은
    // 이미 성공 처리됐을 수 있다. 따라서 "취소됨"으로 단정하지 않고 서버에서 실제 연동 상태를
    // 재조회한 뒤에만 취소/성공 여부를 안내한다.
    const timeoutId = setTimeout(() => {
      cleanup();
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

    // 팝업 닫힘 폴링 (200ms 간격) — 닫히면 캐시를 갱신해 연결 상태를 반영한다. 결과 알림이 없는 닫힘(사용자가 직접 닫음 등)도
    // 화면이 서버 상태를 따라가게 하려는 것이고, 결과 알림·타임아웃 감시는 그대로 둔다.
    const pollId = setInterval(() => {
      if (popup.closed) {
        clearInterval(pollId);
        void refreshSettings();
      }
    }, 200);

    function cleanup() {
      clearInterval(pollId);
      clearTimeout(timeoutId);
      resultChannel?.close();
      if (cleanupRef.current === cleanup) cleanupRef.current = null;
    }
    cleanupRef.current = cleanup;

    if (resultChannel) {
      resultChannel.onmessage = (event: MessageEvent<OAuthResultMessage>) => {
        // 다른 채널 카드의 팝업 결과는 그 카드가 처리한다
        if (event.data?.channel !== channel) return;
        cleanup();
        void refreshSettings();
        if (event.data.result === 'connected') {
          toast.success(`${channel} 연동이 완료되었습니다.`);
        } else if (event.data.result === 'cancelled') {
          toast.info(`${channel} 연동이 취소되었습니다.`);
        } else {
          toast.error(`${channel} 연동을 완료하지 못했습니다. 다시 시도해 주세요.`);
        }
      };
    }
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
