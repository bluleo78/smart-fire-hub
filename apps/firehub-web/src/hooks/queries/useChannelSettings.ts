import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  type ChannelType,
  disconnectChannel,
  getChannelSettings,
  updateChannelPreference,
} from '../../api/channels';

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
