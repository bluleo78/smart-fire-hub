import type { ChannelType } from '../../../api/channels';
import { Card, CardContent, CardHeader } from '../../../components/ui/card';
import { Skeleton } from '../../../components/ui/skeleton';
import { CHANNEL_DESCRIPTIONS } from './channelDescriptions';

/**
 * 알림 채널 카드(ChannelCard)의 로딩 스켈레톤.
 * - 실제 카드와 같은 Card/CardHeader/CardContent 뼈대를 써서 높이·모서리·테두리를 맞춘다.
 *   고정 높이 박스를 쓰면 로딩이 끝나는 순간 카드 높이가 달라져 화면이 밀리기 때문이다 (WD-896).
 * - 설명은 실제 문구를 투명하게 그려 카드 폭에 따라 꺾이는 줄 수까지 같게 하고, 나머지 줄 높이는 375px·1280px 에서 실제 카드를 잰 값이다 (헤더 67px, 주소 16px, 테스트 발송 줄 32px, OAuth 줄 32px). 실측은 E2E 높이 시험이 지킨다.
 * - 한계: 로딩 중에는 채널 상태를 모르므로 기본 상태 기준이다. 주소 줄은 서버가 모든 채널에 채워 보내므로(CHAT 「웹 인박스」,
 *   미연결 KAKAO 「카카오톡」, SLACK 「Slack」) 늘 그린다. SLACK 은 연결+재인증, OAuth 줄은 관리자 권한(버튼 32px) 기준이다. 비관리자 안내 문구(2줄)·워크스페이스 설치 완료
 *   (회원 ID 입력 상자) 같은 다른 상태는 맞추지 않는다.
 * @param channel 채널 종류. 서버 순서(CHAT·EMAIL·KAKAO·SLACK)로 그리는 호출부와 짝이라, 서버 순서가 바뀌면 높이가 어긋나고 E2E 가 잡는다.
 */
export function ChannelCardSkeleton({ channel }: { channel: ChannelType }) {
  const hasTestSend = channel !== 'CHAT';
  const hasOAuth = channel === 'KAKAO' || channel === 'SLACK';
  return (
    <Card data-testid="channel-card-skeleton" aria-hidden="true">
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            {/* 채널 아이콘 */}
            <Skeleton className="h-10 w-10 rounded-lg" />
            {/* 제목+배지 열: 실측 47px = 제목 줄 20px + 간격 2px + 배지 25px */}
            <div>
              <Skeleton className="h-5 w-20" />
              <Skeleton className="mt-0.5 h-[25px] w-12 rounded-md" />
            </div>
          </div>
          {/* 활성화 스위치 */}
          <Skeleton className="h-[1.15rem] w-8 rounded-full" />
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* 설명: 같은 문구를 투명 글자로 그린다 — 화면 폭·사이드바에 따라 꺾이는 줄 수가 실제 카드와 같아진다 */}
        <Skeleton className="w-fit text-sm text-transparent">{CHANNEL_DESCRIPTIONS[channel]}</Skeleton>
        {/* 주소 줄: 서버가 모든 채널에 displayAddress 를 채운다 */}
        <Skeleton className="h-4 w-40" />
        {hasTestSend && <Skeleton className="h-8 w-24" />}
        {hasOAuth && <Skeleton className="h-8 w-20" />}
      </CardContent>
    </Card>
  );
}
