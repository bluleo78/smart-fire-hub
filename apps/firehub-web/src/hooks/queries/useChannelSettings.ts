import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  type ChannelType,
  disconnectChannel,
  getChannelSettings,
  linkSlackUser,
  testChannel,
  updateChannelPreference,
} from '../../api/channels';
import { useMyPermissions } from './useMyPermissions';

/** 채널 설정 쿼리 키 */
const QUERY_KEY = ['channel-settings'] as const;

/**
 * 전체 채널 설정 목록 조회 훅
 * GET /api/v1/channels/settings
 */
export function useChannelSettings() {
  return useQuery({
    queryKey: QUERY_KEY,
    queryFn: () => getChannelSettings().then((r) => r.data),
  });
}

/**
 * 채널 활성화 여부 변경 뮤테이션 훅
 * PATCH /api/v1/channels/settings/{channel}/preference
 */
export function useUpdateChannelPreferenceMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ channel, enabled }: { channel: ChannelType; enabled: boolean }) =>
      updateChannelPreference(channel, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: QUERY_KEY });
    },
  });
}

/**
 * 채널 OAuth 연결 해제 뮤테이션 훅
 * DELETE /api/v1/channels/settings/{channel}
 */
export function useDisconnectChannelMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (channel: ChannelType) => disconnectChannel(channel),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: QUERY_KEY });
    },
  });
}

/**
 * 채널 테스트 발송 뮤테이션 훅
 * POST /api/v1/channels/settings/{channel}/test
 *
 * 응답은 200 OK이지만 success=false인 경우가 있으므로, 호출자가 응답을 검사해
 * 토스트(성공/실패)를 분기해야 한다 — SMTP 테스트와 동일한 처리 패턴.
 */
export function useTestChannelMutation() {
  return useMutation({
    mutationFn: (channel: ChannelType) => testChannel(channel),
  });
}

/**
 * Slack 사용자 ID 매핑 뮤테이션 훅
 * POST /api/v1/oauth/slack/link-user
 *
 * 성공 시 channel-settings 캐시를 무효화하여 SLACK 카드가 connected=true로 갱신되게 한다.
 */
export function useLinkSlackUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ workspaceId, slackUserId }: { workspaceId: number; slackUserId: string }) =>
      linkSlackUser(workspaceId, slackUserId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: QUERY_KEY });
    },
  });
}

/**
 * 워크스페이스 알림 관리(notification:admin)를 할 수 있는가 (WD-433). Slack 워크스페이스 설치(연동/재연결) 버튼 노출에 쓴다.
 * 최종 판정은 서버(/oauth/slack/auth-url) — 화면은 눌러도 403 만 뜰 버튼을 감추는 데만 쓴다.
 */
export function useCanAdminNotifications() {
  const { permissions, isLoading } = useMyPermissions();
  return { canAdmin: permissions.has('notification:admin'), isLoading };
}

