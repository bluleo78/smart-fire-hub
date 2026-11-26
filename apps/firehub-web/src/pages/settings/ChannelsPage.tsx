import { AlertTriangle, BellRing } from 'lucide-react';

import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';

import type { ChannelType } from '../../api/channels';
import { useChannelSettings } from '../../hooks/queries/useChannelSettings';
import { ChannelCard } from './components/ChannelCard';
import { ChannelCardSkeleton } from './components/ChannelCardSkeleton';

/** 로딩 중 그릴 스켈레톤 순서 — 서버가 내려주는 채널 순서(CHAT·EMAIL·KAKAO·SLACK)와 같아야 카드별 높이가 맞는다 (WD-896) */
const CHANNEL_SKELETON_ORDER: readonly ChannelType[] = ['CHAT', 'EMAIL', 'KAKAO', 'SLACK'];

/**
 * 알림 채널 설정 페이지 (/settings/channels)
 * - CHAT / EMAIL / KAKAO / SLACK 4개 채널 카드를 렌더링한다.
 * - 각 카드에서 채널 활성화 토글 및 OAuth 연동/해제가 가능하다.
 */
export default function ChannelsPage() {
  const { data: settings, isLoading, isError, isFetching, refetch } = useChannelSettings();

  return (
    <div className="space-y-6">
      {/* 페이지 헤더 */}
      <div className="flex items-center gap-3">
        <BellRing className="h-6 w-6 text-primary" />
        <div>
          <PageHeading>알림 채널 설정</PageHeading>
          <p className="text-sm text-muted-foreground mt-0.5">
            알림을 수신할 채널을 연결하고 활성화하세요.
          </p>
        </div>
      </div>

      {/* 로딩 스켈레톤 — 실제 카드와 같은 모양·높이(서버 순서 CHAT/EMAIL/KAKAO/SLACK 에 맞춘 모양)라 로딩 뒤 밀림이 없다 (WD-896) */}
      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-2">
          {CHANNEL_SKELETON_ORDER.map((channel) => (
            <ChannelCardSkeleton key={channel} channel={channel} />
          ))}
        </div>
      )}

      {/* 에러 상태 — 보여 줄 데이터가 없을 때만. 데이터가 있는 재조회 실패는 아래 가벼운 안내로 알린다 (WD-1169) */}
      {isError && !settings && (
        <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          채널 설정을 불러오는 데 실패했습니다. 잠시 후 다시 시도해 주세요.
        </div>
      )}

      {/* 재조회 실패 안내 — 카드는 마지막으로 받은(또는 저장 성공으로 반영한) 값이라 오류 배너로 가리지 않고,
          서버와 다를 수 있음만 알린다. 연결 해제·Slack 연결처럼 성공 뒤 재조회로만 카드가 바뀌는 동작에서
          재조회가 실패하면 카드가 옛 상태로 남으므로 이 안내가 없으면 토스트와 어긋난 채 아무 신호가 없다 (WD-1169 리뷰) */}
      {isError && settings && (
        <InlineBanner
          variant="warning"
          icon={<AlertTriangle />}
          actions={
            <Button size="sm" variant="outline" onClick={() => void refetch()} disabled={isFetching}>
              다시 불러오기
            </Button>
          }
        >
          최신 채널 설정을 불러오지 못했습니다. 화면이 실제 설정과 다를 수 있습니다.
        </InlineBanner>
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
