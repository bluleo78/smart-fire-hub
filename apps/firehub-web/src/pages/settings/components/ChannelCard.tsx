import { Bell, Mail, MessageSquare, Slack } from 'lucide-react';
import { toast } from 'sonner';

import type { ChannelSetting, ChannelType } from '../../../api/channels';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../../../components/ui/alert-dialog';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../../../components/ui/card';
import { Switch } from '../../../components/ui/switch';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../../components/ui/tooltip';
import {
  useDisconnectChannelMutation,
  useUpdateChannelPreferenceMutation,
} from '../../../hooks/queries/useChannelSettings';
import { ChannelStatusBadge } from './ChannelStatusBadge';
import { OAuthConnectButton } from './OAuthConnectButton';

/** 채널별 아이콘 매핑 */
const CHANNEL_ICONS: Record<ChannelType, React.ElementType> = {
  CHAT: Bell,
  EMAIL: Mail,
  KAKAO: MessageSquare,
  SLACK: Slack,
};

/** 채널별 표시 이름 */
const CHANNEL_LABELS: Record<ChannelType, string> = {
  CHAT: '앱 알림',
  EMAIL: '이메일',
  KAKAO: '카카오 알림톡',
  SLACK: 'Slack',
};

/** 채널별 설명 */
const CHANNEL_DESCRIPTIONS: Record<ChannelType, string> = {
  CHAT: '앱 내 알림 채널 — 항상 활성화됩니다.',
  EMAIL: '이메일로 알림을 받습니다. 계정 이메일로 발송됩니다.',
  KAKAO: '카카오 알림톡으로 알림을 받습니다.',
  SLACK: 'Slack 워크스페이스로 알림을 받습니다.',
};

interface ChannelCardProps {
  setting: ChannelSetting;
}

/**
 * 채널 설정 카드 컴포넌트
 * - 채널 아이콘, 이름, 연결 상태 배지 표시
 * - 활성화/비활성화 토글 (CHAT은 항상 활성화 - 비활성화 불가)
 * - KAKAO/SLACK: 연동하기 / 재연결 / 연결 해제 버튼
 * - EMAIL: 계정 이메일 표시
 */
export function ChannelCard({ setting }: ChannelCardProps) {
  const { channel, enabled, connected, needsReauth, displayAddress, oauthStartUrl } = setting;

  const Icon = CHANNEL_ICONS[channel];
  const label = CHANNEL_LABELS[channel];
  const description = CHANNEL_DESCRIPTIONS[channel];

  const updatePreference = useUpdateChannelPreferenceMutation();
  const disconnect = useDisconnectChannelMutation();

  const isChatChannel = channel === 'CHAT';
  const isOAuthChannel = channel === 'KAKAO' || channel === 'SLACK';

  const handleToggle = (checked: boolean) => {
    updatePreference.mutate(
      { channel, enabled: checked },
      {
        onError: () => {
          toast.error('채널 설정 변경에 실패했습니다.');
        },
      },
    );
  };

  const handleDisconnect = () => {
    disconnect.mutate(channel, {
      onSuccess: () => {
        toast.success(`${label} 연결이 해제되었습니다.`);
      },
      onError: () => {
        toast.error('연결 해제에 실패했습니다.');
      },
    });
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-3">
          {/* 아이콘 + 이름 + 상태 배지 */}
          <div className="flex items-center gap-3 min-w-0">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted">
              <Icon className="h-5 w-5 text-muted-foreground" />
            </div>
            <div className="min-w-0">
              <CardTitle className="text-sm font-semibold">{label}</CardTitle>
              <div className="mt-0.5">
                <ChannelStatusBadge connected={connected} needsReauth={needsReauth} />
              </div>
            </div>
          </div>

          {/* 활성화 토글 */}
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <div>
                  <Switch
                    checked={connected ? enabled : false}
                    onCheckedChange={handleToggle}
                    disabled={isChatChannel || updatePreference.isPending || !connected}
                    aria-label={`${label} 채널 활성화`}
                  />
                </div>
              </TooltipTrigger>
              {isChatChannel && (
                <TooltipContent>
                  앱 알림은 안전망 채널로 항상 활성화됩니다.
                </TooltipContent>
              )}
            </Tooltip>
          </TooltipProvider>
        </div>
      </CardHeader>

      <CardContent className="space-y-3">
        <CardDescription>{description}</CardDescription>

        {/* displayAddress 표시 (이메일, 카카오 수신 번호, Slack 채널 등) */}
        {displayAddress && (
          <p className="text-xs text-muted-foreground truncate">
            {displayAddress}
          </p>
        )}

        {/* OAuth 채널 액션 버튼 */}
        {isOAuthChannel && (
          <div className="flex items-center gap-2 flex-wrap">
            {/* 미연결 or 재인증 필요: 연동/재연결 버튼 */}
            {(!connected || needsReauth) && oauthStartUrl && (
              <OAuthConnectButton
                channel={channel}
                oauthStartUrl={oauthStartUrl}
                reauth={needsReauth}
              />
            )}

            {/* 연결됨 + 재인증 불필요: 연결 해제 버튼 */}
            {connected && !needsReauth && (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button size="sm" variant="outline">
                    연결 해제
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>{label} 연결 해제</AlertDialogTitle>
                    <AlertDialogDescription>
                      {label} 채널 연결을 해제하시겠습니까? 알림 수신이 중단됩니다.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>취소</AlertDialogCancel>
                    <AlertDialogAction onClick={handleDisconnect}>
                      연결 해제
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            )}

            {/* Slack: 사용자 매핑 안내 (Stage 2 Task 5 MVP — 추후 지원) */}
            {channel === 'SLACK' && connected && (
              <Badge variant="secondary" className="text-xs">
                사용자 매핑 추후 지원
              </Badge>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
