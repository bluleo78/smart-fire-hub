import type { ChannelType } from '../../../api/channels';

/**
 * 채널별 설명 문구.
 * ChannelCard 와 로딩 스켈레톤(ChannelCardSkeleton)이 함께 쓴다 — 스켈레톤이 같은 글을 투명하게 그려
 * 카드 폭에 따라 꺾이는 줄 수까지 실제 카드와 같게 하려는 것이다 (WD-896).
 */
export const CHANNEL_DESCRIPTIONS: Record<ChannelType, string> = {
  CHAT: '앱 내 알림 채널 — 항상 활성화됩니다.',
  EMAIL: '이메일로 알림을 받습니다. 계정 이메일로 발송됩니다.',
  KAKAO: '카카오 알림톡으로 알림을 받습니다.',
  SLACK: 'Slack 워크스페이스로 알림을 받습니다.',
};
