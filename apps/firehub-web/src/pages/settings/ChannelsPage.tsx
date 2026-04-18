import { AlertTriangle, BellRing } from 'lucide-react';

import { Skeleton } from '../../components/ui/skeleton';
import { useChannelSettings } from '../../hooks/queries/useChannelSettings';
import { ChannelCard } from './components/ChannelCard';

/**
 * 알림 채널 설정 페이지 (/settings/channels)
 * - CHAT / EMAIL / KAKAO / SLACK 4개 채널 카드를 렌더링한다.
 * - 각 카드에서 채널 활성화 토글 및 OAuth 연동/해제가 가능하다.
 */
export default function ChannelsPage() {
  const { data: settings, isLoading, isError } = useChannelSettings();

  return (
    <div className="space-y-6">
      {/* 페이지 헤더 */}
      <div className="flex items-center gap-3">
        <BellRing className="h-6 w-6 text-primary" />
        <div>
          <h1 className="text-xl font-semibold">알림 채널 설정</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            알림을 수신할 채널을 연결하고 활성화하세요.
          </p>
        </div>
      </div>

      {/* 로딩 스켈레톤 */}
      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-40 w-full rounded-lg" />
          ))}
        </div>
      )}

      {/* 에러 상태 */}
      {isError && (
        <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          채널 설정을 불러오는 데 실패했습니다. 잠시 후 다시 시도해 주세요.
        </div>
      )}

      {/* 채널 카드 그리드 */}
      {settings && (
        <div className="grid gap-4 sm:grid-cols-2">
          {settings.map((setting) => (
            <ChannelCard key={setting.channel} setting={setting} />
          ))}
        </div>
      )}
    </div>
  );
}
