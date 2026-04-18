import { client } from './client';

/** 채널 종류 */
export type ChannelType = 'CHAT' | 'EMAIL' | 'KAKAO' | 'SLACK';

/**
 * 채널 설정 응답 타입 — 백엔드 ChannelSettingResponse DTO와 일치
 */
export interface ChannelSetting {
  channel: ChannelType;
  enabled: boolean;
  connected: boolean;
  needsReauth: boolean;
  displayAddress: string | null;
  oauthStartUrl: string | null;
}

/**
 * 전체 채널 설정 목록 조회
 * GET /api/v1/channels/settings
 */
export async function getChannelSettings() {
  return client.get<ChannelSetting[]>('/channels/settings');
}

/**
 * 채널 활성화 여부 변경
 * PATCH /api/v1/channels/settings/{channel}/preference
 */
export async function updateChannelPreference(channel: ChannelType, enabled: boolean) {
  return client.patch<void>(`/channels/settings/${channel}/preference`, { enabled });
}

/**
 * 채널 OAuth 연결 해제
 * DELETE /api/v1/channels/settings/{channel}
 */
export async function disconnectChannel(channel: ChannelType) {
  return client.delete<void>(`/channels/settings/${channel}`);
}
