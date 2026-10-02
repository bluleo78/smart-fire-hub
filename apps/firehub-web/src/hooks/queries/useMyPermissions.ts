import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { authApi } from '../../api/auth';

/**
 * 내 권한 코드(현재 워크스페이스). 웹은 지금까지 역할 이름(isAdmin)만 봤는데, 멤버 추가는 스펙상
 * user:write·role:assign 권한 기준이라 권한 코드를 직접 읽는다. 최종 판정은 서버 — 여기는 노출 결정용.
 */
export function useMyPermissions() {
  const query = useQuery({
    queryKey: ['auth', 'me', 'permissions'],
    queryFn: () => authApi.myPermissions().then((r) => r.data),
    staleTime: 60_000,
  });
  const permissions = useMemo(() => new Set(query.data ?? []), [query.data]);
  return { permissions, isLoading: query.isLoading };
}
