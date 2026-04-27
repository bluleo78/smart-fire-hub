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

/**
 * OAuth 인증 URL 조회 — 팝업이 Bearer 헤더를 전달할 수 없으므로, 먼저 이 API를 호출하여
 * 실제 OAuth 인증 URL을 받은 뒤 해당 URL을 팝업으로 직접 연다.
 */
export async function getOAuthUrl(path: string) {
  return client.get<{ url: string }>(path.replace('/api/v1', ''));
}

/** 채널 테스트 발송 응답 — 백엔드 ChannelTestResult DTO와 일치 */
export interface ChannelTestResponse {
  success: boolean;
  message: string;
}

/**
 * 채널 테스트 발송
 * POST /api/v1/channels/settings/{channel}/test
 *
 * 200 OK + success: true/false 형태로 반환된다.
 * SMTP 테스트(/admin/settings 이메일 탭)와 동일한 응답 형태로 통일.
 */
export async function testChannel(channel: ChannelType) {
  return client.post<ChannelTestResponse>(`/channels/settings/${channel}/test`);
}
